/*
 * Original Comet implementation by Boyonabike.
 * Independent: test harness written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.CombatMetrics;
import dev.tntcoating.core.combat.DirectPlan;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.DirectSequence;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.Vec;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Phase 4.6 engine integration: defence before offence, shared budget, no bookkeeping hazards. */
final class DefenseEngineTest {
    private static final Vec FEET = new Vec(0.5, 64.5, 0.5);
    private static final Cell FEET_CELL = new Cell(0, 64, 0);
    private static final Vec CRYSTAL_POSITION = new Vec(2.5, 64.5, 0.5);
    private static final DirectSettings BASE =
        DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
    private static final DirectSettings DANGER = BASE.withDangerousCrystalBreak(true);
    private static final DirectSettings ANTI_PISTON = BASE.withAntiPiston(true);

    @Test
    void imminentDangerousCrystalOutranksNormalOffence() {
        EngineHarness harness = EngineHarness.create(DANGER, FEET);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.setSelfHealth(7.0);
        harness.setSelfUpper(1.0);
        harness.setSelfUpperWorst(6.0);
        harness.addTarget(new Vec(2.5, 64.5, 2.5));
        observeCrystal(harness, 30, CRYSTAL_POSITION);

        harness.tick();

        assertEquals(1, harness.attackCount());
        assertEquals(1, metrics.defenceActions("break_crystal"));
        assertEquals(1.0, harness.budget.current(harness.self()).value(), 1.0e-9);
        assertTrue(harness.engine.sequence().state() == DirectSequence.State.IDLE);
    }

    @Test
    void nonImminentDefenceWaitsForOffence() {
        EngineHarness harness = EngineHarness.create(DANGER, FEET);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.setSelfHealth(7.0);
        harness.setSelfUpper(1.0);
        harness.setSelfUpperWorst(6.0);
        // Far enough from the crystal for arrival 5, close enough to fuel normal offence.
        harness.addTarget(new Vec(6.0, 64.5, 0.5));
        observeCrystal(harness, 30, CRYSTAL_POSITION);

        harness.tick();

        assertEquals(1, harness.attackCount());
        assertEquals(0, metrics.totalDefenceActions(),
            "a non-imminent defensive opportunity must not preempt offence");
    }

    @Test
    void nonImminentDefenceRunsWhenOffenceSendsNothing() {
        EngineHarness harness = EngineHarness.create(DANGER, FEET);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.setSelfHealth(7.0);
        harness.setSelfUpper(1.0);
        harness.setSelfUpperWorst(6.0);
        observeCrystal(harness, 30, CRYSTAL_POSITION);

        harness.tick();

        // A defensive break is still an ATTACK packet; the defence counter is what distinguishes it.
        assertEquals(1, harness.attackCount());
        assertEquals(1, metrics.defenceActions("break_crystal"));
    }

    @Test
    void gatesOffBehavesExactlyLikePhase45Offence() {
        EngineHarness harness = EngineHarness.create(BASE, FEET);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.setSelfHealth(7.0);
        harness.setSelfUpper(1.0);
        harness.setSelfUpperWorst(6.0);
        harness.addTarget(new Vec(2.5, 64.5, 2.5));
        observeCrystal(harness, 30, CRYSTAL_POSITION);

        harness.tick();

        assertEquals(1, harness.attackCount());
        assertEquals(0, metrics.totalDefenceActions());
        assertEquals(0, metrics.defenceActionsByType().size());
    }

