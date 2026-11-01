/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.ActionGeneration;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatMetrics;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.CrystalCombatGate;
import dev.tntcoating.core.combat.CrystalState;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.HazardId;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.ServerTickInterval;
import dev.tntcoating.core.combat.SuspensionCause;
import dev.tntcoating.core.combat.Vec;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 2d lifecycle tests for the CrystalCombat controller, driven entirely by the existing runtime
 * fixture and fakes. Every assertion is about enable/disable/death/respawn/connection state, the one
 * send path staying silent, or the published wait reason; no real client is involved.
 */
final class CrystalCombatModuleTest {
    @BeforeAll
    static void bootstrap() {
        AdapterTestSupport.bootstrap();
    }

    @AfterEach
    void resetGate() {
        CrystalCombatGate.deactivate();
        CrystalCombatGate.setProtector(null);
    }

    @Test
    void capabilityRefusalStaysDisabledAndNeverStartsTheStack() {
        AtomicBoolean factoryCalled = new AtomicBoolean();
        CrystalCombatController controller = new CrystalCombatController(
            () -> "capability missing: CrystalAuraMixin is not injected",
            () -> {
                factoryCalled.set(true);
                throw new AssertionError("the runtime must not start when a critical hook is missing");
            });

        assertFalse(controller.enable());
        assertFalse(controller.enabled());
        assertFalse(factoryCalled.get());
        assertFalse(controller.sessionOpen());
        assertFalse(CrystalCombatGate.suppressMeteor(), "the gate must never activate after a refusal");
        assertEquals("capability missing: CrystalAuraMixin is not injected", controller.infoString());
        assertEquals(controller.infoString(), controller.waitReason());
        assertEquals(0L, controller.metrics().placeAttempts());
        assertEquals(0L, controller.metrics().attackAttempts());
    }

    @Test
    void readinessFailureIsReportedInsteadOfStartingTheStack() {
        AtomicBoolean factoryCalled = new AtomicBoolean();
        CrystalCombatController controller = new CrystalCombatController(
            () -> {
                throw new IllegalStateException("probe exploded");
            },
            () -> {
                factoryCalled.set(true);
                throw new AssertionError("no stack after a readiness failure");
            });

        assertFalse(controller.enable());
        assertFalse(factoryCalled.get());
        assertEquals("capability check failed: probe exploded", controller.refusalReason());
        assertFalse(CrystalCombatGate.suppressMeteor());
    }

    @Test
    void runtimeStartFailureStaysDisabledWithAVisibleReason() {
        CrystalCombatController controller = new CrystalCombatController(() -> null,
            () -> {
                throw new IllegalStateException("missing mixin");
            });

        assertFalse(controller.enable());
        assertFalse(controller.enabled());
        assertEquals("runtime start failed: missing mixin", controller.infoString());
        assertFalse(CrystalCombatGate.suppressMeteor());
    }

    @Test
    void successfulEnableBindsTheLedgerProtectorAndActivatesTheGate() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        HazardId hazard = sentHazard(fixture);
        CrystalCombatController controller = controller(fixture);

        assertTrue(controller.enable());
        assertTrue(controller.enabled());
        assertTrue(controller.sessionOpen());
        assertTrue(CrystalCombatGate.suppressMeteor());
        assertTrue(fixture.crystals.unresolved(hazard));
        assertTrue(CrystalCombatGate.protects(fixture.crystalUuid),
            "the gate protector is bound from the ledger at enable");
        assertFalse(CrystalCombatGate.protects(UUID.randomUUID()));

