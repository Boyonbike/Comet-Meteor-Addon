/*
 * Original Comet implementation by Boyonabike.
 * Independent: test doubles written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CleanupPriority;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Mode;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.Ownership;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.PistonPlanner;
import dev.tntcoating.core.combat.PistonGeometry;
import dev.tntcoating.core.combat.PistonLayoutSelection;
import dev.tntcoating.core.combat.PistonSequence;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.TickBudget;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.CombatHurtModel;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Runtime integration tests for the Phase 5.1 piston trap controller. */
final class PistonTrapControllerTest {
    private static final Cell SOURCE = new Cell(-1, -60, 0);
    private static final Cell PISTON = new Cell(-2, -60, 0);
    private static final Cell POWER = new Cell(-3, -60, 0);
    private static final UUID TARGET_UUID = UUID.nameUUIDFromBytes("piston-target".getBytes());
    private static final UUID SPAWN_UUID = UUID.nameUUIDFromBytes("piston-spawn".getBytes());
    private static final UUID OTHER_UUID = UUID.nameUUIDFromBytes("piston-other".getBytes());
    private static final FakeSink.Kind BREAK = FakeSink.Kind.BREAK;
    private static final FakeSink.Kind STOP = FakeSink.Kind.STOP;

    // ------------------------------------------------------------------ tests