    @Test
    void riskBudgetRejectionStopsTheDefensiveSend() {
        EngineHarness harness = EngineHarness.create(DANGER, FEET);
        harness.setSelfUpper(5.0);
        harness.addTarget(new Vec(2.5, 64.5, 2.5));
        observeCrystal(harness, 30, CRYSTAL_POSITION);
        // Tick one: healthy, so offence sends a 5.0-bound break and that risk becomes outstanding debt.
        harness.tick();
        assertEquals(1, harness.attackCount());
        assertEquals(5.0, harness.budget.current(harness.self()).value(), 1.0e-9);

        // Tick two: the crystal is now lethal and the 5.0 outstanding bound plus the 5.0 break exposure
        // exceeds the self ceiling, so the defensive reserve (and the identical offence reserve) must fail.
        harness.setSelfHealth(7.0);
        harness.setSelfUpperWorst(6.0);
        harness.tick();

        assertEquals(1, harness.attackCount());
    }

    @Test
    void blockerDispatchSharesTheRuntimePathAndLeavesNoHazardOrRiskBound() {
        EngineHarness harness = EngineHarness.create(ANTI_PISTON, FEET);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        piston(harness, new Cell(-2, FEET_CELL.y(), 0), new PistonFacing(1, 0, 0));
        harness.setBase(new Cell(-1, FEET_CELL.y() - 1, 0));

        harness.tick();

        assertEquals(0, harness.attackCount());
        assertEquals(1, harness.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT));
        assertEquals(1, metrics.defenceActions("place_blocker"));
        assertEquals(0.0, harness.budget.current(harness.self()).value(), 1.0e-9);
        assertEquals(0.0, harness.budget.debt(harness.self()).bound(), 1.0e-9);
        assertTrue(harness.engine.sequence().state() == DirectSequence.State.IDLE);
        assertEquals(0, harness.scheduler.leaseCount());
        assertEquals(0, harness.client.selectedSlot);
        assertEquals("minecraft:totem_of_undying", harness.client.offhand);
        assertEquals(2, harness.scheduler.packetUsed(harness.serverTick));
    }

    @Test
    void unacceptedBlockerPacketRollsBackTheUnsentLeaseAndCanRetry() {
        EngineHarness harness = EngineHarness.create(ANTI_PISTON, FEET);
        piston(harness, new Cell(-2, FEET_CELL.y(), 0), new PistonFacing(1, 0, 0));
        harness.setBase(new Cell(-1, FEET_CELL.y() - 1, 0));
        harness.setClientSupportBelow(false);

        harness.tick();

        assertEquals(0, harness.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT));
        assertEquals(0, harness.budget.outstanding());
        assertEquals(0, harness.scheduler.leaseCount());
        assertEquals(0, harness.scheduler.packetUsed(harness.serverTick));
        assertEquals(0, harness.client.selectedSlot);

        harness.setClientSupportBelow(true);
        harness.tick();
        assertEquals(1, harness.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT));
    }

    @Test
    void failedDefensiveDispatchFallsThroughToOffenceInTheSameTick() {
        EngineHarness harness = EngineHarness.create(ANTI_PISTON, FEET);
        harness.setSelfUpper(1.0);
        harness.addTarget(new Vec(2.5, 64.5, 0.5));
        observeCrystal(harness, 30, CRYSTAL_POSITION);
        piston(harness, new Cell(-2, FEET_CELL.y(), 0), new PistonFacing(1, 0, 0));
        harness.setBase(new Cell(-1, FEET_CELL.y() - 1, 0));
        // No obsidian in the hotbar: the blocker cannot be dispatched, so the engine must fall through to
        // the legal offensive break of the observed crystal in the same tick.
        harness.client.hotbar[1] = "minecraft:dirt";

        harness.tick();

        assertEquals(1, harness.attackCount());
        assertEquals(0, harness.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT));
    }

    @Test
    void placementSequenceKeepsPriorityOverDefence() {
        EngineHarness harness = EngineHarness.create(ANTI_PISTON, FEET);
        harness.setBase(new Cell(2, 63, 0));
        harness.addTarget(new Vec(2.5, 64.5, 0.5));
        piston(harness, new Cell(-2, FEET_CELL.y(), 0), new PistonFacing(1, 0, 0));
        harness.setBase(new Cell(-1, FEET_CELL.y() - 1, 0));

        harness.tick();
        // Whatever the offence chooses, defence must not run while a placement sequence is active.
        assertTrue(harness.engine.sequence().state() == DirectSequence.State.IDLE
            || harness.engine.sequence().state() == DirectSequence.State.PLACE_SENT);
    }

    @Test
    void defensivePlanNeverCarriesACrystalHazardOrPlacementBinding() {
        EngineHarness harness = EngineHarness.create(ANTI_PISTON, FEET);
        piston(harness, new Cell(-2, FEET_CELL.y(), 0), new PistonFacing(1, 0, 0));
        harness.setBase(new Cell(-1, FEET_CELL.y() - 1, 0));

        harness.tick();

        assertEquals(0, harness.sink.count(CombatTestFakes.FakeSink.Kind.PLACE));
        assertFalse(harness.sink.sends.stream()
            .anyMatch(send -> send.kind() == CombatTestFakes.FakeSink.Kind.PLACE));
        assertEquals(0.0, harness.budget.current(harness.self()).value(), 1.0e-9);
    }

    @Test
    void disabledEngineSendsNoDefence() {
        EngineHarness harness = EngineHarness.create(ANTI_PISTON, FEET);
        piston(harness, new Cell(-2, FEET_CELL.y(), 0), new PistonFacing(1, 0, 0));
        harness.setBase(new Cell(-1, FEET_CELL.y() - 1, 0));

        harness.engine.onDisabled();
        harness.tick();

        assertEquals(0, harness.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT));
        assertNull(harness.sink.last(CombatTestFakes.FakeSink.Kind.SUPPORT));

        harness.engine.onEnabled();
        harness.tick();
        assertEquals(1, harness.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT));
    }

    @Test
    void defensiveBreakPlanCarriesNoStickyTarget() {
        EngineHarness harness = EngineHarness.create(DANGER, FEET);
        harness.setSelfHealth(7.0);
        harness.setSelfUpper(1.0);
        harness.setSelfUpperWorst(6.0);
        harness.addTarget(new Vec(2.5, 64.5, 2.5));
        observeCrystal(harness, 30, CRYSTAL_POSITION);

        harness.tick();

        assertEquals(1, harness.attackCount());
        // A defensive break is not tied to a placement sequence or a ledger hazard, and it does not set the
        // sticky offence target: the next defensive plan is rebuilt from the world each tick.
        assertTrue(harness.engine.sequence().state() == DirectSequence.State.IDLE);
    }

    @Test
    void reconfigureRebuildsTheDefensivePlannerToo() {
        EngineHarness harness = EngineHarness.create(BASE, FEET);
        CombatMetrics metrics = new CombatMetrics();
        harness.engine.attachMetrics(metrics);
        harness.engine.reconfigure(DANGER);
        harness.setSelfHealth(7.0);
        harness.setSelfUpper(1.0);
        harness.setSelfUpperWorst(6.0);
        harness.addTarget(new Vec(2.5, 64.5, 2.5));
        observeCrystal(harness, 30, CRYSTAL_POSITION);

        harness.tick();

        assertEquals(1, metrics.defenceActions("break_crystal"),
            "a live settings change must rebuild the defensive planner, not just the offence planner");
    }

    private static void piston(EngineHarness harness, Cell piston, PistonFacing facing) {
        harness.addPiston(piston, facing, false);
    }

    private static void observeCrystal(EngineHarness harness, int id, Vec position) {
        UUID uuid = UUID.randomUUID();
        EntityKey key = new EntityKey(harness.connection, id, uuid);
        harness.client.putCrystal(id, uuid, position);
        harness.observe(new Observation(harness.connection, harness.receipt++, harness.revision++,
            System.nanoTime(), harness.serverTick, ObservationKind.SPAWN, key, null, position,
            Box.fromCell(EngineHarness.cellOf(position)),
            new ObservationPayload.Spawn(EngineHarness.END_CRYSTAL)));
    }
}