        controller.onJoined();
        assertEquals("idle", controller.infoString());
        assertEquals("idle", controller.waitReason());
    }

    @Test
    void disableDeactivatesTheGateAndSendsNothingAfterwards() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        HazardId hazard = sentHazard(fixture);
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());
        ActionGeneration before = fixture.session.actionGeneration();

        controller.disable();

        assertFalse(controller.enabled());
        assertFalse(CrystalCombatGate.suppressMeteor(), "disable deactivates the gate immediately");
        assertTrue(fixture.session.isOpen(), "disable keeps the connection so observation stays passive");
        assertNotEquals(before, fixture.session.actionGeneration());
        assertTrue(fixture.crystals.unresolved(hazard), "hazards survive disable");
        assertTrue(fixture.crystals.protects(fixture.crystalUuid), "sent risk keeps its hazard protection");
        assertEquals(0, fixture.sink.attempts);

        // The old ticket is dead: every direct attempt to dispatch after disable must be refused.
        assertNotNull(fixture.runtime.dispatch(fixture.place(), fixture.ticket));
        assertEquals(0, fixture.sink.attempts, "no packet may leave after disable");

        controller.tick(1);
        controller.onDeath();
        controller.onRespawn();
        assertEquals(0, fixture.sink.attempts, "lifecycle calls after disable never send");
        assertEquals("disabled", controller.waitReason());
    }

    @Test
    void deathEndsTheGenerationAndKeepsHazardsAndDebt() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        HazardId hazard = sentHazard(fixture);
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());
        ActionGeneration before = fixture.session.actionGeneration();

        controller.onDeath();

        assertNotSame(before, fixture.session.actionGeneration(), "death ends the action generation");
        assertTrue(fixture.budget.suspended());
        assertTrue(fixture.budget.suspensionCauses().contains(SuspensionCause.DEATH));
        assertTrue(fixture.crystals.unresolved(hazard), "death never retires hazards");
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(0, fixture.sink.attempts);
        assertTrue(controller.waitReason().startsWith("suspended: "));
        assertTrue(controller.waitReason().contains("death"));
    }

    @Test
    void respawnReconcilesAndReopensSendingOnlyAfterThat() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        HazardId hazard = sentHazard(fixture);
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());
        controller.onDeath();

        controller.onRespawn();

        assertTrue(fixture.session.isOpen());
        assertTrue(controller.sessionOpen());
        assertFalse(fixture.budget.suspensionCauses().contains(SuspensionCause.DEATH),
            "respawn reconciliation clears the death suspension");
        assertTrue(fixture.crystals.unresolved(hazard), "respawn keeps unresolved hazard evidence");
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void connectionCloseClearsOnlyThatConnection() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());
        ConnectionKey old = fixture.ticket.conn();

        controller.onLeft();

        assertFalse(fixture.session.isOpen());
        assertFalse(controller.sessionOpen());
        assertEquals(0, fixture.sink.attempts);

        controller.tick(7);
        assertEquals(0, fixture.sink.attempts, "no send may follow the closed connection");

        controller.onJoined();
        assertTrue(fixture.session.isOpen());
        assertNotEquals(old, fixture.session.connection());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void waitReasonsFollowTheDocumentedPriority() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        assertEquals("disabled", controller.waitReason());
        assertTrue(controller.enable());
        controller.onJoined();
        assertEquals("idle", controller.waitReason());

        fixture.client.crystals.clear();
        assertEquals("no crystals", controller.waitReason());

        Optional<RiskId> risk = fixture.budget.reserve(CombatRuntimeFixture.PLAN,
            fixture.exposure(4.0), new ServerTickInterval(10, 12), fixture.state());
        assertTrue(risk.isPresent(), "fixture risk reservation must succeed");
        fixture.budget.mayHaveLeft(risk.orElseThrow());
        assertEquals("risk debt", controller.waitReason());

        fixture.budget.suspend(SuspensionCause.SETBACK);
        assertEquals("suspended: setback", controller.waitReason());
        fixture.budget.resumeAfterReconciliation();
        assertEquals("risk debt", controller.waitReason());

        controller.onLeft();
        assertEquals("waiting for connection", controller.waitReason());
    }

    @Test
    void metricsWireTickTimeSuspensionsAndRiskDebt() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());

        controller.tick(10);
        controller.tick(10);
        controller.tick(9);
        CombatMetrics metrics = controller.metrics();
        assertEquals(1, metrics.percentiles(CombatMetrics.COMBAT_TICK_MS).samples(),
            "duplicate and backwards ticks are ignored");
        assertTrue(metrics.percentiles(CombatMetrics.COMBAT_TICK_MS).p50() >= 0.0);
        assertEquals(1, metrics.riskDebtSamples());

        fixture.budget.suspend(SuspensionCause.SETBACK);
        controller.tick(11);
        assertEquals(1, metrics.suspensions(SuspensionCause.SETBACK));
        controller.tick(12);
        assertEquals(1, metrics.suspensions(SuspensionCause.SETBACK),
            "one suspension episode counts once");

        assertTrue(metrics.meanRiskDebt() >= 0.0);
        assertTrue(metrics.retirementsByRule().isEmpty(), "retirement rules stay uninstrumented until P3");
        assertEquals(0, metrics.totalDefenceActions());
        assertEquals(0.0, metrics.estimatedTargetDamage());
        assertEquals(0L, metrics.attackAttempts());
    }

    @Test
    void tickFeedsObservationsToThePassiveLedger() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        HazardId hazard = fixture.crystals.reserveSpawn(fixture.ticket.conn(),
            Box.fromCell(CombatRuntimeFixture.CELL), Box.fromCell(CombatRuntimeFixture.CELL), Set.of());
        fixture.crystals.markSent(hazard, 0);
        assertNull(fixture.crystals.state(fixture.crystal()), "nothing is observed before the first tick");
        assertFalse(fixture.crystals.protects(fixture.crystalUuid));
        assertTrue(controller.enable());

        fixture.intake.enqueueForTest(spawn(fixture, 1L, 2));
        controller.tick(1);

        assertEquals(CrystalState.OBSERVED, fixture.crystals.state(fixture.crystal()),
            "the runtime drains observations into the ledger before the budget applies them");
        assertTrue(fixture.crystals.unresolved(hazard));
        assertTrue(fixture.crystals.protects(fixture.crystalUuid),
            "the bound hazard is visible to the gate protector after a controller tick");
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void onLeftClearsOnlyTheClosingConnectionsLedgerState() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        HazardId closing = fixture.crystals.reserveSpawn(fixture.ticket.conn(),
            Box.fromCell(CombatRuntimeFixture.CELL), Box.fromCell(CombatRuntimeFixture.CELL), Set.of());
        fixture.crystals.markSent(closing, 0);
        ConnectionKey other = new ConnectionKey(9_999L);
        HazardId unrelated = fixture.crystals.reserveSpawn(other,
            Box.fromCell(new Cell(8, 64, 8)), Box.fromCell(new Cell(8, 64, 8)), Set.of());
        fixture.crystals.markSent(unrelated, 0);

        assertTrue(controller.enable());
        fixture.intake.enqueueForTest(spawn(fixture, 1L, 2));
        controller.tick(1);
        assertTrue(fixture.crystals.protects(fixture.crystalUuid));

        controller.onLeft();

        assertFalse(fixture.crystals.unresolved(closing),
            "the closing connection's hazards are cleared");
        assertNull(fixture.crystals.state(fixture.crystal()));
        assertFalse(fixture.crystals.protects(fixture.crystalUuid));
        assertTrue(fixture.crystals.unresolved(unrelated),
            "ledger state on another connection is untouched");
        assertFalse(fixture.session.isOpen());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void dimensionChangeClosesTheOldLedgerEpochAfterTheTick() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        HazardId closing = fixture.crystals.reserveSpawn(fixture.ticket.conn(),
            Box.fromCell(CombatRuntimeFixture.CELL), Box.fromCell(CombatRuntimeFixture.CELL), Set.of());
        fixture.crystals.markSent(closing, 0);
        ConnectionKey other = new ConnectionKey(9_999L);
        HazardId unrelated = fixture.crystals.reserveSpawn(other,
            Box.fromCell(new Cell(8, 64, 8)), Box.fromCell(new Cell(8, 64, 8)), Set.of());
        fixture.crystals.markSent(unrelated, 0);
        ConnectionKey old = fixture.ticket.conn();

        assertTrue(controller.enable());
        fixture.intake.enqueueForTest(spawn(fixture, 1L, 2));
        controller.tick(1);
        assertTrue(fixture.crystals.protects(fixture.crystalUuid));

        // The first respawn only establishes the dimension; the second is the actual dimension change.
        respawn(fixture, "minecraft:overworld");
        respawn(fixture, "minecraft:the_nether");
        controller.tick(2);

        assertNotEquals(old, fixture.session.connection(), "a dimension change starts a new epoch");
        assertFalse(fixture.crystals.unresolved(closing),
            "the old epoch's ledger state is closed after the tick that observed the change");
        assertFalse(fixture.crystals.protects(fixture.crystalUuid));
        assertTrue(fixture.crystals.unresolved(unrelated));
        assertEquals(0, fixture.sink.attempts);
    }

    /**
     * The Meteor after-application host feeds spawns directly, without a packet receipt: a sent placement
     * binds to the crystal that actually appeared and stays protected until removal.
     */
    @Test
    void hostSpawnObservationBindsASentHazardWithoutTheIntake() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        HazardId hazard = fixture.crystals.reserveSpawn(fixture.ticket.conn(),
            Box.fromCell(CombatRuntimeFixture.CELL), Box.fromCell(CombatRuntimeFixture.CELL), Set.of());
        fixture.crystals.markSent(hazard, 0);
        assertTrue(controller.enable());
        controller.tick(5);

        controller.observeSpawn(fixture.crystal(), CombatRuntimeFixture.CRYSTAL_POSITION,
            "minecraft:end_crystal");

        assertEquals(CrystalState.OBSERVED, fixture.crystals.state(fixture.crystal()),
            "a Meteor after-application spawn must reach the passive ledger");
        assertEquals(CrystalState.OBSERVED, fixture.crystals.stateOf(hazard));
        assertTrue(fixture.crystals.unresolved(hazard));
        assertTrue(fixture.crystals.protects(fixture.crystalUuid),
            "the sent placement is bound to the crystal the server spawned");
        assertTrue(CrystalCombatGate.protects(fixture.crystalUuid));
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void hostRemovalObservationIsAuthoritativeAndStopsProtection() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        HazardId hazard = fixture.crystals.reserveSpawn(fixture.ticket.conn(),
            Box.fromCell(CombatRuntimeFixture.CELL), Box.fromCell(CombatRuntimeFixture.CELL), Set.of());
        fixture.crystals.markSent(hazard, 0);
        assertTrue(controller.enable());
        controller.observeSpawn(fixture.crystal(), CombatRuntimeFixture.CRYSTAL_POSITION,
            "minecraft:end_crystal");
        assertTrue(fixture.crystals.protects(fixture.crystalUuid));

        controller.observeRemoval(fixture.crystal());

        assertEquals(CrystalState.REMOVED, fixture.crystals.state(fixture.crystal()),
            "a real removal is terminal evidence, not a tracking loss");
        assertFalse(fixture.crystals.unresolved(hazard));
        assertFalse(fixture.crystals.protects(fixture.crystalUuid));
        assertFalse(CrystalCombatGate.protects(fixture.crystalUuid));
        assertEquals(0, fixture.sink.attempts);
    }

    /** The host host is always subscribed, so the ledger keeps observing passively after disable. */
    @Test
    void hostObservationsStillFeedThePassiveLedgerWhileDisabled() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());
        controller.disable();
        assertFalse(CrystalCombatGate.suppressMeteor());

        controller.observeSpawn(fixture.crystal(), CombatRuntimeFixture.CRYSTAL_POSITION,
            "minecraft:end_crystal");

        assertEquals(CrystalState.OBSERVED, fixture.crystals.state(fixture.crystal()),
            "disable stops actions, not passive evidence");
        assertEquals(0, fixture.sink.attempts);
    }

    /**
     * Host receipts start far above the intake's counter, so the ledger's (connection, receiptSeq) dedupe key
     * can never drop one source as a duplicate of the other.
     */
    @Test
    void hostReceiptSequenceDoesNotCollideWithIntakeReceipts() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        EntityKey other = new EntityKey(fixture.ticket.conn(), 77, UUID.randomUUID());
        assertTrue(controller.enable());
        fixture.intake.enqueueForTest(spawn(fixture, 1L, 2));

        controller.observeSpawn(other, new Vec(4.5, 65.5, 4.5), "minecraft:end_crystal");
        controller.tick(2);

        assertEquals(CrystalState.OBSERVED, fixture.crystals.state(fixture.crystal()),
            "the intake observation is still applied");
        assertEquals(CrystalState.OBSERVED, fixture.crystals.state(other),
            "the host observation is applied even though both share connection 1");
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void hostObservationsAreIgnoredWithoutALiveSessionOrForAnotherConnection() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        controller.observeSpawn(fixture.crystal(), CombatRuntimeFixture.CRYSTAL_POSITION,
            "minecraft:end_crystal");
        assertNull(fixture.crystals.state(fixture.crystal()), "no stack means no ledger writes");

        assertTrue(controller.enable());
        EntityKey foreign = new EntityKey(new ConnectionKey(424_242L), 99, UUID.randomUUID());
        controller.observeSpawn(foreign, new Vec(9.5, 64.5, 9.5), "minecraft:end_crystal");
        assertNull(fixture.crystals.state(foreign), "another connection's epoch is never written");

        controller.onLeft();
        controller.observeSpawn(fixture.crystal(), CombatRuntimeFixture.CRYSTAL_POSITION,
            "minecraft:end_crystal");
        assertNull(fixture.crystals.state(fixture.crystal()), "a closed session accepts no observations");
    }

    /**
     * B1: a RESPAWN that arrives through {@code runtime.tick} must reconcile the ledger and clear the respawn
     * suspension without ever sending. The controller installs the hook; this test wraps it to count calls so
     * two implementations (direct + observation) cannot hide behind one observable effect.
     */
    @Test
    void observedRespawnReconcilesExactlyOnceThroughTheRuntimeHook() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        HazardId stale = fixture.crystals.reserveSpawn(fixture.ticket.conn(),
            Box.fromCell(CombatRuntimeFixture.CELL), Box.fromCell(CombatRuntimeFixture.CELL), Set.of());
        fixture.crystals.markSent(stale, 0);
        assertTrue(controller.enable());
        Intent attack = fixture.attack();
        RiskId riskId = fixture.reserve(attack);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(attack, fixture.ticket).status());
        assertEquals(1, fixture.budget.outstanding());

        int sentAttempts = fixture.sink.attempts;
        AtomicInteger reconciles = new AtomicInteger();
        Runnable installed = fixture.runtime.respawnReconciler();
        fixture.runtime.setRespawnReconciler(() -> {
            reconciles.incrementAndGet();
            installed.run();
        });

        respawn(fixture, "minecraft:overworld");
        controller.tick(1);

        assertEquals(1, reconciles.get(), "one applied RESPAWN observation reconciles exactly once");
        assertEquals(CrystalState.OBSERVED, fixture.crystals.state(fixture.crystal()),
            "the stale hazard is rebound against a fresh applied snapshot");
        assertTrue(fixture.crystals.unresolved(stale), "respawn keeps unresolved hazard evidence");
        assertTrue(fixture.crystals.protects(fixture.crystalUuid));
        assertFalse(fixture.budget.suspended(), "the respawn suspension clears only after reconciliation");
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(riskId));
        assertEquals(0.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);
        assertEquals(sentAttempts, fixture.sink.attempts, "respawn reconciliation never sends");
    }

    /** B1: the direct lifecycle call shares that one implementation, so it also reconciles exactly once. */
    @Test
    void directRespawnReconcilesExactlyOnce() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());
        AtomicInteger reconciles = new AtomicInteger();
        Runnable installed = fixture.runtime.respawnReconciler();
        fixture.runtime.setRespawnReconciler(() -> {
            reconciles.incrementAndGet();
            installed.run();
        });

        controller.onRespawn();

        assertEquals(1, reconciles.get(), "direct and observed respawn use one implementation");
        assertTrue(fixture.session.isOpen());
        assertEquals(0, fixture.sink.attempts);
    }

    /**
     * B2: disable stops actions, not observation. A sent risk must still retire from the causally later
     * explosion+health observations applied by later ticks, with the visible status staying "disabled".
     */
    @Test
    void disableKeepsApplyingObservationsSoSentRiskCanRetire() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = controller(fixture);
        assertTrue(controller.enable());
        Intent attack = fixture.attack();
        RiskId riskId = fixture.reserve(attack);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(attack, fixture.ticket).status());
        assertEquals(1, fixture.budget.outstanding());

        controller.disable();
        assertEquals("disabled", controller.infoString());
        int attemptsAfterDisable = fixture.sink.attempts;

        explode(fixture, 6.0f);
        health(fixture, 20.0f);
        controller.tick(1);

        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(riskId),
            "a disabled controller still applies the observations that retire sent risk");
        assertEquals(0, fixture.budget.outstanding());
        assertEquals(attemptsAfterDisable, fixture.sink.attempts, "passive observation never sends");
        assertEquals("disabled", controller.infoString(),
            "passive ticks must not overwrite the disabled status with an active wait reason");
    }

    private static CrystalCombatController controller(CombatRuntimeFixture fixture) {
        return new CrystalCombatController(() -> null, fixture::controllerStack);
    }

    private static Observation spawn(CombatRuntimeFixture fixture, long receipt, int tick) {
        return new Observation(fixture.ticket.conn(), receipt, 1L, 1_000L, tick,
            ObservationKind.SPAWN, fixture.crystal(), null, CombatRuntimeFixture.CRYSTAL_POSITION, null,
            new ObservationPayload.Spawn("minecraft:end_crystal"));
    }

    /** A sent placement whose crystal spawned and bound, so the hazard is ledger-protected. */
    private static HazardId sentHazard(CombatRuntimeFixture fixture) {
        HazardId hazard = fixture.crystals.reserveSpawn(fixture.ticket.conn(),
            Box.fromCell(CombatRuntimeFixture.CELL), Box.fromCell(CombatRuntimeFixture.CELL), Set.of());
        fixture.crystals.markSent(hazard, 0);
        fixture.crystals.observe(spawn(fixture, 1L, 2));
        assertTrue(fixture.crystals.unresolved(hazard));
        return hazard;
    }

    /** Applies one raw respawn receipt on the client thread, as the observation hook would. */
    private static void respawn(CombatRuntimeFixture fixture, String dimension) {
        ObservationIntake.Receipt receipt =
            fixture.intake.record(1L, -1, 0, new ObservationIntake.RawRespawn(dimension));
        fixture.intake.apply(receipt, null);
    }

    /** Applies one raw explosion receipt at the crystal position. */
    private static void explode(CombatRuntimeFixture fixture, float radius) {
        ObservationIntake.Receipt receipt = fixture.intake.record(1L, -1, 0,
            new ObservationIntake.RawExplosion(CombatRuntimeFixture.CRYSTAL_POSITION.x(),
                CombatRuntimeFixture.CRYSTAL_POSITION.y(), CombatRuntimeFixture.CRYSTAL_POSITION.z(),
                radius));
        fixture.intake.apply(receipt, null);
    }

    /** Applies one coherent self-health receipt for the fixture player. */
    private static void health(CombatRuntimeFixture fixture, float health) {
        ObservationIntake.Receipt receipt = fixture.intake.record(1L, -1, 0,
            new ObservationIntake.RawEntityData(1,
                List.of(new SynchedEntityData.DataValue<>(9, EntityDataSerializers.FLOAT, health),
                    new SynchedEntityData.DataValue<>(10, EntityDataSerializers.FLOAT, 0.0f))));
        fixture.intake.apply(receipt, null);
    }
}