    @Test
    void structuralOrderAndZeroBoundRisk() {
        Harness harness = new Harness();
        harness.tick(1);
        assertEquals(PistonSequence.PistonPhase.SUPPORTS, harness.controller.phase());
        harness.tick(2);
        harness.tick(3);
        assertTrue(harness.controller.awaitingBlockConfirmation());
        assertEquals(PistonSequence.PistonPhase.PISTON, harness.controller.phase());
        harness.confirmPiston();
        assertEquals(PistonSequence.PistonPhase.CRYSTAL, harness.controller.phase());
        PistonSequence sequence = harness.controller.activeTrap().orElseThrow();
        PistonPlanner.Plan plan = sequence.plan();

        assertEquals(List.of(PISTON, SOURCE, POWER), plan.intents().stream().map(intent -> intent.cell()).toList());
        assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.PISTON));
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE), "crystal waits for the structural stage");
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.POWER), "power waits for the crystal stage");

        RiskId structural = harness.fixture.scheduler.riskId(plan.planId(), plan.pistonIntent().orElseThrow().id())
            .orElseThrow();
        assertEquals(0.0, harness.fixture.budget.debt(harness.fixture.self()).bound());
        assertTrue(harness.fixture.budget.boundCrystal(structural).isEmpty());
    }

    @Test
    void crystalRiskReservedAtCrystalAndBoundAfterUniqueSpawn() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.tick(2);
        harness.tick(3);
        harness.confirmPiston();
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));

        harness.tick(4);
        assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.PLACE));
        RiskId risk = harness.fixture.scheduler
            .riskId(harness.plan().planId(), harness.plan().crystalIntent().orElseThrow().id()).orElseThrow();
        assertTrue(harness.fixture.budget.boundCrystal(risk).isEmpty(), "no spawn observation yet");
        assertEquals(CombatRiskBudget.State.SENT, harness.fixture.budget.state(risk));

        UUID uuid = SPAWN_UUID;
        EntityKey spawn = new EntityKey(harness.fixture.ticket.conn(), 42, uuid);
        harness.controller.onCrystalSpawn(spawn, "minecraft:end_crystal", uuid, SOURCE);
        harness.world.crystals.add(spawn);
        harness.world.positions.put(spawn, SOURCE.center());
        harness.world.uuids.put(spawn, SPAWN_UUID);
        harness.tick(5);
        assertEquals(Optional.of(spawn), harness.fixture.budget.boundCrystal(risk));
        assertEquals(PistonSequence.PistonPhase.POWER, harness.controller.phase());
        assertFalse(harness.controller.spawnRejected());
    }

    @Test
    void observationCallbacksNeverSend() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.tick(2);
        harness.tick(3);
        harness.tick(4);
        int before = harness.fixture.sink.attempts;
        harness.controller.onCrystalSpawn(new EntityKey(harness.fixture.ticket.conn(), 42, SPAWN_UUID),
            "minecraft:end_crystal", SPAWN_UUID, SOURCE);
        harness.controller.onPistonUpdate(PISTON);
        harness.controller.onBlockUpdate(POWER);
        harness.controller.onTargetChanged(harness.target);
        harness.controller.onCrystalRemoved(new EntityKey(harness.fixture.ticket.conn(), 42, SPAWN_UUID));
        assertEquals(before, harness.fixture.sink.attempts);
    }

    @Test
    void spawnPresentInLiveWorldAtBindStillBindsUniquely() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.tick(2);
        harness.tick(3);
        harness.confirmPiston();
        harness.tick(4); // dispatchCrystal snapshots the empty pre-send baseline
        EntityKey spawn = new EntityKey(harness.fixture.ticket.conn(), 42, SPAWN_UUID);
        harness.controller.onCrystalSpawn(spawn, "minecraft:end_crystal", SPAWN_UUID, SOURCE);
        // Production reads mc.level: by bind time the crystal we placed is already in the live world.
        harness.world.crystals.add(spawn);
        harness.world.positions.put(spawn, SOURCE.center());
        harness.world.uuids.put(spawn, SPAWN_UUID);
        harness.tick(5);
        assertEquals(Optional.of(spawn), harness.sequence().bound().map(PistonSequence.Spawn::entity));
        assertFalse(harness.controller.spawnRejected());
        assertEquals(PistonSequence.PistonPhase.POWER, harness.controller.phase());
    }

    @Test
    void preexistingForeignCrystalIsFilteredButOurSpawnStillBinds() {
        Harness harness = new Harness();
        EntityKey foreign = new EntityKey(harness.fixture.ticket.conn(), 41, OTHER_UUID);
        harness.controller.onCrystalSpawn(foreign, "minecraft:end_crystal", OTHER_UUID,
            new Cell(4, -59, 4));
        harness.world.crystals.add(foreign);
        harness.world.positions.put(foreign, new Vec(4.5, -59.5, 4.5));
        harness.world.uuids.put(foreign, OTHER_UUID);
        harness.tick(1);
        harness.tick(2);
        harness.tick(3);
        harness.confirmPiston();
        harness.tick(4); // the pre-existing crystal is part of the captured pre-send baseline
        EntityKey spawn = new EntityKey(harness.fixture.ticket.conn(), 42, SPAWN_UUID);
        harness.controller.onCrystalSpawn(spawn, "minecraft:end_crystal", SPAWN_UUID, SOURCE);
        harness.world.crystals.add(spawn);
        harness.world.positions.put(spawn, SOURCE.center());
        harness.world.uuids.put(spawn, SPAWN_UUID);
        harness.tick(5);
        assertEquals(Optional.of(spawn), harness.sequence().bound().map(PistonSequence.Spawn::entity));
        assertEquals(SPAWN_UUID, harness.sequence().bound()
            .map(PistonSequence.Spawn::uuid).orElseThrow());
        assertFalse(harness.controller.spawnRejected());
    }

    @Test
    void secondForeignLateSpawnIsNeverBound() {
        Harness ambiguous = new Harness();
        ambiguous.tick(1);
        ambiguous.tick(2);
        ambiguous.tick(3);
        ambiguous.confirmPiston();
        ambiguous.tick(4);
        ambiguous.controller.onCrystalSpawn(
            new EntityKey(ambiguous.fixture.ticket.conn(), 42, SPAWN_UUID),
            "minecraft:end_crystal", SPAWN_UUID, SOURCE);
        ambiguous.controller.onCrystalSpawn(
            new EntityKey(ambiguous.fixture.ticket.conn(), 43, OTHER_UUID),
            "minecraft:end_crystal", OTHER_UUID, SOURCE);
        ambiguous.tick(5);
        assertTrue(ambiguous.controller.spawnRejected());
        assertTrue(ambiguous.controller.manualCleanupRequired());
        assertTrue(ambiguous.sequence().bound().isEmpty());
        assertEquals(0, ambiguous.fixture.sink.count(FakeSink.Kind.POWER));
    }

    @Test
    void computedPushIsUsedWithoutCrystalMoveObservation() {
        Harness harness = new Harness();
        harness.advanceToWaitPush();
        Vec expected = harness.plan().expectedPushed();
        Vec sourceCentre = SOURCE.center();
        assertEquals(1.02, expected.distance(sourceCentre), 1.0e-9);
        assertEquals(new Vec(sourceCentre.x() + 1.02, sourceCentre.y(), sourceCentre.z()), expected);

        harness.world.put(PISTON, "minecraft:piston");
        harness.world.facings.put(PISTON, PistonGeometry.EAST);
        harness.world.extended.add(PISTON);
        harness.world.powered.add(PISTON);
        harness.tick(7);
        harness.tick(8);
        harness.tick(9);
        assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
        assertNotNull(harness.fixture.sink.last(FakeSink.Kind.ATTACK));
        assertEquals(new EntityKey(harness.fixture.ticket.conn(), 42, SPAWN_UUID),
            harness.sequence().bound().orElseThrow().entity());
        // The target never moved; the attack used the computed push position, not a crystal-move observation.
        assertEquals(new Vec(0.5, -60.0, 0.5), harness.world.position(harness.target));
    }

    @Test
    void damageFailureAtComputedPositionAbortsWithoutAttack() {
        Harness harness = new Harness();
        harness.advanceToWaitPush();
        harness.world.put(PISTON, "minecraft:piston");
        harness.world.facings.put(PISTON, PistonGeometry.EAST);
        harness.world.extended.add(PISTON);
        harness.world.powered.add(PISTON);
        harness.world.minimumDamage = 11.0; // planner priced the swept path below this
        harness.tick(7);
        harness.tick(8);
        harness.tick(9);
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
        assertEquals(PistonSequence.PistonPhase.CLEANUP, harness.controller.phase());
        assertFalse(harness.sequence().flags().attackSafe());
        assertEquals("damage-too-low", harness.controller.lastFailure());
        assertTrue(harness.controller.riskRetained());
    }

    @Test
    void attackWaitsForPistonProgress() {
        Harness harness = new Harness();
        harness.advanceToWaitPush();
        // The piston block exists, but neither powered nor extended yet: no progress evidence.
        harness.world.put(PISTON, "minecraft:piston");
        harness.world.facings.put(PISTON, PistonGeometry.EAST);
        harness.tick(7);
        harness.tick(8);
        assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, harness.controller.phase());
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.ATTACK), "no progress observed yet");

        harness.world.powered.add(PISTON);
        harness.world.extended.add(PISTON);
        harness.tick(9);
        harness.tick(10);
        assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
    }

    @Test
    void wrongFacingAbortsBeforePowerAndKeepsRisk() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.world.facings.put(PISTON, PistonGeometry.WEST);
        harness.tick(2);
        harness.tick(3);
        harness.tick(4);
        assertTrue(harness.controller.wrongFacingObserved());
        assertTrue(harness.controller.phase() == PistonSequence.PistonPhase.ABORTED
            || harness.controller.manualCleanupRequired());
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.POWER));
        assertTrue(harness.controller.riskRetained());
    }

    @Test
    void earlyPowerAbortsBeforeCrystal() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.world.powered.add(PISTON);
        harness.tick(2);
        harness.tick(3);
        harness.tick(4);
        assertTrue(harness.controller.earlyPowerObserved());
        assertTrue(harness.controller.phase() == PistonSequence.PistonPhase.ABORTED
            || harness.controller.manualCleanupRequired());
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));
        assertTrue(harness.controller.riskRetained());
    }

    @Test
    void targetLossBeforeAndAfterPowerAbortsWithoutAttack() {
        Harness before = new Harness();
        before.tick(1);
        before.tick(2);
        before.tick(3);
        before.world.positions.put(before.target, new Vec(8.5, -55.0, 8.5));
        before.tick(4);
        assertTrue(before.controller.phase() == PistonSequence.PistonPhase.ABORTED
            || before.controller.manualCleanupRequired());
        assertEquals(0, before.fixture.sink.count(FakeSink.Kind.ATTACK));

        Harness after = new Harness();
        after.advanceToWaitPush();
        after.world.positions.put(after.target, new Vec(8.5, -55.0, 8.5));
        after.tick(7);
        assertTrue(after.controller.phase() == PistonSequence.PistonPhase.ABORTED
            || after.controller.manualCleanupRequired());
        assertEquals(0, after.fixture.sink.count(FakeSink.Kind.ATTACK));
        assertTrue(after.controller.riskRetained());
    }

    @Test
    void verticalPushOutOfTheHoleDuringWaitPushAbortsWithoutAttack() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            int attempts = harness.fixture.sink.attempts;
            // An explosion near the target destroys a block and lifts the target one block out of its hole.
            // Our piston has not pushed it, and the crystal was placed for the old cell, so the planned
            // attack is no longer verified.
            harness.controller.onBlockUpdate(new Cell(3, -60, 0));
            harness.world.positions.put(harness.target, new Vec(0.5, -59.0, 0.5));
            harness.tick(7);

            assertEquals(PistonSequence.PistonPhase.ABORTED, harness.sequence().phase());
            assertEquals("target-pushed-vertically", harness.controller.lastFailure());
            assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
            assertEquals(attempts, harness.fixture.sink.attempts, "the abort sends nothing");
            assertTrue(harness.controller.riskRetained());
            assertTrue(harness.controller.manualCleanupRequired());
            assertTrue(harness.debug.stream().anyMatch(line ->
                    line.contains("external-disturbance") && line.contains("cell=Cell[x=3, y=-60, z=0]")),
                harness.debug.toString());
            assertTrue(harness.debug.stream().anyMatch(line ->
                    line.contains("abort stage=WAIT_PUSH reason=target-pushed-vertically")),
                harness.debug.toString());
            assertTrue(harness.debug.stream().anyMatch(line ->
                    line.contains("manual-cleanup-blocks")
                        && line.contains("reason=target-pushed-vertically")
                        && line.contains("power (-3, -60, 0)")
                        && line.contains("piston (-2, -60, 0)")
                        && line.contains("crystal (-1, -60, 0)")),
                harness.debug.toString());
            assertTrue(harness.incidents.stream().anyMatch(line ->
                    line.contains("trap-aborted-after-power")
                        && line.contains("reason=target-pushed-vertically")),
                harness.incidents.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void normalJumpInTheHoleDoesNotAbortTheTrap() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            // A normal jump: at most 0.4 blocks of rise per tick, then the fall back to the hole floor.
            // There is no external block disturbance, so the trap must keep waiting for the piston.
            double[] heights = {-59.6, -59.2, -58.8, -58.5, -58.9, -59.4, -60.0};
            int tick = 7;
            for (double y : heights) {
                harness.world.positions.put(harness.target, new Vec(0.5, y, 0.5));
                harness.tick(tick++);
                assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, harness.controller.phase(),
                    "a jump is not an explosion push");
            }
            assertEquals("", harness.controller.lastFailure());
            assertTrue(harness.debug.stream().noneMatch(line -> line.contains("external-disturbance")),
                harness.debug.toString());

            harness.world.powered.add(PISTON);
            harness.world.extended.add(PISTON);
            harness.tick(tick++);
            harness.tick(tick);
            assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.ATTACK),
                "the trap still attacks after a normal jump");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void jumpWithDistantBlockUpdateDoesNotAbort() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            harness.world.positions.put(harness.target, new Vec(0.5, -59.6, 0.5));
            harness.tick(7);
            harness.world.positions.put(harness.target, new Vec(0.5, -59.2, 0.5));
            harness.tick(8);
            // The block change is outside the disturbance radius of the target: it cannot be the blast
            // that lifted it, so even a sharp rise is not vertical-push evidence.
            harness.controller.onBlockUpdate(new Cell(5, -60, 5));
            harness.world.positions.put(harness.target, new Vec(0.5, -57.8, 0.5));
            harness.tick(9);

            assertNotEquals(PistonSequence.PistonPhase.ABORTED, harness.controller.phase());
            assertEquals("", harness.controller.lastFailure());
            assertTrue(harness.debug.stream().noneMatch(line -> line.contains("external-disturbance")),
                harness.debug.toString());

            harness.world.positions.put(harness.target, new Vec(0.5, -60.0, 0.5));
            harness.tick(10);
            harness.world.powered.add(PISTON);
            harness.world.extended.add(PISTON);
            harness.tick(11);
            harness.tick(12);
            assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void verticalPushInWaitPushAbortsOnExternalDisturbance() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            int attempts = harness.fixture.sink.attempts;
            harness.controller.onBlockUpdate(new Cell(3, -60, 0));
            harness.world.positions.put(harness.target, new Vec(0.5, -58.9, 0.5));
            harness.tick(7);

            assertEquals(PistonSequence.PistonPhase.ABORTED, harness.sequence().phase());
            assertEquals("target-pushed-vertically", harness.controller.lastFailure());
            assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
            assertEquals(attempts, harness.fixture.sink.attempts, "the abort sends nothing");
            assertTrue(harness.controller.riskRetained());
            assertTrue(harness.controller.manualCleanupRequired());
            assertTrue(harness.debug.stream().anyMatch(line -> line.contains("external-disturbance")),
                harness.debug.toString());
            assertTrue(harness.debug.stream().anyMatch(line ->
                    line.contains("abort stage=WAIT_PUSH reason=target-pushed-vertically")),
                harness.debug.toString());
            assertTrue(harness.incidents.stream().anyMatch(line ->
                    line.contains("trap-aborted-after-power")
                        && line.contains("reason=target-pushed-vertically")),
                harness.incidents.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void verticalPushInPowerAbortsBeforeTheAttack() {
        Harness harness = new Harness();
        try {
            harness.advanceToPower();
            int attempts = harness.fixture.sink.attempts;
            harness.controller.onBlockUpdate(new Cell(3, -60, 0));
            harness.world.positions.put(harness.target, new Vec(0.5, -58.9, 0.5));
            harness.tick(7);
            assertEquals(PistonSequence.PistonPhase.ABORTED, harness.sequence().phase());
            assertEquals("target-pushed-vertically", harness.controller.lastFailure());
            assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
            assertEquals(attempts, harness.fixture.sink.attempts, "the abort sends nothing");
            assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.POWER));
            assertTrue(harness.controller.riskRetained());
            assertTrue(harness.controller.manualCleanupRequired());
            assertTrue(harness.debug.stream().anyMatch(line ->
                    line.contains("abort stage=POWER reason=target-pushed-vertically")),
                harness.debug.toString());
            assertTrue(harness.incidents.stream().anyMatch(line ->
                    line.contains("trap-aborted-after-power")
                        && line.contains("stage=POWER")),
                harness.incidents.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void explosionLiftInWaitPushAbortsWithoutABlockUpdate() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            int attempts = harness.fixture.sink.attempts;
            // Deliver the blast through the runtime's registered passive observer: production wiring,
            // not a direct controller call. No block update accompanies the explosion.
            harness.explode(new Vec(1.5, -60.0, 0.5));
            // The same server tick lifts the target one block out of the hole.
            harness.world.positions.put(harness.target, new Vec(0.5, -59.0, 0.5));
            harness.tick(7);

            assertEquals(PistonSequence.PistonPhase.ABORTED, harness.sequence().phase());
            assertEquals("target-pushed-vertically", harness.controller.lastFailure());
            assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
            assertEquals(attempts, harness.fixture.sink.attempts, "the abort sends nothing");
            assertTrue(harness.controller.riskRetained());
            assertTrue(harness.controller.manualCleanupRequired());
            assertTrue(harness.debug.stream().anyMatch(line ->
                    line.contains("external-explosion") && line.contains("distance=1.00")),
                harness.debug.toString());
            assertTrue(harness.debug.stream().noneMatch(line -> line.contains("external-disturbance")),
                "no block update was delivered: " + harness.debug);
            assertTrue(harness.debug.stream().anyMatch(line ->
                    line.contains("abort stage=WAIT_PUSH reason=target-pushed-vertically")),
                harness.debug.toString());
            assertTrue(harness.debug.stream().anyMatch(line ->
                    line.contains("manual-cleanup-blocks")
                        && line.contains("reason=target-pushed-vertically")
                        && line.contains("power (-3, -60, 0)")
                        && line.contains("piston (-2, -60, 0)")
                        && line.contains("crystal (-1, -60, 0)")),
                harness.debug.toString());
            assertTrue(harness.incidents.stream().anyMatch(line ->
                    line.contains("trap-aborted-after-power")
                        && line.contains("reason=target-pushed-vertically")),
                harness.incidents.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void explosionFarFromTheTargetDoesNotAbort() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            int attempts = harness.fixture.sink.attempts;
            // The blast is far outside the eight-block evidence radius: it cannot have lifted the target.
            harness.explode(new Vec(12.5, -60.0, 0.5));
            harness.world.positions.put(harness.target, new Vec(0.5, -59.0, 0.5));
            harness.tick(7);

            assertNotEquals(PistonSequence.PistonPhase.ABORTED, harness.controller.phase());
            assertEquals("", harness.controller.lastFailure());
            assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
            assertEquals(attempts, harness.fixture.sink.attempts);
            assertTrue(harness.debug.stream().noneMatch(line -> line.contains("external-explosion")),
                harness.debug.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void sustainedHalfBlockRiseWithExplosionAborts() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            int attempts = harness.fixture.sink.attempts;
            harness.explode(new Vec(1.5, -60.0, 0.5));
            // A sustained lift of exactly 0.5 blocks per tick clears the strict 0.45 threshold; the old
            // strict 0.5 comparison and the missing evidence source both let it through.
            harness.world.positions.put(harness.target, new Vec(0.5, -59.5, 0.5));
            harness.tick(7);
            harness.world.positions.put(harness.target, new Vec(0.5, -59.0, 0.5));
            harness.tick(8);

            assertEquals(PistonSequence.PistonPhase.ABORTED, harness.sequence().phase());
            assertEquals("target-pushed-vertically", harness.controller.lastFailure());
            assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
            assertEquals(attempts, harness.fixture.sink.attempts);
            assertTrue(harness.controller.riskRetained());
            assertTrue(harness.debug.stream().anyMatch(line ->
                    line.contains("abort stage=WAIT_PUSH reason=target-pushed-vertically")),
                harness.debug.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void explosionDuringSupportsDoesNotAbort() {
        Harness harness = new Harness();
        try {
            // A missing support keeps the trap in SUPPORTS while its placement is pending.
            Cell support = new Cell(-1, -61, 0);
            harness.world.blocks.remove(support);
            harness.tick(1); // scan -> reserve
            harness.tick(2); // reserve -> supports, support placement pending
            assertEquals(PistonSequence.PistonPhase.SUPPORTS, harness.controller.phase());
            harness.explode(new Vec(1.5, -60.0, 0.5));
            // The phase guard ignores the blast before POWER: no evidence is armed and nothing aborts.
            assertTrue(harness.debug.stream().noneMatch(line -> line.contains("external-explosion")),
                harness.debug.toString());
            assertEquals("", harness.controller.lastFailure());
            assertNotEquals(PistonSequence.PistonPhase.ABORTED, harness.controller.phase());

            // A pre-POWER lift is the pre-existing target-left-envelope rule, never vertical-push
            // evidence: the ignored explosion must not have armed a false abort.
            harness.world.positions.put(harness.target, new Vec(0.5, -59.0, 0.5));
            harness.tick(3);
            assertEquals(PistonSequence.PistonPhase.ABORTED, harness.sequence().phase());
            assertTrue(harness.debug.stream().noneMatch(line ->
                    line.contains("target-pushed-vertically")), harness.debug.toString());
            assertTrue(harness.debug.stream().noneMatch(line -> line.contains("external-explosion")),
                harness.debug.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void ourOwnPlacementsAndPistonExtensionDoNotCountAsDisturbance() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPushWithSupport();
            // The piston extension arrives through the same block-observation callback as an external
            // change; it is a plan cell, so it must never be recorded as explosion evidence.
            harness.controller.onBlockUpdate(harness.plan().push().extension());
            // The support, piston and power were all confirmed through onBlockUpdate; none may be
            // recorded as an external disturbance even though the target stays put.
            harness.world.powered.add(PISTON);
            harness.world.extended.add(PISTON);
            harness.tick(7);
            harness.tick(8);
            harness.tick(9);

            assertNotEquals(PistonSequence.PistonPhase.ABORTED, harness.controller.phase());
            assertEquals("", harness.controller.lastFailure());
            assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
            assertTrue(harness.debug.stream().noneMatch(line -> line.contains("external-disturbance")),
                harness.debug.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void manualCleanupInfoStringListsSupportsWithTheirCoordinates() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPushWithSupport();
            harness.controller.onBlockUpdate(new Cell(3, -60, 0));
            harness.world.positions.put(harness.target, new Vec(0.5, -58.9, 0.5));
            harness.tick(7);
            assertEquals(PistonSequence.PistonPhase.ABORTED, harness.controller.phase());

            String text = harness.controller.manualCleanupText();
            assertTrue(text.contains("supports [(-1, -61, 0)]"), text);
            assertTrue(text.contains("power (-3, -60, 0)"), text);
            assertTrue(text.contains("piston (-2, -60, 0)"), text);
            assertTrue(text.contains("crystal (-1, -60, 0)"), text);
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void manualCleanupCrystalMovesToThePushedCellAndDisappearsAfterTheAttack() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            assertTrue(harness.controller.manualCleanupText().contains("crystal (-1, -60, 0)"),
                "before the push the crystal is still at the placement cell");

            harness.world.powered.add(PISTON);
            harness.world.extended.add(PISTON);
            harness.tick(7);
            assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, harness.controller.phase());
            String pushed = harness.controller.manualCleanupText();
            assertTrue(pushed.contains("crystal (0, -60, 0)"), pushed);
            assertFalse(pushed.contains("crystal (-1, -60, 0)"), pushed);

            harness.tick(8);
            harness.tick(9);
            assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
            String attacked = harness.controller.manualCleanupText();
            assertFalse(attacked.contains("crystal"), attacked);
            assertTrue(attacked.contains("power (-3, -60, 0)"), attacked);
            assertTrue(attacked.contains("piston (-2, -60, 0)"), attacked);
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void manualCleanupOmitsTheCrystalAfterRemovalObservation() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            EntityKey spawn = new EntityKey(harness.fixture.ticket.conn(), 42, SPAWN_UUID);
            harness.controller.onCrystalRemoved(spawn);
            assertFalse(harness.controller.manualCleanupText().contains("crystal"),
                harness.controller.manualCleanupText());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void downwardPushWithNearbyDisturbanceDoesNotAbort() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            harness.controller.onBlockUpdate(new Cell(3, -60, 0));
            // Explosion-style block evidence is present, but the target is pushed down
            // (holed_vertical): the detector only matches upward impulses, so the trap must still attack.
            harness.world.positions.put(harness.target, new Vec(0.5, -60.6, 0.5));
            harness.tick(7);
            assertNotEquals(PistonSequence.PistonPhase.ABORTED, harness.controller.phase());
            harness.world.powered.add(PISTON);
            harness.world.extended.add(PISTON);
            harness.tick(8);
            harness.tick(9);
            assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
            assertNotEquals("target-pushed-vertically", harness.controller.lastFailure());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void manualCleanupInfoStringListsTheBlocksTheTrapPlaced() {
        Harness harness = new Harness();
        CrystalCombatController module = new CrystalCombatController(() -> null, harness::stack);
        try {
            module.setMode(Mode.PISTON_ONLY);
            module.setTrapEnabled(true);
            module.setPistonLayouts(PistonLayoutSelection.of(List.of(
                PistonLayoutSelection.byId("east-feet-torch").orElseThrow())));
            assertTrue(module.enable());
            harness.advanceToWaitPush();
            harness.controller.onBlockUpdate(new Cell(3, -60, 0));
            harness.world.positions.put(harness.target, new Vec(0.5, -59.0, 0.5));
            harness.tick(7);
            module.tick(8);

            String reason = module.waitReason();
            assertTrue(reason.startsWith("manual cleanup required"), reason);
            assertTrue(reason.contains("target-pushed-vertically"), reason);
            assertTrue(reason.contains("power (-3, -60, 0)"), reason);
            assertTrue(reason.contains("piston (-2, -60, 0)"), reason);
            assertTrue(reason.contains("crystal (-1, -60, 0)"), reason);
            assertFalse(reason.contains("supports ["), reason);
            assertTrue(reason.endsWith(".crystalcombat cleanup confirm"), reason);
            assertEquals(reason, module.infoString());
        } finally {
            module.disable();
        }
    }

    @Test
    void timeoutAndManualCleanupRetainSentRisk() {
        Harness harness = new Harness();
        harness.tick(1);
        assertNotNull(harness.controller.activeTrap().orElse(null));
        harness.tick(105);
        assertTrue(harness.controller.manualCleanupRequired());
        assertTrue(harness.controller.riskRetained());
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.ATTACK));
    }

    @Test
    void disableDeathAndDisconnectNeverSendAndKeepHazards() {
        for (String mode : List.of("disable", "death", "left")) {
            Harness harness = new Harness();
            harness.tick(1);
            harness.tick(2);
            harness.tick(3);
            int before = harness.fixture.sink.attempts;
            switch (mode) {
                case "disable" -> harness.controller.disable();
                case "death" -> harness.controller.death();
                default -> harness.controller.connectionClosed();
            }
            harness.tick(4);
            assertEquals(before, harness.fixture.sink.attempts, mode);
            assertTrue(harness.controller.riskRetained(), mode);
            assertTrue(harness.fixture.budget.outstanding() > 0, mode);
        }
    }

    @Test
    void failedStructuralPlacementStopsTheTrapWithoutRetryStorm() {
        Harness harness = new Harness();
        harness.fixture.sink.failOnAttempt = 2; // slot switch is attempt 1; the piston placement is attempt 2
        harness.tick(1);
        harness.tick(2); // supports settle, no packet
        harness.tick(3); // piston dispatch: the placement is refused
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.POWER));
        assertNotEquals("", harness.controller.lastFailure());
        assertTrue(harness.fixture.sink.sends.stream().anyMatch(send ->
            send.kind() == FakeSink.Kind.PISTON && !send.enqueued()));
    }

    @Test
    void oneTrapUntilReconciled() {
        Harness harness = new Harness();
        harness.tick(1);
        PistonSequence first = harness.controller.activeTrap().orElseThrow();
        harness.tick(2);
        harness.tick(3);
        assertTrue(harness.controller.activeTrap().isPresent());
        assertEquals(first, harness.controller.activeTrap().orElseThrow());
        assertFalse(harness.controller.manualCleanupRequired());
        harness.controller.reconcile();
        assertFalse(harness.controller.activeTrap().isPresent());
        harness.tick(4);
        assertNotEquals(first, harness.controller.activeTrap().orElse(null));
    }

    @Test
    void directExplosionDestroysTheTrapCrystalWithoutASecondExplosionAndKeepsRisk() {
        Harness harness = new Harness();
        harness.advanceToWaitPush();
        RiskId risk = harness.fixture.scheduler
            .riskId(harness.plan().planId(), harness.plan().crystalIntent().orElseThrow().id()).orElseThrow();
        EntityKey crystal = new EntityKey(harness.fixture.ticket.conn(), 42, SPAWN_UUID);
        assertEquals(Optional.of(crystal), harness.fixture.budget.boundCrystal(risk));
        double before = harness.fixture.budget.outstanding();
        int sendsBefore = harness.fixture.sink.attempts;

        // The audited direct-explosion hit: EndCrystal.hurtServer removes an explosion-tagged crystal
        // without a second explosion. The controller sees only the removal observation and must not
        // attack the missing entity or invent damage; the risk stays as debt until evidence retires it.
        harness.controller.onCrystalRemoved(crystal);
        harness.world.crystals.remove(crystal);
        harness.tick(7);

        assertEquals(sendsBefore, harness.fixture.sink.attempts, "no trap packet after the crystal is gone");
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.ATTACK), "no attack at a destroyed crystal");
        assertTrue(harness.controller.riskRetained());
        assertEquals(before, harness.fixture.budget.outstanding(), 1.0e-9,
            "an explosion observation is evidence of loss, not a retire signal");
        assertEquals(CombatRiskBudget.State.SENT, harness.fixture.budget.state(risk));
    }

    // ------------------------------------------------------------------ block-confirmation gate

    @Test
    void noPhaseAdvanceBeforeTheBlockObservation() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.tick(2);
        harness.tick(3);
        assertEquals(PistonSequence.PistonPhase.PISTON, harness.controller.phase());
        assertTrue(harness.controller.awaitingBlockConfirmation());
        assertEquals(Optional.of(PISTON), harness.controller.pendingCell());
        harness.tick(4);
        assertEquals(PistonSequence.PistonPhase.PISTON, harness.controller.phase(),
            "packet acceptance is not block confirmation");
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));
        harness.confirmPiston();
        assertEquals(PistonSequence.PistonPhase.CRYSTAL, harness.controller.phase());
        harness.tick(5);
        assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.PLACE));
    }

    @Test
    void staleBlockObservationDoesNotCount() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.tick(2);
        // The cell already looked like our piston before the packet: that observation cannot confirm it.
        harness.world.put(PISTON, "minecraft:piston");
        harness.world.facings.put(PISTON, PistonGeometry.EAST);
        harness.controller.onBlockUpdate(PISTON);
        harness.tick(3);
        harness.tick(4);
        assertEquals(PistonSequence.PistonPhase.PISTON, harness.controller.phase());
        assertTrue(harness.controller.awaitingBlockConfirmation());
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));
    }

    @Test
    void wrongFacingBlockObservationAborts() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.tick(2);
        harness.tick(3);
        harness.world.put(PISTON, "minecraft:piston");
        harness.world.facings.put(PISTON, PistonGeometry.WEST);
        harness.controller.onBlockUpdate(PISTON);
        assertTrue(harness.controller.wrongFacingObserved());
        assertEquals(PistonSequence.PistonPhase.ABORTED, harness.controller.phase());
        assertTrue(harness.controller.manualCleanupRequired());
        assertTrue(harness.controller.riskRetained());
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.POWER));
    }

    @Test
    void timeoutKeepsRiskAndSendsNothingFurther() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.tick(2);
        harness.tick(3);
        for (int tick = 4; tick <= 80; tick++) harness.tick(tick);
        assertTrue(harness.controller.manualCleanupRequired());
        assertTrue(harness.controller.riskRetained());
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.POWER));
        int attempts = harness.fixture.sink.attempts;
        harness.tick(81);
        assertEquals(attempts, harness.fixture.sink.attempts);
    }

    @Test
    void powerWaitsForCrystalSpawnAndPistonConfirmation() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.tick(2);
        harness.tick(3);
        harness.tick(4);
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.POWER));

        harness.confirmPiston();
        harness.tick(4);
        assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.PLACE));
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.POWER));

        EntityKey spawn = new EntityKey(harness.fixture.ticket.conn(), 42, SPAWN_UUID);
        harness.controller.onCrystalSpawn(spawn, "minecraft:end_crystal", SPAWN_UUID, SOURCE);
        harness.tick(5);
        assertEquals(PistonSequence.PistonPhase.POWER, harness.controller.phase());
        assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.POWER), "power waits for the crystal bind");

        harness.tick(6);
        assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.POWER));
        assertTrue(harness.controller.awaitingBlockConfirmation());
        assertEquals(PistonSequence.PistonPhase.POWER, harness.controller.phase());
        assertEquals(Optional.of(POWER), harness.controller.pendingCell());

        harness.confirmPower();
        assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, harness.controller.phase());
    }

    @Test
    void supportStageWaitsForFreshBlockObservation() {
        Harness harness = new Harness();
        Cell support = new Cell(-1, -61, 0);
        harness.world.blocks.remove(support);
        harness.tick(1);
        assertEquals(PistonSequence.PistonPhase.SUPPORTS, harness.controller.phase());
        harness.tick(2);
        assertTrue(harness.controller.awaitingBlockConfirmation());
        assertEquals(Optional.of(support), harness.controller.pendingCell());
        assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.SUPPORT));
        int attempts = harness.fixture.sink.attempts;

        harness.tick(3);
        assertEquals(attempts, harness.fixture.sink.attempts,
            "a pending support is not dispatched twice");
        assertEquals(PistonSequence.PistonPhase.SUPPORTS, harness.controller.phase());

        harness.world.put(support, "minecraft:obsidian");
        harness.controller.onBlockUpdate(support);
        assertEquals(PistonSequence.PistonPhase.PISTON, harness.controller.phase());
        assertFalse(harness.controller.awaitingBlockConfirmation());
        harness.tick(4);
        assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.PISTON));
    }

    @Test
    void observationForAnotherCellDoesNotConfirmPending() {
        Harness harness = new Harness();
        harness.tick(1);
        harness.tick(2);
        harness.tick(3);
        int attempts = harness.fixture.sink.attempts;

        harness.controller.onBlockUpdate(POWER);
        assertEquals(PistonSequence.PistonPhase.PISTON, harness.controller.phase());
        assertTrue(harness.controller.awaitingBlockConfirmation());
        assertEquals(Optional.of(PISTON), harness.controller.pendingCell());

        harness.tick(4);
        assertEquals(attempts, harness.fixture.sink.attempts,
            "another cell cannot settle the pending piston placement");
        harness.confirmPiston();
        assertEquals(PistonSequence.PistonPhase.CRYSTAL, harness.controller.phase());
    }

    // ------------------------------------------------------------------ production wiring seam

    @Test
    void directOnlyNeverRunsTheTrapAndPistonOnlyDoesWithoutAStrayDirectTrapPath() {
        Harness harness = new Harness();
        CrystalCombatController controller = new CrystalCombatController(() -> null, harness::stack);
        try {
            assertTrue(controller.enable());
            assertFalse(harness.controller.enabled(), "DirectOnly never runs the trap");
            int before = harness.fixture.sink.attempts;
            controller.tick(10);
            assertEquals(before, harness.fixture.sink.attempts, "off-path trap never sends");
            assertFalse(harness.controller.activeTrap().isPresent());

            controller.setMode(Mode.PISTON_ONLY);
            controller.setTrapEnabled(true);
            assertTrue(harness.controller.enabled(), "PistonOnly arms the trap");
            controller.tick(11);
            assertTrue(harness.controller.activeTrap().isPresent(), "PistonOnly scans/reserves");
            assertEquals(before, harness.fixture.sink.attempts, "reservation is not a send");

            controller.tick(12);
            assertEquals(PistonSequence.PistonPhase.PISTON, harness.controller.phase());
            controller.tick(13);
            assertTrue(harness.controller.awaitingBlockConfirmation());
            assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.PISTON),
                "the planned piston dispatch is the only send");
            assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.PLACE));
            assertEquals(0, harness.fixture.sink.count(FakeSink.Kind.POWER));

            // Switching back to DirectOnly must stop the trap immediately and send nothing.
            controller.setMode(Mode.DIRECT_ONLY);
            assertFalse(harness.controller.enabled(), "DirectOnly stops the trap");
            int switchedAt = harness.fixture.sink.attempts;
            controller.tick(14);
            controller.tick(15);
            assertEquals(switchedAt, harness.fixture.sink.attempts, "no trap packet after the switch");

            controller.setMode(Mode.PISTON_ONLY);
            controller.setTrapEnabled(false);
            assertFalse(harness.controller.enabled(), "the beta top-level gate still applies");

            // Phase 6.4 stage 1: Hybrid is accepted and keeps the trap available. What serialises the
            // field is a committed plan, not the mode name; no packet may appear from switching alone.
            controller.setTrapEnabled(true);
            int beforeHybrid = harness.fixture.sink.attempts;
            controller.setMode(Mode.HYBRID);
            assertEquals(Mode.HYBRID, controller.mode());
            assertTrue(harness.controller.enabled(), "Hybrid arms the trap");
            assertEquals(beforeHybrid, harness.fixture.sink.attempts, "a mode switch never sends");
        } finally {
            controller.disable();
        }
    }

    @Test
    void cleanupConfirmationReleasesTheTrapSlotAndAllowsTheNextTrap() {
        Harness harness = new Harness();
        CrystalCombatController controller = new CrystalCombatController(() -> null, harness::stack);
        try {
            assertTrue(controller.enable());
            controller.setMode(Mode.PISTON_ONLY);
            controller.setTrapEnabled(true);
            controller.tick(10);
            assertTrue(harness.controller.activeTrap().isPresent());
            int before = harness.fixture.sink.attempts;

            assertTrue(controller.confirmTrapCleanup(), "an active trap must be reconciled");
            assertTrue(harness.controller.activeTrap().isEmpty(), "the trap slot is released");
            assertFalse(controller.confirmTrapCleanup(), "nothing twice");
            assertEquals(before, harness.fixture.sink.attempts, "cleanup sends no packet");

            // A second layout can start only because the production command path released the slot.
            controller.tick(20);
            assertTrue(harness.controller.activeTrap().isPresent(),
                "the next trap may scan after explicit cleanup");
        } finally {
            controller.disable();
        }
    }

    @Test
    void disabledTrapIsNotLiveForTheHybridSentinel() {
        Harness harness = new Harness();
        harness.tick(1);
        assertTrue(harness.controller.activeTrap().isPresent());
        assertTrue(CrystalCombatController.trapIsLive(harness.controller));

        harness.controller.disable();
        assertTrue(harness.controller.activeTrap().isPresent(), "the retained sequence is kept for cleanup");
        assertFalse(CrystalCombatController.trapIsLive(harness.controller),
            "a disabled trap must not re-arm the Hybrid active-trap sentinel");

        harness.controller.enable();
        assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, harness.controller.activeTrap().orElseThrow().phase());
        assertFalse(CrystalCombatController.trapIsLive(harness.controller),
            "re-enabling a trap whose sequence needs manual cleanup must not re-arm the sentinel");

        harness.controller.reconcile();
        assertTrue(harness.controller.activeTrap().isEmpty());
        harness.tick(20);
        assertTrue(harness.controller.activeTrap().isPresent());
        assertTrue(CrystalCombatController.trapIsLive(harness.controller));

        harness.controller.death();
        assertFalse(CrystalCombatController.trapIsLive(harness.controller));
        harness.controller.enable();
        assertFalse(CrystalCombatController.trapIsLive(harness.controller),
            "a death-retained sequence is not live after re-enable");
        harness.controller.connectionClosed();
        assertFalse(CrystalCombatController.trapIsLive(harness.controller));
    }

    @Test
    void allLayoutsOffIsAVisibleWaitReasonNotAnIdleTrap() {
        Harness harness = new Harness();
        CrystalCombatController controller = new CrystalCombatController(() -> null, harness::stack);
        try {
            assertTrue(controller.enable());
            controller.setMode(Mode.PISTON_ONLY);
            controller.setTrapEnabled(true);
            controller.setPistonLayouts(PistonLayoutSelection.none());
            assertTrue(controller.waitReason().contains("all piston layouts disabled"),
                "the tester must see why PistonOnly cannot act");
        } finally {
            controller.disable();
        }
    }

    @Test
    void everyMissingTrapMaterialSuppressesThePlanWithoutReservingOrSending() {
        for (String itemId : List.of(PistonTrapController.PISTON_ITEM,
                PistonTrapController.CRYSTAL_ITEM, PistonTrapController.SUPPORT_ITEM,
                PistonGeometry.PowerKind.REDSTONE_TORCH.itemId())) {
            Harness harness = new Harness();
            try {
                harness.removeMaterial(itemId);
                harness.tick(1);
                harness.tick(2);
                assertTrue(harness.controller.activeTrap().isEmpty(), itemId);
                assertEquals(0, harness.fixture.sink.attempts, itemId);
                assertEquals(0, harness.fixture.scheduler.leaseCount(), itemId);
                assertTrue(harness.debug.stream().anyMatch(line -> line.contains("missing materials")),
                    itemId + " " + harness.debug);
            } finally {
                harness.controller.disable();
            }
        }
    }

    @Test
    void blockOnlyLayoutWithoutTheBlockMaterialSuppressesThePlan() {
        Harness harness = new Harness();
        try {
            harness.controller.setLayouts(PistonLayoutSelection.of(List.of(
                PistonLayoutSelection.byId("east-feet-redstone").orElseThrow())));
            harness.tick(1);
            assertTrue(harness.controller.activeTrap().isEmpty());
            assertEquals("redstone block", harness.controller.missingMaterialsText());
            assertEquals(0, harness.fixture.sink.attempts);
            assertEquals(0, harness.fixture.scheduler.leaseCount());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void missingMaterialsAreVisibleInTheInfoString() {
        Harness harness = new Harness();
        CrystalCombatController controller = new CrystalCombatController(() -> null, harness::stack);
        try {
            assertTrue(controller.enable());
            controller.setMode(Mode.PISTON_ONLY);
            controller.setTrapEnabled(true);
            controller.setPistonLayouts(PistonLayoutSelection.of(List.of(
                PistonLayoutSelection.byId("east-feet-torch").orElseThrow())));
            harness.removeMaterial(PistonTrapController.PISTON_ITEM);
            harness.removeMaterial(PistonGeometry.PowerKind.REDSTONE_TORCH.itemId());

            controller.tick(1);
            assertEquals("piston trap: missing materials: piston, redstone torch",
                controller.waitReason());
            assertEquals(controller.waitReason(), controller.infoString());
            // The change-triggered debug line itself is covered by
            // missingMaterialsAreLoggedOnceNotEveryTick, which taps the trap controller directly.
        } finally {
            controller.disable();
        }
    }

    @Test
    void missingMaterialsAreLoggedOnceNotEveryTick() {
        Harness harness = new Harness();
        try {
            harness.removeMaterial(PistonTrapController.PISTON_ITEM);
            harness.removeMaterial(PistonGeometry.PowerKind.REDSTONE_TORCH.itemId());
            harness.tick(1);
            harness.tick(2);
            harness.tick(3);
            long lines = harness.debug.stream()
                .filter(line -> line.contains("missing materials")).count();
            assertEquals(1, lines, harness.debug.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void materialRemovedBetweenPreviewAndStartPreventsTheReservation() {
        Harness harness = new Harness();
        try {
            assertTrue(harness.controller.previewPlan(1, TickBudget.unlimited()).isPresent());
            harness.removeMaterial(PistonTrapController.SUPPORT_ITEM);
            assertFalse(harness.controller.startPreviewed(1));
            assertTrue(harness.controller.activeTrap().isEmpty());
            assertEquals(0, harness.fixture.sink.attempts);
            assertEquals(0, harness.fixture.scheduler.leaseCount());
            assertTrue(harness.controller.missingMaterials().contains(PistonTrapController.SUPPORT_ITEM));
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void removingThePreviewedPlansOwnPowerItemPreventsTheReservation() {
        Harness harness = new Harness();
        try {
            assertTrue(harness.controller.previewPlan(1, TickBudget.unlimited()).isPresent());
            // The harness carries only the torch, so the previewed plan needs the torch for PLACE_POWER.
            assertTrue(harness.controller.missingMaterials().isEmpty());
            harness.removeMaterial(PistonGeometry.PowerKind.REDSTONE_TORCH.itemId());
            // A different enabled power kind is present, so the controller-wide check passes...
            harness.fixture.client.hotbar[4] = PistonGeometry.PowerKind.REDSTONE_BLOCK.itemId();
            harness.world.items.add(PistonGeometry.PowerKind.REDSTONE_BLOCK.itemId());
            assertTrue(harness.controller.missingMaterials().isEmpty());
            // ...but this exact plan cannot be built without the torch and must not begin.
            boolean started = harness.controller.startPreviewed(1);
            assertTrue(harness.debug.stream().anyMatch(line -> line.contains("power=REDSTONE_TORCH")),
                "the previewed plan must be the torch plan: " + harness.debug);
            assertFalse(started, "the previewed plan needs the removed torch");
            assertTrue(harness.controller.activeTrap().isEmpty());
            assertEquals(0, harness.fixture.scheduler.leaseCount());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void allLayoutsPreviewOfATorchPlanCannotStartWhenOnlyTheTorchIsGone() {
        Harness harness = new Harness();
        try {
            // All sixteen layouts are enabled and the harness hotbar holds only the torch power kind.
            assertTrue(harness.controller.previewPlan(1, TickBudget.unlimited()).isPresent());
            harness.removeMaterial(PistonGeometry.PowerKind.REDSTONE_TORCH.itemId());
            harness.addMaterial(PistonGeometry.PowerKind.REDSTONE_BLOCK.itemId());
            // Module-wide view: at least one enabled layout's power item is present again.
            assertTrue(harness.controller.missingMaterials().isEmpty());

            assertFalse(harness.controller.startPreviewed(1),
                "the exact previewed plan lost the torch it needs for PLACE_POWER");
            assertTrue(harness.controller.activeTrap().isEmpty());
            assertEquals(0, harness.fixture.sink.attempts);
            assertEquals(0, harness.fixture.scheduler.leaseCount());
            assertTrue(harness.debug.stream().anyMatch(
                line -> line.contains("plan-missing-materials") && line.contains("power=REDSTONE_TORCH")),
                "the refusal must name the plan and its missing power item: " + harness.debug);
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void redstoneBlockPlanStillStartsWhenOnlyTheTorchIsGone() {
        Harness harness = new Harness();
        try {
            harness.removeMaterial(PistonGeometry.PowerKind.REDSTONE_TORCH.itemId());
            harness.addMaterial(PistonGeometry.PowerKind.REDSTONE_BLOCK.itemId());
            assertTrue(harness.controller.missingMaterials().isEmpty());

            // The bounded planner scan sees the eight torch candidates first (now rejected as missing),
            // so the redstone-block plan needs a second planning pass before it can be previewed.
            assertTrue(harness.controller.previewPlan(1, TickBudget.unlimited()).isEmpty());
            assertTrue(harness.controller.previewPlan(2, TickBudget.unlimited()).isPresent());
            assertTrue(harness.controller.startPreviewed(2),
                "a redstone-block plan is buildable in this hotbar and must not be blocked");
            assertEquals(PistonGeometry.PowerKind.REDSTONE_BLOCK,
                harness.controller.activeTrap().orElseThrow().plan().push().powerKind());
        } finally {
            harness.controller.disable();
        }
    }

    /**
     * The Hybrid path samples the world before planning. The planner world only learns the live hotbar
     * when that sample refreshes the snapshot, so a restock the snapshot has not read yet must still be
     * visible to the same planning tick.
     */
    @Test
    void previewPlanRefreshesThePlannerWorldBeforePlanning() {
        Harness harness = new Harness();
        try {
            harness.world.modelSnapshotRefresh();
            harness.removeMaterial(PistonGeometry.PowerKind.REDSTONE_TORCH.itemId());
            assertFalse(harness.controller.previewPlan(1, TickBudget.unlimited()).isPresent(),
                "no enabled layout has its power item in the planner snapshot");

            // Restock the live hotbar only: the planner snapshot is stale until the sample refreshes it.
            harness.stageOnlyMaterial(PistonGeometry.PowerKind.REDSTONE_BLOCK.itemId());
            assertFalse(harness.world.hasItem(PistonGeometry.PowerKind.REDSTONE_BLOCK.itemId()),
                "the planner snapshot must not see the restock before the sample");

            // The first refreshed pass proves the snapshot sees the restock but only walks the eight
            // now-missing torch candidates; the next pass reaches the redstone-block candidates.
            assertTrue(harness.controller.previewPlan(2, TickBudget.unlimited()).isEmpty());
            assertTrue(harness.world.hasItem(PistonGeometry.PowerKind.REDSTONE_BLOCK.itemId()),
                "previewPlan must sample the planner world before planning");
            assertTrue(harness.controller.previewPlan(3, TickBudget.unlimited()).isPresent());
            assertTrue(harness.controller.startPreviewed(3));
            assertEquals(PistonGeometry.PowerKind.REDSTONE_BLOCK,
                harness.controller.activeTrap().orElseThrow().plan().push().powerKind());
        } finally {
            harness.controller.disable();
        }
    }

    /**
     * Regression for the fresh-Hybrid deadlock: the trap's stationary trackers only move when the world
     * is sampled, and a fresh session has never ticked the trap, so previews must take the sample.
     */
    @Test
    void hybridPreviewSamplesTheWorldUntilTheTargetIsStationary() {
        Harness harness = new Harness();
        try {
            harness.world.stationaryFromSamples = true;
            harness.world.sampledTicks = 0;

            for (int tick = 1; tick < PistonPlanner.STATIONARY_TICKS; tick++) {
                assertTrue(harness.controller.previewPlan(tick, TickBudget.unlimited()).isEmpty(),
                    "no plan may be offered after only " + tick + " sampled stationary tick(s)");
            }
            assertEquals(PistonPlanner.STATIONARY_TICKS - 1, harness.world.sampledTicks,
                "every preview must take one world sample");

            assertTrue(harness.controller.previewPlan(PistonPlanner.STATIONARY_TICKS,
                TickBudget.unlimited()).isPresent(),
                "ten sampled stationary ticks must make the fresh target eligible");
            assertEquals(PistonPlanner.STATIONARY_TICKS, harness.world.sampledTicks);

            // A preview is read-only apart from the sample: it never scans, reserves or sends.
            assertEquals(0, harness.fixture.sink.attempts);
            assertEquals(0, harness.fixture.scheduler.leaseCount());
            assertTrue(harness.controller.activeTrap().isEmpty());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void twoPreviewsInTheSameServerTickSampleTheWorldOnce() {
        Harness harness = new Harness();
        try {
            assertTrue(harness.controller.previewPlan(4, TickBudget.unlimited()).isPresent());
            assertEquals(1, harness.world.onServerTicks);
            assertEquals(1, harness.world.refreshCount);

            assertTrue(harness.controller.previewPlan(4, TickBudget.unlimited()).isPresent());
            assertEquals(1, harness.world.onServerTicks,
                "a second preview in the same server tick must not resample the world");
            assertEquals(1, harness.world.refreshCount);
            assertEquals(1, harness.world.stationaryAdvances);
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void pistonOnlyTrapResumesScanningAfterAnExhaustedEmptyPass() {
        Harness harness = new Harness();
        CrystalCombatController controller = new CrystalCombatController(() -> null, harness::stack);
        try {
            assertTrue(controller.enable());
            controller.setMode(Mode.PISTON_ONLY);
            controller.setTrapEnabled(true);
            harness.world.stationaryTicks = 0;

            controller.tick(1); // full empty pass: the target is not stationary yet
            assertTrue(harness.controller.activeTrap().isEmpty());
            controller.tick(2); // still not stationary: the pass must restart instead of replaying
            assertTrue(harness.controller.activeTrap().isEmpty());

            harness.world.stationaryTicks = 10; // the target has now been still for long enough
            controller.tick(3);
            assertTrue(harness.controller.activeTrap().isPresent(),
                "an exhausted empty pass must restart so the trap can start");
            PistonPlanner.Plan plan = harness.plan();
            assertEquals(harness.world.target, plan.target());
            assertTrue(harness.world.hasItem(plan.push().powerKind().itemId()),
                "the started plan must pass the material gate");
        } finally {
            controller.disable();
        }
    }

    @Test
    void oneTickThatPreviewsAndStartsAdvancesTheStationaryCounterOnce() {
        Harness harness = new Harness();
        try {
            assertTrue(harness.controller.previewPlan(1, TickBudget.unlimited()).isPresent());
            assertEquals(1, harness.world.refreshCount, "preview samples the world exactly once");
            assertEquals(1, harness.world.onServerTicks);
            assertEquals(1, harness.world.stationaryAdvances);
            assertTrue(harness.controller.startPreviewed(1));
            assertEquals(1, harness.world.stationaryAdvances,
                "selecting/beginning a plan must not advance the motion counters again");
            assertEquals(1, harness.world.onServerTicks);

            // The same Hybrid tick then ticks the now-active trap; the per-tick dedupe keeps it at one
            // sample, so the preview and the trap tick cannot double-count the same server tick.
            harness.tick(1);
            assertEquals(1, harness.world.onServerTicks,
                "the trap tick must not sample the same server tick twice");
            assertEquals(1, harness.world.stationaryAdvances, "never double-counted");
            assertEquals(1, harness.world.refreshCount,
                "one world sample for the preview and the same tick's trap tick");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void cleanupKeepsAlreadySentRiskAsDebt() {
        Harness harness = new Harness();
        CrystalCombatController controller = new CrystalCombatController(() -> null, harness::stack);
        try {
            assertTrue(controller.enable());
            controller.setMode(Mode.PISTON_ONLY);
            controller.setTrapEnabled(true);
            controller.tick(10);
            controller.tick(11);
            controller.tick(12);
            assertEquals(1, harness.fixture.sink.count(FakeSink.Kind.PISTON));
            int debt = harness.fixture.budget.debt(harness.fixture.self()).count();
            assertTrue(debt > 0, "the sent structural packet has a zero-bound debt entry");

            assertTrue(controller.confirmTrapCleanup());
            assertEquals(debt, harness.fixture.budget.debt(harness.fixture.self()).count(),
                "manual cleanup releases the slot but never retires sent risk");
            assertTrue(harness.controller.activeTrap().isEmpty());
        } finally {
            controller.disable();
        }
    }

    @Test
    void disablingTheTrapMidTrapKeepsTheSequenceButSendsNothing() {
        Harness harness = new Harness();
        try {
            harness.tick(1);
            harness.tick(2);
            harness.tick(3);
            int atDisable = harness.fixture.sink.attempts;
            harness.controller.disable();
            assertTrue(harness.controller.enabled() == false);
            harness.tick(4);
            harness.tick(5);
            assertEquals(atDisable, harness.fixture.sink.attempts, "disable sends no cleanup packet");
            assertTrue(harness.incidents.stream().anyMatch(line -> line.contains("manual-cleanup-required")),
                "manual cleanup is marked when debug diagnostics are attached");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void deathMidTrapSendsNothing() {
        Harness harness = new Harness();
        try {
            harness.tick(1);
            harness.tick(2);
            harness.tick(3);
            int atDeath = harness.fixture.sink.attempts;
            harness.controller.death();
            harness.tick(4);
            harness.tick(5);
            assertEquals(atDeath, harness.fixture.sink.attempts, "death ends the trap without a packet");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void disablingAllBetaLayoutsLeavesTheTrapInert() {
        Harness harness = new Harness();
        try {
            harness.controller.setLayouts(dev.tntcoating.core.combat.PistonLayoutSelection.none());
            harness.tick(1);
            harness.tick(2);
            assertFalse(harness.controller.activeTrap().isPresent(), "no fallback layout set");
            assertEquals(0, harness.fixture.sink.attempts);
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void c21SuppressedClientSyncIsDiagnosticNotAnIncident() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            harness.world.suppressCrystalClientPosition = true;
            harness.tick(7);
            assertTrue(harness.debug.stream().anyMatch(line -> line.contains("c21-suppressed")),
                "the absent client observation is recorded as a C21 diagnostic");
            assertFalse(harness.incidents.stream().anyMatch(line -> line.contains("position-disagreement")),
                "no disagreement incident without a client position update");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void staleClientPositionIsDiagnosticNotAnIncident() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            harness.tick(7);
            assertTrue(harness.debug.stream().anyMatch(line -> line.contains("no-update=true")),
                "the stale client position is recorded");
            assertFalse(harness.incidents.stream().anyMatch(line -> line.contains("position-disagreement")));
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void realClientPositionContradictionIsAnIncident() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            EntityKey spawn = harness.world.crystals.iterator().next();
            Vec computed = harness.sequence().computedPushed();
            harness.world.crystalClientPositions.put(spawn,
                new Vec(computed.x() + 1.0, computed.y(), computed.z()));
            harness.tick(7);
            assertTrue(harness.incidents.stream().anyMatch(line -> line.contains("position-disagreement")),
                "a genuine client update beyond tolerance is an incident");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void wrongBlockConfirmationIsAnIncident() {
        Harness harness = new Harness();
        try {
            harness.tick(1);
            harness.tick(2);
            harness.tick(3);
            harness.world.put(PISTON, "minecraft:stone");
            harness.controller.onBlockUpdate(PISTON);
            assertTrue(harness.incidents.stream().anyMatch(
                line -> line.contains("block-confirmation-wrong-block")), harness.incidents.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void blockConfirmationTimeoutIsAnIncident() {
        Harness harness = new Harness();
        try {
            harness.tick(1);
            harness.tick(2);
            harness.tick(3);
            for (int tick = 4; tick <= 40; tick++) harness.tick(tick);
            assertTrue(harness.incidents.stream().anyMatch(
                line -> line.contains("block-confirmation-timeout")), harness.incidents.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void abortAfterPowerIsAnIncident() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            harness.world.powered.add(PISTON);
            harness.tick(8); // powered after the 2-tick extension -> ATTACK
            assertEquals(PistonSequence.PistonPhase.ATTACK, harness.controller.phase());
            harness.world.uuids.remove(harness.world.crystals.iterator().next());
            harness.tick(9); // unverified attack aborts after POWER
            assertTrue(harness.incidents.stream().anyMatch(
                line -> line.contains("trap-aborted-after-power")), harness.incidents.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void attackWithNoObservedCrystalIsAnIncident() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            harness.world.powered.add(PISTON);
            harness.tick(8); // ATTACK
            harness.world.crystals.clear(); // the bound crystal is no longer observed
            harness.tick(9);
            assertTrue(harness.incidents.stream().anyMatch(
                line -> line.contains("attack-computed-unobserved")), harness.incidents.toString());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void debugLogRecordsLayoutComputedPositionStagesAndBlockConfirmations() {
        Harness harness = new Harness();
        try {
            harness.advanceToWaitPush();
            harness.tick(7);
            String debug = String.join("\n", harness.debug);
            assertTrue(debug.contains("trap-plan"), "chosen layout line missing: " + debug);
            assertTrue(debug.contains("computed-final=Vec"), "computed final position missing: " + debug);
            assertTrue(debug.contains("stage "), "stage transition missing: " + debug);
            assertTrue(debug.contains("block-confirmed"), "block confirmation missing: " + debug);
            assertTrue(debug.contains("position-cross-check"), "client cross-check missing: " + debug);
        } finally {
            harness.controller.disable();
        }
    }

    // --------------------------------------------------------------- beta auto-cleanup

    @Test
    void autoCleanupRemovesOwnedPowerThenPistonThenSupports() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            harness.advanceToWaitPushWithSupport();
            harness.world.powered.add(PISTON);
            harness.world.extended.add(PISTON);
            harness.tick(7);
            harness.tick(8);
            harness.tick(9);
            harness.controller.onCrystalRemoved(harness.sequence().bound().orElseThrow().entity());
            assertEquals(PistonSequence.PistonPhase.CLEANUP, harness.controller.phase());

            for (int tick = 10; tick < 30; tick++) {
                harness.tick(tick);
            }
            assertEquals(0, harness.fixture.sink.count(BREAK), "cleanup waits its 20-tick slot turn");
            harness.tick(30);
            assertEquals(1, harness.fixture.sink.count(BREAK),
                "debug=" + harness.debug + " lastFailure=" + harness.controller.lastFailure()
                    + " phase=" + harness.controller.phase());
            assertEquals(POWER, broke(harness.fixture.sink.last(BREAK)));

            harness.world.blocks.remove(POWER);
            harness.observeBlock(POWER, "minecraft:redstone_torch", "minecraft:air");
            harness.tick(31);
            assertEquals(2, harness.fixture.sink.count(BREAK));
            assertEquals(PISTON, broke(harness.fixture.sink.last(BREAK)));
            assertTrue(harness.fixture.sink.sends.stream().anyMatch(
                send -> send.kind() == FakeSink.Kind.SLOT && send.number() == 4),
                "the adapter chose the hotbar pickaxe for the piston");

            Cell support = new Cell(-1, -61, 0);
            harness.world.blocks.remove(PISTON);
            harness.observeBlock(PISTON, "minecraft:piston", "minecraft:air");
            harness.tick(32);
            assertEquals(3, harness.fixture.sink.count(BREAK));
            assertEquals(support, broke(harness.fixture.sink.last(BREAK)));

            harness.world.blocks.remove(support);
            harness.observeBlock(support, "minecraft:obsidian", "minecraft:air");
            harness.tick(33);
            assertEquals(PistonSequence.PistonPhase.COOLDOWN, harness.controller.phase());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void autoCleanupNeverBreaksAContestedBlock() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            harness.advanceToCleanup();
            // A second attribution for the power cell: the ledger can no longer name us.
            harness.observeBlock(POWER, "minecraft:air", "minecraft:redstone_torch");
            assertEquals(Ownership.CONTESTED, harness.fixture.crystals.ownership(POWER));

            for (int tick = 10; tick < 30; tick++) {
                harness.tick(tick);
            }
            harness.tick(30);
            assertEquals(1, harness.fixture.sink.count(BREAK));
            assertEquals(PISTON, broke(harness.fixture.sink.last(BREAK)), "power is contested and skipped");

            harness.world.blocks.remove(PISTON);
            harness.observeBlock(PISTON, "minecraft:piston", "minecraft:air");
            for (int tick = 31; tick <= 50; tick++) {
                harness.tick(tick);
            }
            assertEquals(1, harness.fixture.sink.count(BREAK), "the contested power is never broken");
            assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, harness.controller.phase());
            assertTrue(harness.controller.manualCleanupText().contains("power ("),
                harness.controller.manualCleanupText());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void autoCleanupSkipsACellWhoseBlockChangedBeforeTheSend() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            harness.advanceToCleanup();
            harness.world.put(POWER, "minecraft:stone");
            harness.observeBlock(POWER, "minecraft:redstone_torch", "minecraft:stone");

            for (int tick = 10; tick < 30; tick++) {
                harness.tick(tick);
            }
            harness.tick(30);
            assertEquals(1, harness.fixture.sink.count(BREAK));
            assertEquals(PISTON, broke(harness.fixture.sink.last(BREAK)),
                "the power cell no longer holds the block this trap placed");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void autoCleanupOffNeverSendsACleanupPacket() {
        Harness harness = new Harness();
        try {
            harness.advanceToCleanup();
            for (int tick = 10; tick <= 55; tick++) {
                harness.tick(tick);
            }
            assertEquals(0, harness.fixture.sink.count(BREAK));
            assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, harness.controller.phase());
            assertTrue(harness.controller.manualCleanupRequired());
            assertTrue(harness.controller.manualCleanupText().contains("power ("),
                harness.controller.manualCleanupText());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void autoCleanupTogglesLiveThroughTheConfigPath() {
        Harness harness = new Harness();
        try {
            harness.advanceToCleanup();
            for (int tick = 10; tick < 15; tick++) {
                harness.tick(tick);
            }
            assertEquals(0, harness.fixture.sink.count(BREAK));

            harness.controller.setAutoCleanup(true);
            for (int tick = 15; tick < 35; tick++) {
                harness.tick(tick);
            }
            assertEquals(0, harness.fixture.sink.count(BREAK), "the wait starts when cleanup is enabled");
            harness.tick(35);
            assertEquals(1, harness.fixture.sink.count(BREAK));
            assertEquals(POWER, broke(harness.fixture.sink.last(BREAK)));
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void cleanupDeadlineFallsBackToManualWithTheRemainingBlocks() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            harness.advanceToCleanup();
            for (int tick = 10; tick <= 49; tick++) {
                harness.tick(tick);
            }
            assertEquals(PistonSequence.PistonPhase.CLEANUP, harness.controller.phase());
            harness.tick(50);
            assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, harness.controller.phase());
            assertTrue(harness.controller.manualCleanupRequired());
            assertTrue(harness.controller.riskRetained());
            String text = harness.controller.manualCleanupText();
            assertTrue(text.contains("power ("), text);
            assertTrue(text.contains("piston ("), text);
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void disableMidCleanupStopsImmediately() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            harness.advanceToCleanup();
            for (int tick = 10; tick < 30; tick++) {
                harness.tick(tick);
            }
            harness.tick(30);
            assertEquals(1, harness.fixture.sink.count(BREAK));
            harness.controller.disable();
            for (int tick = 31; tick <= 60; tick++) {
                harness.tick(tick);
            }
            assertEquals(1, harness.fixture.sink.count(BREAK));
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void deathAndConnectionCloseStopCleanup() {
        Harness death = new Harness();
        try {
            death.controller.setAutoCleanup(true);
            death.advanceToCleanup();
            for (int tick = 10; tick < 30; tick++) {
                death.tick(tick);
            }
            death.tick(30);
            int afterDeathSend = death.fixture.sink.count(BREAK);
            death.controller.death();
            for (int tick = 31; tick <= 60; tick++) {
                death.tick(tick);
            }
            assertEquals(afterDeathSend, death.fixture.sink.count(BREAK));
        } finally {
            death.controller.disable();
        }

        Harness closed = new Harness();
        try {
            closed.controller.setAutoCleanup(true);
            closed.advanceToCleanup();
            for (int tick = 10; tick < 30; tick++) {
                closed.tick(tick);
            }
            closed.tick(30);
            int afterCloseSend = closed.fixture.sink.count(BREAK);
            closed.controller.connectionClosed();
            for (int tick = 31; tick <= 60; tick++) {
                closed.tick(tick);
            }
            assertEquals(afterCloseSend, closed.fixture.sink.count(BREAK));
        } finally {
            closed.controller.disable();
        }
    }

    @Test
    void autoCleanupRequiresLedgerOwnership() {
        Harness harness = new Harness();
        try {
            harness.feedLedger = false;
            harness.controller.setAutoCleanup(true);
            harness.advanceToCleanup();
            assertEquals(Ownership.NOT_OURS, harness.fixture.crystals.ownership(POWER));
            for (int tick = 10; tick <= 50; tick++) {
                harness.tick(tick);
            }
            assertEquals(0, harness.fixture.sink.count(BREAK),
                "without a confirmed placement the ledger never names the cell ours");
            assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, harness.controller.phase());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void autoCleanupWithoutAHotbarPickaxeBreaksTheTorchOnlyAndGoesManual() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            harness.advanceToCleanup();
            harness.fixture.client.hotbar[4] = "";
            harness.fixture.client.offhand = "minecraft:diamond_pickaxe";
            for (int tick = 10; tick <= 30; tick++) {
                harness.tick(tick);
            }
            // A torch needs no tool, so the power cell still goes first. The piston and obsidian supports
            // need a hotbar pickaxe and must be skipped, never taken from the offhand.
            assertEquals(1, harness.fixture.sink.count(BREAK),
                "without a hotbar pickaxe only the torch is removable");
            assertEquals(POWER, broke(harness.fixture.sink.last(BREAK)),
                "the selected hotbar slot removes the torch; the offhand is never used");
            harness.world.blocks.remove(POWER);
            harness.observeBlock(POWER, "minecraft:redstone_torch", "minecraft:air");
            for (int tick = 31; tick <= 50; tick++) {
                harness.tick(tick);
            }
            assertEquals(1, harness.fixture.sink.count(BREAK), "no pickaxe means no further break");
            assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, harness.controller.phase());
            assertTrue(harness.controller.manualCleanupText().contains("piston ("),
                "the piston is listed for manual removal");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void autoCleanupWaitForAnUnresolvedTrapCrystal() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            harness.advanceToCleanupWithoutAttack();
            for (int tick = 10; tick <= 49; tick++) {
                harness.tick(tick);
            }
            assertEquals(0, harness.fixture.sink.count(BREAK),
                "cleanup never starts while the trap crystal is unresolved");
            harness.tick(50);
            assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, harness.controller.phase());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void autoCleanupStartsOnceTheTrapCrystalIsGone() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            harness.advanceToCleanupWithoutAttack();
            harness.controller.onCrystalRemoved(harness.sequence().bound().orElseThrow().entity());
            for (int tick = 10; tick < 30; tick++) {
                harness.tick(tick);
            }
            harness.tick(30);
            assertEquals(1, harness.fixture.sink.count(BREAK));
            assertEquals(POWER, broke(harness.fixture.sink.last(BREAK)));
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void disableMidCleanupCancelsTheVanillaDestructionItStarted() {
        Harness harness = new Harness();
        try {
            harness.advanceToFirstCleanupBreak();
            harness.controller.disable();

            assertEquals(1, harness.fixture.sink.stops(), "disable stops the destruction it started");
            assertNull(harness.fixture.sink.breaking(), "no destruction is left running");
            assertEquals(STOP, harness.fixture.sink.last().kind(), "the stop is the last packet");

            for (int tick = 31; tick <= 60; tick++) {
                harness.tick(tick);
            }
            assertEquals(1, harness.fixture.sink.count(BREAK), "no cleanup break follows the halt");
            assertEquals(1, harness.fixture.sink.stops());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void midBreakBlockChangeCancelsDestructionAndSendsNoFurtherBreak() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            Cell support = new Cell(-1, -61, 0);
            harness.advanceToWaitPushWithSupport();
            harness.world.powered.add(PISTON);
            harness.world.extended.add(PISTON);
            harness.tick(7);
            harness.tick(8);
            harness.tick(9);
            harness.controller.onCrystalRemoved(harness.sequence().bound().orElseThrow().entity());
            for (int tick = 10; tick < 30; tick++) {
                harness.tick(tick);
            }
            harness.tick(30);
            assertEquals(POWER, harness.fixture.sink.breaking());
            harness.world.blocks.remove(POWER);
            harness.observeBlock(POWER, "minecraft:redstone_torch", "minecraft:air");
            harness.tick(31);
            assertEquals(PISTON, harness.fixture.sink.breaking());
            harness.world.blocks.remove(PISTON);
            harness.observeBlock(PISTON, "minecraft:piston", "minecraft:air");
            harness.tick(32);
            assertEquals(support, harness.fixture.sink.breaking());
            int breaks = harness.fixture.sink.count(BREAK);
            int stops = harness.fixture.sink.stops();

            // The cell we are breaking stops being ours mid-break.
            harness.world.put(support, "minecraft:stone");
            harness.observeBlock(support, "minecraft:obsidian", "minecraft:stone");

            assertEquals(stops + 1, harness.fixture.sink.stops(),
                "the in-progress destruction is cancelled");
            assertNull(harness.fixture.sink.breaking());
            harness.tick(33);
            assertEquals(breaks, harness.fixture.sink.count(BREAK), "no further break is sent");
            assertEquals(PistonSequence.PistonPhase.COOLDOWN, harness.controller.phase());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void cleanupDeadlineCancelsDestructionBeforeManualFallback() {
        Harness harness = new Harness();
        try {
            harness.advanceToFirstCleanupBreak();
            for (int tick = 31; tick < 50; tick++) {
                harness.tick(tick);
            }
            int breaks = harness.fixture.sink.count(BREAK);

            harness.tick(50);

            assertEquals(1, harness.fixture.sink.stops(), "the deadline cancels the destruction");
            assertNull(harness.fixture.sink.breaking());
            assertEquals(breaks, harness.fixture.sink.count(BREAK));
            assertEquals(STOP, harness.fixture.sink.last().kind());
            assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, harness.controller.phase());
            assertTrue(harness.controller.manualCleanupRequired());
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void deathAndRespawnCancelTheDestructionInProgress() {
        Harness death = new Harness();
        try {
            death.advanceToFirstCleanupBreak();
            death.controller.death();
            assertEquals(1, death.fixture.sink.stops());
            assertNull(death.fixture.sink.breaking());
            for (int tick = 31; tick <= 60; tick++) {
                death.tick(tick);
            }
            assertEquals(1, death.fixture.sink.count(BREAK), "death stops cleanup immediately");
        } finally {
            death.controller.disable();
        }

        Harness respawn = new Harness();
        try {
            respawn.advanceToFirstCleanupBreak();
            respawn.controller.respawn();
            assertEquals(1, respawn.fixture.sink.stops());
            assertNull(respawn.fixture.sink.breaking());
            for (int tick = 31; tick <= 60; tick++) {
                respawn.tick(tick);
            }
            assertEquals(1, respawn.fixture.sink.count(BREAK), "respawn stops cleanup immediately");
        } finally {
            respawn.controller.disable();
        }
    }

    @Test
    void connectionCloseClearsDestructionWithoutSendingAnything() {
        Harness harness = new Harness();
        try {
            harness.advanceToFirstCleanupBreak();
            int sends = harness.fixture.sink.sends.size();

            harness.controller.connectionClosed();

            assertEquals(0, harness.fixture.sink.stops(), "a closed connection has nowhere to send a stop");
            assertEquals(sends, harness.fixture.sink.sends.size(), "connection close sends nothing");
            assertFalse(harness.fixture.runtime.stopDestruction(),
                "the runtime no longer tracks a destruction after the close");
            for (int tick = 31; tick <= 60; tick++) {
                harness.tick(tick);
            }
            assertEquals(1, harness.fixture.sink.count(BREAK), "no cleanup break follows the close");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void switchingCleanupCellsCancelsThePreviousDestruction() {
        Harness harness = new Harness();
        try {
            harness.advanceToFirstCleanupBreak();
            // The world view moves on before the observation arrives, as it can when the server update
            // is still in flight; cleanup now selects the piston while the power break is in progress.
            harness.world.blocks.put(POWER, "minecraft:air");

            harness.tick(31);

            assertEquals(1, harness.fixture.sink.stops(), "the power destruction is cancelled");
            assertEquals(PISTON, harness.fixture.sink.breaking(), "the piston break is now running");
            assertEquals(2, harness.fixture.sink.count(BREAK));
            List<FakeSink.Send> breaks = harness.fixture.sink.sends.stream()
                .filter(send -> send.kind() == BREAK)
                .toList();
            int powerBreak = harness.fixture.sink.sends.indexOf(breaks.get(0));
            int stop = harness.fixture.sink.sends.indexOf(harness.fixture.sink.last(STOP));
            int pistonBreak = harness.fixture.sink.sends.indexOf(breaks.get(1));
            assertTrue(powerBreak < stop, "the power break precedes the stop");
            assertTrue(stop < pistonBreak, "the stop precedes the piston break");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void cleanupUsesTicketScopedOwnershipNotAnotherConnectionsState() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            harness.advanceToCleanup();
            ConnectionKey other = new ConnectionKey(9_999L);
            var placement = harness.fixture.crystals.reservePlacement(other, POWER,
                "minecraft:redstone_torch");
            harness.fixture.crystals.markPlacementSent(placement, 0);
            harness.fixture.crystals.observe(new Observation(other, 9_000,
                harness.fixture.intake.appliedRevision(), System.nanoTime(), 500,
                ObservationKind.BLOCK_UPDATE, null, POWER, null, null,
                new ObservationPayload.BlockUpdate("minecraft:air", "minecraft:stone", false)));
            assertEquals(Ownership.CONTESTED, harness.fixture.crystals.ownership(POWER));
            assertEquals(Ownership.INFERRED_OURS,
                harness.fixture.crystals.ownership(harness.fixture.ticket.conn(), POWER));

            for (int tick = 10; tick < 30; tick++) {
                harness.tick(tick);
            }
            harness.tick(30);

            assertEquals(1, harness.fixture.sink.count(BREAK));
            assertEquals(POWER, broke(harness.fixture.sink.last(BREAK)),
                "another connection's contested state does not hide our own block");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void cleanupCreatesNoRiskEntriesAndReleasesItsPacketReservation() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            harness.advanceToCleanup();
            int outstanding = harness.fixture.budget.outstanding();
            int leases = harness.fixture.scheduler.leaseCount();
            for (int tick = 10; tick < 30; tick++) {
                harness.tick(tick);
            }
            harness.tick(30);
            assertEquals(outstanding, harness.fixture.budget.outstanding(),
                "cleanup consumes packet budget, never risk");
            harness.world.blocks.remove(POWER);
            harness.observeBlock(POWER, "minecraft:redstone_torch", "minecraft:air");
            harness.tick(31);
            harness.world.blocks.remove(PISTON);
            harness.observeBlock(PISTON, "minecraft:piston", "minecraft:air");
            harness.tick(32);
            Cell support = new Cell(-1, -61, 0);
            if (harness.world.blocks.containsKey(support)) {
                harness.world.blocks.remove(support);
                harness.observeBlock(support, "minecraft:obsidian", "minecraft:air");
            }
            harness.tick(33);

            assertEquals(PistonSequence.PistonPhase.COOLDOWN, harness.controller.phase());
            assertEquals(outstanding, harness.fixture.budget.outstanding(),
                "no cleanup risk survives completion");
            assertTrue(harness.fixture.scheduler.leaseCount() <= leases,
                "cleanup leases are released when the packet is sent");
            harness.controller.disable();
            assertEquals(outstanding, harness.fixture.budget.outstanding(),
                "disable leaves no cleanup risk behind");
            assertTrue(harness.fixture.scheduler.leaseCount() <= leases,
                "disable leaves no cleanup lease behind");
        } finally {
            harness.controller.disable();
        }
    }

    @Test
    void autoCleanupNeverBreaksBlocksThatExistedBeforeTheTrap() {
        Harness harness = new Harness();
        try {
            harness.controller.setAutoCleanup(true);
            Cell existingObsidian = new Cell(-2, -61, 0);
            Cell existingBedrock = new Cell(-3, -61, 0);
            harness.world.put(existingObsidian, "minecraft:obsidian");
            harness.world.put(existingBedrock, "minecraft:bedrock");
            harness.advanceToWaitPushWithSupport();
            Cell support = new Cell(-1, -61, 0);
            harness.world.powered.add(PISTON);
            harness.world.extended.add(PISTON);
            harness.tick(7);
            harness.tick(8);
            harness.tick(9);
            harness.controller.onCrystalRemoved(harness.sequence().bound().orElseThrow().entity());
            assertEquals(Ownership.NOT_OURS,
                harness.fixture.crystals.ownership(harness.fixture.ticket.conn(), existingObsidian));

            for (int tick = 10; tick < 50; tick++) {
                harness.tick(tick);
            }
            List<Cell> broken = harness.fixture.sink.sends.stream()
                .filter(send -> send.kind() == BREAK)
                .map(PistonTrapControllerTest::broke)
                .toList();
            assertTrue(broken.stream().noneMatch(existingObsidian::equals), broken.toString());
            assertTrue(broken.stream().noneMatch(existingBedrock::equals), broken.toString());
            assertTrue(broken.stream().noneMatch(support::equals), broken.toString());
            assertEquals("minecraft:obsidian", harness.world.blockId(existingObsidian),
                "the pre-existing support is still there");
            assertEquals("minecraft:bedrock", harness.world.blockId(existingBedrock));
            assertTrue(harness.fixture.sink.count(BREAK) >= 1);

            harness.tick(50);
            assertEquals(PistonSequence.PistonPhase.MANUAL_CLEANUP, harness.controller.phase());
            String manual = harness.controller.manualCleanupText();
            assertTrue(manual.contains("power ("), manual);
            assertTrue(manual.contains("piston ("), manual);
            assertTrue(manual.contains("supports ["), manual);
        } finally {
            harness.controller.disable();
        }
    }

    private static Cell broke(FakeSink.Send send) {
        var pos = send.hit().getBlockPos();
        return new Cell(pos.getX(), pos.getY(), pos.getZ());
    }

    // ------------------------------------------------------------------ harness


    private static final class Harness {
        final CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        final EntityKey target;
        final PistonWorld world;
        final PistonTrapController controller;
        final CleanupPriority cleanupPriority = new CleanupPriority();
        final List<String> debug = new ArrayList<>();
        final List<String> incidents = new ArrayList<>();
        boolean feedLedger = true;
        private long observationSequence;
        private int observationTick = 1_000;

        Harness() {
            target = new EntityKey(fixture.ticket.conn(), 50, TARGET_UUID);
            fixture.client.eye = new Vec(-2.5, -59.0, 0.5);
            fixture.client.hotbar[0] = "minecraft:obsidian";
            fixture.client.hotbar[1] = "minecraft:piston";
            fixture.client.hotbar[2] = "minecraft:redstone_torch";
            fixture.client.hotbar[3] = "minecraft:end_crystal";
            fixture.client.hotbar[4] = "minecraft:diamond_pickaxe";
            fixture.client.offhand = "minecraft:totem_of_undying";
            fixture.client.selectedSlot = 0;
            world = new PistonWorld(fixture.ticket.conn(), fixture.self(), target, TARGET_UUID);
            controller = new PistonTrapController(fixture.runtime, fixture.session, fixture.scheduler,
                fixture.budget, fixture.crystals, PistonTrapController.inventory(fixture.adapter),
                fixture.client, world);
            controller.setCleanupPriority(cleanupPriority);
            controller.setDiagnostics(new PistonTrapController.Diagnostics() {
                @Override
                public void debug(String line) {
                    debug.add(line);
                }

                @Override
                public void incident(String line) {
                    incidents.add(line);
                }
            });
        }

        PistonPlanner.Plan plan() {
            return sequence().plan();
        }

        PistonSequence sequence() {
            return controller.activeTrap().orElseThrow();
        }

        void tick(int serverTick) {
            fixture.runtime.tick(serverTick);
            controller.tick(serverTick, TickBudget.unlimited());
        }

        /**
         * Applies a block change the way production does: the ledger sees the old/new block ids and the
         * trap sees the block-update callback.
         */
        void observeBlock(Cell cell, String oldBlock, String newBlock) {
            if (feedLedger) {
                fixture.crystals.observe(new Observation(fixture.ticket.conn(), observationSequence++,
                    fixture.intake.appliedRevision(), System.nanoTime(), observationTick++,
                    ObservationKind.BLOCK_UPDATE, null, cell, null, null,
                    new ObservationPayload.BlockUpdate(oldBlock, newBlock, false)));
            }
            // The production client and the trap world are the same Minecraft level; the fakes are two
            // objects, so keep the runtime's block view in step with every applied update.
            if (newBlock == null || newBlock.isEmpty() || "minecraft:air".equals(newBlock)
                || "minecraft:cave_air".equals(newBlock)) {
                fixture.client.blocks.remove(cell);
            } else {
                fixture.client.blocks.put(cell, newBlock);
            }
            controller.onBlockUpdate(cell);
        }

        /**
         * Delivers an explosion centre through the runtime's registered observer seam. This is exactly
         * the production path: the runtime applies the EXPLOSION observation and forwards its centre.
         */
        void explode(Vec centre) {
            fixture.runtime.explosionObserver().accept(centre);
        }

        /** Removes a material from both the client hotbar and the planner's world view. */
        void removeMaterial(String itemId) {
            for (int slot = 0; slot < fixture.client.hotbar.length; slot++) {
                if (itemId.equals(fixture.client.hotbar[slot])) fixture.client.hotbar[slot] = "";
            }
            world.items.remove(itemId);
            world.stagedItems.remove(itemId);
        }

        /** Adds a material to the live hotbar and to the planner's world view. */
        void addMaterial(String itemId) {
            fixture.client.hotbar[freeHotbarSlot()] = itemId;
            world.items.add(itemId);
        }

        /** Adds a material to the live hotbar only; the planner sees it after the next refresh. */
        void stageOnlyMaterial(String itemId) {
            fixture.client.hotbar[freeHotbarSlot()] = itemId;
            world.stageMaterial(itemId);
        }

        private int freeHotbarSlot() {
            for (int slot = 0; slot < fixture.client.hotbar.length; slot++) {
                String item = fixture.client.hotbar[slot];
                if (item == null || item.isEmpty()) return slot;
            }
            throw new AssertionError("no free hotbar slot");
        }

        /** Runs through piston + crystal dispatch and one binding spawn, ending in POWER. */
        void advanceToPower() {
            tick(1); // scan -> supports
            tick(2); // supports settle -> piston
            tick(3); // piston packet sent, awaiting block observation
            confirmPiston();
            tick(4); // crystal packet
            deliverSpawn();
            tick(5); // bind -> power
            tick(6); // power packet sent, awaiting block observation
            assertEquals(PistonSequence.PistonPhase.POWER, controller.phase());
            assertEquals(Optional.of(POWER), controller.pendingCell());
        }

        /** Runs through piston + crystal + power dispatch and one binding spawn, ending in WAIT_PUSH. */
        void advanceToWaitPush() {
            advanceToPower();
            confirmPower();
            assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, controller.phase());
        }

        /** Pushes the crystal, attacks it, then observes the removal: ends in CLEANUP. */
        void advanceToCleanup() {
            advanceToWaitPush();
            world.powered.add(PISTON);
            world.extended.add(PISTON);
            tick(7);
            tick(8);
            tick(9);
            assertEquals(PistonSequence.PistonPhase.WAIT_REMOVE, controller.phase());
            controller.onCrystalRemoved(sequence().bound().orElseThrow().entity());
            assertEquals(PistonSequence.PistonPhase.CLEANUP, controller.phase());
        }

        /** Runs the 20-tick starvation wait and sends the first cleanup break, leaving it in progress. */
        void advanceToFirstCleanupBreak() {
            controller.setAutoCleanup(true);
            advanceToCleanup();
            for (int tick = 10; tick < 30; tick++) {
                tick(tick);
            }
            tick(30);
            assertEquals(1, fixture.sink.count(BREAK));
            assertEquals(POWER, fixture.sink.breaking());
        }

        /**
         * Ends in CLEANUP without an attack: the priced damage is below the configured floor, so the
         * crystal is still present and its removal must gate cleanup.
         */
        void advanceToCleanupWithoutAttack() {
            advanceToWaitPush();
            world.put(PISTON, "minecraft:piston");
            world.facings.put(PISTON, PistonGeometry.EAST);
            world.extended.add(PISTON);
            world.powered.add(PISTON);
            world.minimumDamage = 11.0;
            tick(7);
            tick(8);
            tick(9);
            assertEquals(PistonSequence.PistonPhase.CLEANUP, controller.phase());
            assertFalse(sequence().flags().attackSafe());
        }

        /**
         * Same as {@link #advanceToWaitPush}, but the planner needs one support under the crystal; the
         * support is placed and confirmed through the same block-observation callback as production.
         */
        void advanceToWaitPushWithSupport() {
            Cell support = new Cell(-1, -61, 0);
            world.blocks.remove(support);
            tick(1); // scan -> supports
            tick(2); // support packet sent, awaiting block observation
            assertEquals(Optional.of(support), controller.pendingCell());
            world.put(support, "minecraft:obsidian");
            observeBlock(support, "minecraft:air", "minecraft:obsidian");
            tick(3); // piston packet sent, awaiting block observation
            confirmPiston();
            tick(4); // crystal packet
            deliverSpawn();
            tick(5); // bind -> power
            tick(6); // power packet sent, awaiting block observation
            confirmPower();
            assertEquals(PistonSequence.PistonPhase.WAIT_PUSH, controller.phase());
        }

        private void deliverSpawn() {
            EntityKey spawn = new EntityKey(fixture.ticket.conn(), 42, SPAWN_UUID);
            controller.onCrystalSpawn(spawn, "minecraft:end_crystal", SPAWN_UUID, SOURCE);
            // The live world already contains the crystal at bind time, as production does.
            world.crystals.add(spawn);
            world.positions.put(spawn, SOURCE.center());
            world.uuids.put(spawn, SPAWN_UUID);
            fixture.client.putCrystal(42, SPAWN_UUID, SOURCE.center());
        }

        /** Applies the server-authored piston block state and its observation event. */
        void confirmPiston() {
            world.put(PISTON, "minecraft:piston");
            world.facings.put(PISTON, PistonGeometry.EAST);
            observeBlock(PISTON, "minecraft:air", "minecraft:piston");
        }

        /** Applies the exact power block for the planned kind and its observation event. */
        void confirmPower() {
            String item = plan().push().powerKind().itemId();
            world.put(POWER, item);
            observeBlock(POWER, "minecraft:air", item);
        }

        CrystalCombatController.Stack stack() {
            return new CrystalCombatController.Stack(fixture.runtime, fixture.session, fixture.crystals,
                fixture.scheduler, fixture.budget, fixture.client, fixture.intake, null,
                new CombatHurtModel(), controller);
        }
    }

    static final class PistonWorld implements PistonTrapController.World {
        final Map<Cell, String> blocks = new HashMap<>();
        final Map<Cell, PistonFacing> facings = new HashMap<>();
        final Map<EntityKey, Vec> positions = new LinkedHashMap<>();
        final Map<Vec, Double> victimDamage = new HashMap<>();
        final Set<EntityKey> friends = new HashSet<>();
        final Set<Cell> entities = new HashSet<>();
        final Set<Cell> extended = new HashSet<>();
        final Set<Cell> powered = new HashSet<>();
        final Set<EntityKey> crystals = new HashSet<>();
        final Map<EntityKey, UUID> uuids = new HashMap<>();
        final Map<EntityKey, Vec> crystalClientPositions = new LinkedHashMap<>();
        boolean suppressCrystalClientPosition;
        final EntityKey self;
        final EntityKey target;
        final UUID targetUuid;
        double speed;
        int stationaryTicks = 10;
        double selfCeiling = 6.0;
        double friendCeiling = 2.0;
        double minimumDamage = 6.0;
        double mitigation = 1.0;
        long planId = 7L;
        int currentTick = 100;
        boolean targetAlive = true;
        final Set<String> items = new HashSet<>(List.of(PistonTrapController.PISTON_ITEM,
            PistonTrapController.CRYSTAL_ITEM, PistonTrapController.SUPPORT_ITEM,
            PistonGeometry.PowerKind.REDSTONE_TORCH.itemId()));
        /** When set, only {@link #refresh(int)} copies these staged items into {@link #items}. */
        final Set<String> stagedItems = new HashSet<>();
        boolean snapshotFromStaged;
        /** When set, {@link #stationaryTicks(EntityKey)} reports the sampled ticks, not the fixed field. */
        boolean stationaryFromSamples;
        int sampledTicks;
        int refreshCount;
        int onServerTicks;
        int stationaryAdvances;
        int lastRefreshTick = -1;
        int lastSampleTick = Integer.MIN_VALUE;

        PistonWorld(ConnectionKey conn, EntityKey self, EntityKey target, UUID targetUuid) {
            this.self = self;
            this.target = target;
            this.targetUuid = targetUuid;
            positions.put(target, new Vec(0.5, -60.0, 0.5));
            positions.put(self, new Vec(40.0, 0.0, 40.0));
            uuids.put(target, targetUuid);
            floor(-61);
            put(new Cell(-1, -61, 0), "minecraft:obsidian");
            put(new Cell(-1, -62, 0), "minecraft:stone");
            blocks.put(new Cell(1, -60, 0), "minecraft:stone");
            blocks.put(new Cell(1, -59, 0), "minecraft:stone");
            blocks.put(new Cell(0, -60, 1), "minecraft:stone");
            blocks.put(new Cell(0, -59, 1), "minecraft:stone");
            blocks.put(new Cell(0, -60, -1), "minecraft:stone");
            blocks.put(new Cell(0, -59, -1), "minecraft:stone");
            blocks.put(new Cell(-1, -59, 0), "minecraft:stone");
            blocks.put(new Cell(-2, -59, 0), "minecraft:stone");
            blocks.put(new Cell(-3, -59, 0), "minecraft:stone");
        }

        void put(Cell cell, String id) {
            blocks.put(cell, id);
        }

        void floor(int y) {
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) put(new Cell(x, y, z), "minecraft:stone");
            }
        }

        @Override
        public List<EntityKey> targets() {
            return List.of(target);
        }

        @Override
        public EntityKey self() {
            return self;
        }

        /** Models production: the planner-visible items only change when {@link #refresh(int)} runs. */
        void modelSnapshotRefresh() {
            snapshotFromStaged = true;
            stagedItems.clear();
            stagedItems.addAll(items);
        }

        void stageMaterial(String itemId) {
            stagedItems.add(itemId);
        }

        @Override
        public void refresh(int serverTick) {
            refreshCount++;
            lastRefreshTick = serverTick;
            if (snapshotFromStaged) {
                items.clear();
                items.addAll(stagedItems);
            }
        }

        @Override
        public void onServerTick(int serverTick) {
            // Models production: a preview and a trap tick for the same server tick sample it once.
            if (serverTick == lastSampleTick) return;
            lastSampleTick = serverTick;
            refresh(serverTick);
            onServerTicks++;
            stationaryAdvances++;
            sampledTicks++;
        }

        @Override
        public boolean hasItem(String itemId) {
            return itemId != null && items.contains(itemId);
        }

        @Override
        public boolean alive(EntityKey entity) {
            return !entity.equals(target) || targetAlive;
        }

        @Override
        public EffectiveHealth health(EntityKey entity) {
            return new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L);
        }

        @Override
        public Map<EntityKey, EffectiveHealth> friendHealth() {
            Map<EntityKey, EffectiveHealth> result = new LinkedHashMap<>();
            for (EntityKey friend : friends) result.put(friend, health(friend));
            return result;
        }

        @Override
        public Safety safety() {
            return new Safety(minimumDamage, selfCeiling,
                dev.tntcoating.core.combat.CombatDamagePolicy.MIN_REMAINING_HEALTH, friendCeiling);
        }

        @Override
        public boolean withinAttackEnvelope(EntityKey entity, Vec pushed) {
            return position(entity).distance(pushed) <= 4.0;
        }

        @Override
        public UUID uuid(EntityKey entity) {
            return uuids.get(entity);
        }

        @Override
        public Set<EntityKey> crystalEntities() {
            return Set.copyOf(crystals);
        }

        @Override
        public Set<UUID> crystalUuids() {
            Set<UUID> result = new HashSet<>();
            for (EntityKey crystal : crystals) {
                UUID uuid = uuids.get(crystal);
                if (uuid != null) result.add(uuid);
            }
            return result;
        }

        @Override
        public Optional<Vec> crystalClientPosition(EntityKey entity) {
            if (entity == null || suppressCrystalClientPosition) return Optional.empty();
            Vec explicit = crystalClientPositions.get(entity);
            if (explicit != null) return Optional.of(explicit);
            if (crystals.contains(entity)) return Optional.of(position(entity));
            return Optional.empty();
        }

        @Override
        public boolean pistonExtended(Cell cell) {
            return extended.contains(cell);
        }

        @Override
        public List<Vec> feasiblePositions(EntityKey entity) {
            return List.of(position(entity));
        }

        @Override
        public Vec position(EntityKey entity) {
            return positions.getOrDefault(entity, new Vec(0.5, -60.0, 0.5));
        }

        @Override
        public double speed(EntityKey entity) {
            return speed;
        }

        @Override
        public int stationaryTicks(EntityKey entity) {
            return stationaryFromSamples ? sampledTicks : stationaryTicks;
        }

        @Override
        public double crystalDamage(Vec crystalCentre, Vec victimFeet) {
            Double override = victimDamage.get(victimFeet);
            if (override != null) return override;
            double dx = crystalCentre.x() - victimFeet.x();
            double dy = crystalCentre.y() - victimFeet.y();
            double dz = crystalCentre.z() - victimFeet.z();
            return Math.sqrt(dx * dx + dy * dy + dz * dz) < 2.0 ? 10.0 : 0.0;
        }

        @Override
        public List<EntityKey> nearbyFriends() {
            return new ArrayList<>(friends);
        }

        @Override
        public boolean friendly(EntityKey entity) {
            return friends.contains(entity);
        }

        @Override
        public double mitigation(EntityKey entity) {
            return mitigation;
        }

        @Override
        public double selfDamageCeiling() {
            return selfCeiling;
        }

        @Override
        public double friendDamageCeiling() {
            return friendCeiling;
        }

        @Override
        public double minimumTargetDamage() {
            return minimumDamage;
        }

        @Override
        public long planId() {
            return planId;
        }

        @Override
        public int currentTick() {
            return currentTick;
        }

        @Override
        public String blockId(Cell cell) {
            return blocks.get(cell);
        }

        @Override
        public boolean replaceable(Cell cell) {
            String id = blocks.get(cell);
            return id == null || id.equals("minecraft:air") || id.equals("minecraft:cave_air");
        }

        @Override
        public boolean fullSupport(Cell cell) {
            String id = blocks.get(cell);
            return "minecraft:stone".equals(id) || "minecraft:obsidian".equals(id)
                || "minecraft:bedrock".equals(id);
        }

        @Override
        public boolean entityFree(Cell cell) {
            return !entities.contains(cell);
        }

        @Override
        public boolean crystalPresent(Cell cell) {
            return false;
        }

        @Override
        public boolean pistonPowered(Cell cell) {
            return powered.contains(cell);
        }

        @Override
        public PistonFacing pistonFacing(Cell cell) {
            return facings.get(cell);
        }

        @Override
        public boolean redstoneComponent(Cell cell) {
            return false;
        }
    }
}
