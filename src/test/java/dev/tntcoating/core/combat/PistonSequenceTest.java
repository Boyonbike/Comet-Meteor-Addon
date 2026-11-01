/*
 * Original Comet test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Phase 5.3/5.4/5.5: ordered single-push sequence, C21 push completion and fail-closed cleanup. */
final class PistonSequenceTest {
    private static final ConnectionKey CONN = new ConnectionKey(3);
    private static final EntityKey TARGET = new EntityKey(CONN, 1, UUID.randomUUID());
    private static final Vec POSITION = new Vec(0.5, -60.0, 0.5);

    @Test
    void happyPathFollowsTheExactOrderedDependencies() {
        PistonSequence seq = sequence(PistonSequence.Config.defaults());
        assertTrue(seq.begin(0));
        seq.reservationAccepted(0);
        seq.stageSettled(PistonSequence.PistonPhase.SUPPORTS, 1);
        seq.stageSettled(PistonSequence.PistonPhase.PISTON, 2);
        assertEquals(PistonSequence.PistonPhase.CRYSTAL, seq.phase());
        assertEquals(PistonSequence.BindResult.UNIQUE, seq.bindSpawn(List.of(spawn(seq, TARGET)), 3));
        seq.stageSettled(PistonSequence.PistonPhase.CRYSTAL, 4);
        seq.stageSettled(PistonSequence.PistonPhase.POWER, 5);
        assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, seq.phase());
        assertFalse(seq.flags().waitingForCrystalMove(), "C21: never wait for a crystal move observation");
        seq.pistonProgress(1.0, 7); // two authoritative extension ticks after POWER at tick 5
        assertEquals(PistonSequence.PistonPhase.ATTACK, seq.phase());
        assertEquals(seq.plan().expectedPushed(), seq.computedPushed());
        assertTrue(seq.damageAtComputedPosition(20.0, 6.0, 8));
        seq.attackSent(8);
        assertEquals(PistonSequence.PistonPhase.WAIT_REMOVE, seq.phase());
        seq.removalObserved(9);
        assertEquals(PistonSequence.PistonPhase.CLEANUP, seq.phase());
        assertTrue(seq.flags().cleanupPending());
        seq.cleanupCompleted(10);
        assertEquals(PistonSequence.PistonPhase.COOLDOWN, seq.phase());
        assertEquals(List.of(PistonSequence.PistonPhase.RESERVE, PistonSequence.PistonPhase.SUPPORTS,
            PistonSequence.PistonPhase.PISTON,
            PistonSequence.PistonPhase.CRYSTAL, PistonSequence.PistonPhase.POWER,
            PistonSequence.PistonPhase.WAIT_PUSH, PistonSequence.PistonPhase.ATTACK,
            PistonSequence.PistonPhase.WAIT_REMOVE, PistonSequence.PistonPhase.CLEANUP,
            PistonSequence.PistonPhase.COOLDOWN), seq.history());
        assertTrue(seq.flags().attackSafe());
    }

    @Test
    void powerBeforeCrystalPlanIsRejected() {
        PistonGeometry.PistonPush push = push();
        Intent piston = intent(1, Action.PLACE_PISTON, push.piston(), Set.of(), Set.of());
        Intent crystal = intent(2, Action.PLACE_CRYSTAL, push.source(), Set.of(piston), Set.of());
        Intent power = intent(3, Action.PLACE_POWER, push.power(), Set.of(), Set.of());
        PistonPlanner.Plan bad = new PistonPlanner.Plan(7, TARGET, push, List.of(piston, power, crystal),
            List.of(push.piston(), push.power()), 20.0, push.expectedPushed());
        assertThrows(IllegalArgumentException.class,
            () -> new PistonSequence(bad, PistonSequence.Config.defaults()));
        PistonPlanner.Plan good = new PistonPlanner.Plan(7, TARGET, push, List.of(piston, crystal, power),
            List.of(push.piston(), push.power()), 20.0, push.expectedPushed());
        new PistonSequence(good, PistonSequence.Config.defaults());
    }

    @Test
    void ambiguousSpawnQuarantinesAndRequiresReconciliation() {
        PistonSequence seq = atCrystal();
        PistonSequence.Spawn one = spawn(seq, TARGET);
        PistonSequence.Spawn two = new PistonSequence.Spawn(
            new EntityKey(CONN, 2, UUID.randomUUID()), DirectPlanner.END_CRYSTAL, UUID.randomUUID(),
            seq.plan().source());
        assertEquals(PistonSequence.BindResult.AMBIGUOUS, seq.bindSpawn(List.of(one, two), 3));
        assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, seq.phase());
        assertTrue(seq.flags().manualCleanupRequired());
        assertTrue(seq.flags().noNewTrapUntilReconciled());
        assertTrue(seq.flags().riskRetained());
        seq.reconcile();
        assertFalse(seq.flags().noNewTrapUntilReconciled());
    }

    @Test
    void onlyAUniqueIdAndUuidSpawnInTheReservedCellBinds() {
        PistonSequence seq = atCrystal();
        PistonSequence.Spawn elsewhere = new PistonSequence.Spawn(TARGET, DirectPlanner.END_CRYSTAL,
            UUID.randomUUID(), new Cell(5, -60, 5));
        assertEquals(PistonSequence.BindResult.UNMATCHED, seq.bindSpawn(List.of(elsewhere), 3));
        assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, seq.phase());
        seq = atCrystal();
        assertEquals(PistonSequence.BindResult.UNIQUE, seq.bindSpawn(List.of(spawn(seq, TARGET)), 3));
        assertEquals(PistonSequence.BindResult.ALREADY_BOUND, seq.bindSpawn(List.of(spawn(seq, TARGET)), 4));
    }

    @Test
    void noCrystalMoveObservationIsEverWaitedFor() {
        PistonSequence seq = atPush();
        assertFalse(seq.flags().waitingForCrystalMove());
        // Client observations never advance WAIT_PUSH; only authoritative progress does.
        Vec computed = seq.computedPushed();
        seq.clientPushObserved(new Vec(computed.x() + 0.02, computed.y(), computed.z()), 7);
        assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, seq.phase());
        seq.pistonProgress(0.51, 7);
        assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, seq.phase());
        assertFalse(seq.flags().waitingForCrystalMove());
    }

    @Test
    void clientSimulationIsCrossCheckedNotTrusted() {
        PistonSequence seq = atPush();
        Vec computed = seq.computedPushed();
        seq.clientPushObserved(new Vec(computed.x() + 0.02, computed.y(), computed.z()), 7);
        assertTrue(seq.crossCheckUsed());
        assertFalse(seq.clientDisagreement(), "0.02 off is within the measured tolerance");
        assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, seq.phase());
        seq.clientPushObserved(new Vec(computed.x() + 1.0, computed.y(), computed.z()), 8);
        assertTrue(seq.clientDisagreement());
        assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, seq.phase());
        assertFalse(seq.flags().attackSafe());
    }

    @Test
    void targetLeavingBeforePowerAbortsWithoutSendingPower() {
        PistonSequence seq = atCrystal();
        seq.targetMoved(new Vec(1.5, -60.0, 0.5), false, 3);
        assertEquals(PistonSequence.PistonPhase.ABORTED, seq.phase());
        assertTrue(seq.flags().riskRetained());
        assertFalse(seq.flags().broadLocksHeld());
        assertFalse(seq.flags().attackSafe());
    }

    @Test
    void targetLeavingAfterPowerContinuesOnlyWhenTheAttackStaysSafe() {
        PistonSequence seq = atPush();
        seq.targetMoved(new Vec(1.5, -60.0, 0.5), true, 7);
        assertTrue(seq.phase() == PistonSequence.PistonPhase.WAIT_PUSH);
        seq = atPush();
        seq.targetMoved(new Vec(9.5, -50.0, 9.5), false, 7);
        assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, seq.phase());
        assertFalse(seq.flags().attackSafe());
        assertTrue(seq.flags().riskRetained());
    }

    @Test
    void failedDamageAtTheComputedPositionMeansNoAttackAndCleanup() {
        PistonSequence seq = atAttack();
        assertFalse(seq.damageAtComputedPosition(2.0, 6.0, 9));
        assertEquals(PistonSequence.PistonPhase.CLEANUP, seq.phase());
        seq.attackSent(10);
        assertEquals(PistonSequence.PistonPhase.CLEANUP, seq.phase(), "no attack may be sent");
        assertFalse(seq.flags().attackSafe());
        assertTrue(seq.flags().riskRetained());
    }

    @Test
    void stageTimeoutIsRttP99PlusFiveAndStructuralRetriesAreBounded() {
        PistonSequence seq = new PistonSequence(plan(), new PistonSequence.Config(30, 200, 40, false));
        assertEquals(35, seq.config().stageTimeoutTicks());
        seq.begin(0);
        seq.reservationAccepted(0);
        assertEquals("timeout:supports", seq.tick(36).orElseThrow());
        assertEquals(1, seq.structuralRetries());
        assertEquals("timeout:supports", seq.tick(72).orElseThrow());
        assertEquals(2, seq.structuralRetries());
        seq.tick(109);
        assertEquals(PistonSequence.PistonPhase.ABORTED, seq.phase());
    }

    @Test
    void unresolvedCrystalPlacementIsNeverRetried() {
        PistonSequence seq = atCrystalTicks(0);
        seq.tick(36);
        assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, seq.phase());
        assertEquals(0, seq.structuralRetries());
        assertTrue(seq.flags().manualCleanupRequired());
        assertTrue(seq.flags().riskRetained());
    }

    @Test
    void trapDeadlineReleasesBroadLocksButKeepsRiskAndExclusions() {
        PistonSequence seq = atPush();
        seq.tick(PistonSequence.TRAP_DEADLINE_TICKS);
        assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, seq.phase());
        assertTrue(seq.flags().riskRetained());
        assertFalse(seq.flags().broadLocksHeld());
        assertTrue(seq.flags().spatialExclusionsKept());
        assertTrue(seq.flags().manualCleanupRequired());
        assertTrue(seq.flags().noNewTrapUntilReconciled());
    }

    @Test
    void cleanupDeadlineBacksOffOnceThenEscalates() {
        PistonSequence seq = atCleanup(10);
        assertEquals("cleanup-retry", seq.tick(52).orElseThrow());
        assertEquals(PistonSequence.PistonPhase.CLEANUP, seq.phase());
        seq.tick(93);
        assertEquals(PistonSequence.PistonPhase.ABORTED, seq.phase());
        assertTrue(seq.flags().riskRetained());
    }

    @Test
    void disableKeepsHazardsAndRiskWithoutACleanupSend() {
        PistonSequence seq = atPush();
        seq.disable();
        assertTrue(seq.flags().disabled());
        assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, seq.phase());
        assertTrue(seq.flags().riskRetained());
        assertFalse(seq.flags().cleanupPending(), "disable must not schedule a cleanup packet");
        assertTrue(seq.flags().manualCleanupRequired());
    }

    @Test
    void deathAndRespawnEndTheGenerationButKeepHazards() {
        PistonSequence seq = atPush();
        seq.death();
        assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, seq.phase());
        assertTrue(seq.flags().riskRetained());
        assertTrue(seq.flags().manualCleanupRequired());
        seq.respawn();
        assertTrue(seq.flags().riskRetained());
        assertTrue(seq.flags().manualCleanupRequired());
        seq.reconcile();
        assertFalse(seq.flags().noNewTrapUntilReconciled());
    }

    @Test
    void cleanupIsOwnershipAwareAndManualByDefault() {
        PistonSequence seq = planOnly(PistonSequence.Config.defaults());
        assertEquals(PistonSequence.CleanupAction.MINE, seq.cleanupAction(PistonSequence.Ownership.CONFIRMED_OURS));
        assertEquals(PistonSequence.CleanupAction.MANUAL,
            seq.cleanupAction(PistonSequence.Ownership.INFERRED_OURS));
        assertEquals(PistonSequence.CleanupAction.MANUAL, seq.cleanupAction(PistonSequence.Ownership.CONTESTED));
        assertEquals(PistonSequence.CleanupAction.MANUAL, seq.cleanupAction(PistonSequence.Ownership.UNKNOWN));
        PistonSequence inferred = planOnly(new PistonSequence.Config(0, 100, 40, true));
        assertEquals(PistonSequence.CleanupAction.MINE, inferred.cleanupAction(PistonSequence.Ownership.INFERRED_OURS));
        assertEquals(PistonSequence.CleanupAction.MANUAL, inferred.cleanupAction(PistonSequence.Ownership.CONTESTED));
    }

    @Test
    void aSingleTrapSlotRefusesASecondActiveTrap() {
        PistonSequence.TrapSlot slot = new PistonSequence.TrapSlot();
        PistonSequence first = planOnly(PistonSequence.Config.defaults());
        PistonSequence second = planOnly(PistonSequence.Config.defaults());
        assertTrue(slot.begin(first, 0));
        assertFalse(slot.begin(second, 1));
        assertTrue(slot.active().isPresent());
        first.disable();
        slot.release(first);
        assertTrue(slot.begin(second, 2));
    }

    // ------------------------------------------------------------------ fixtures

    private static PistonSequence sequence(PistonSequence.Config config) {
        return new PistonSequence(plan(), config);
    }

    private static PistonSequence planOnly(PistonSequence.Config config) {
        return new PistonSequence(plan(), config);
    }

    private static PistonGeometry.PistonPush push() {
        return PistonGeometry.layout(new Cell(0, -60, 0), PistonGeometry.EAST, 0,
            PistonGeometry.PowerKind.REDSTONE_TORCH);
    }

    @Test
    void cleanupAndCooldownCompletionNeverRetireRisk() {
        PistonSequence seq = atCleanup(10);
        assertTrue(seq.flags().riskRetained());
        seq.cleanupCompleted(11);
        assertEquals(PistonSequence.PistonPhase.COOLDOWN, seq.phase());
        assertTrue(seq.flags().riskRetained(), "cleanup completion never retires risk");
        seq.cooldownCompleted(12);
        assertTrue(seq.flags().riskRetained(), "cooldown completion never retires risk");
        assertEquals(0, seq.riskRetirementRequests());
    }

    @Test
    void preExistingForeignAndLateSpawnsAreNeverBound() {
        PistonSequence seq = atCrystal();
        PistonSequence.Spawn foreign = spawn(seq, TARGET);
        assertEquals(PistonSequence.BindResult.UNMATCHED,
            seq.bindSpawn(List.of(foreign), Set.of(foreign.uuid()), Set.of(), 3));
        assertEquals(PistonSequence.PistonPhase.CRYSTAL, seq.phase(), "no foreign spawn may bind");

        seq = atCrystal();
        PistonSequence.Spawn ours = spawn(seq, TARGET);
        assertEquals(PistonSequence.BindResult.UNMATCHED,
            seq.bindSpawn(List.of(ours), Set.of(), Set.of(ours.entity()), 3));

        seq = atCrystalTicks(3);
        assertEquals(PistonSequence.BindResult.UNMATCHED,
            seq.bindSpawn(List.of(spawn(seq, TARGET)), 3), "a spawn at the placement tick is pre-existing");
    }

    @Test
    void duplicateSpawnsWithTheSameUuidAreQuarantined() {
        PistonSequence seq = atCrystal();
        PistonSequence.Spawn one = spawn(seq, TARGET);
        PistonSequence.Spawn duplicate = new PistonSequence.Spawn(one.entity(), one.entityType(),
            one.uuid(), one.cell());
        assertEquals(PistonSequence.BindResult.AMBIGUOUS,
            seq.bindSpawn(List.of(one, duplicate), 3));
        assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, seq.phase());
        assertTrue(seq.flags().manualCleanupRequired());
        assertTrue(seq.flags().riskRetained());
    }

    private static Intent intent(long id, Action action, Cell cell, Set<Intent> deps, Set<EntityKey> locks) {
        return new Intent(id, 7L, Strategy.PISTON, action, cell, null, Set.of(cell), locks, deps, 200);
    }

    private static PistonPlanner.Plan plan() {
        PistonGeometry.PistonPush push = push();
        Intent piston = intent(1, Action.PLACE_PISTON, push.piston(), Set.of(), Set.of());
        Intent crystal = intent(2, Action.PLACE_CRYSTAL, push.source(), Set.of(piston), Set.of());
        Intent power = intent(3, Action.PLACE_POWER, push.power(), Set.of(crystal), Set.of());
        return new PistonPlanner.Plan(7L, TARGET, push, List.of(piston, crystal, power),
            List.of(push.piston(), push.power()), 20.0, push.expectedPushed());
    }

    private static PistonSequence atCrystal() {
        return atCrystalTicks(1);
    }

    private static PistonSequence atCrystalTicks(int settled) {
        PistonSequence seq = sequence(PistonSequence.Config.defaults());
        seq.begin(0);
        seq.reservationAccepted(0);
        seq.stageSettled(PistonSequence.PistonPhase.SUPPORTS, settled);
        seq.stageSettled(PistonSequence.PistonPhase.PISTON, settled);
        assertEquals(PistonSequence.PistonPhase.CRYSTAL, seq.phase());
        return seq;
    }

    private static PistonSequence atPush() {
        PistonSequence seq = atCrystal();
        seq.bindSpawn(List.of(spawn(seq, TARGET)), 2);
        seq.stageSettled(PistonSequence.PistonPhase.CRYSTAL, 2);
        seq.stageSettled(PistonSequence.PistonPhase.POWER, 5);
        assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, seq.phase());
        return seq;
    }

    private static PistonSequence atAttack() {
        PistonSequence seq = atPush();
        seq.pistonProgress(1.0, 7);
        assertEquals(PistonSequence.PistonPhase.ATTACK, seq.phase());
        return seq;
    }

    private static PistonSequence atCleanup(int tick) {
        PistonSequence seq = atAttack();
        seq.damageAtComputedPosition(20.0, 6.0, tick);
        seq.attackSent(tick);
        seq.removalObserved(tick + 1);
        assertEquals(PistonSequence.PistonPhase.CLEANUP, seq.phase());
        return seq;
    }

    private static PistonSequence.Spawn spawn(PistonSequence seq, EntityKey entity) {
        return new PistonSequence.Spawn(entity, DirectPlanner.END_CRYSTAL, UUID.randomUUID(),
            seq.plan().source());
    }
}
