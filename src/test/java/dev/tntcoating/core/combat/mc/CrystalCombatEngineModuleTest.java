/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.CrystalCombatGate;
import dev.tntcoating.core.combat.DirectPause;
import dev.tntcoating.core.combat.DirectSequence;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.HazardId;
import dev.tntcoating.core.combat.Mode;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.TickBudget;
import dev.tntcoating.core.combat.Vec;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Phase 3.6 module-level wiring: DirectOnly sends only through dispatch and stops on disable/death/pause. */
class CrystalCombatEngineModuleTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);
    private static final Cell BASE = new Cell(2, -61, 0);
    private static final Vec CRYSTAL = new Vec(2.5, -60.0, 0.5);
    private static final Vec TARGET = new Vec(2.5, -60.0, 3.0);

    @AfterEach
    void resetGate() {
        CrystalCombatGate.deactivate();
    }

    /**
     * First-tick dispatch assertions use an unlimited optional-work budget: JIT and class-loading time must
     * not be mistaken for a planner failure. The budgeted production tick itself is covered by the Phase 4.1
     * budget tests; these tests cover the dispatch wiring.
     */
    private static void firstTick(CrystalCombatController controller) {
        controller.tick(1, TickBudget.unlimited());
    }

    private static CrystalCombatController controller(EngineHarness harness) {
        DirectEngineHost host = new DirectEngineHost(harness.runtime, () -> harness.world,
            harness.engine, harness.gateway);
        CrystalCombatController.Stack stack = new CrystalCombatController.Stack(harness.runtime,
            harness.session, harness.crystals, harness.scheduler, harness.budget, harness.client,
            harness.intake, host, new dev.tntcoating.core.combat.CombatHurtModel());
        return new CrystalCombatController(() -> null, () -> stack);
    }

    private static EngineHarness ready() {
        EngineHarness harness = EngineHarness.create(SELF);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        return harness;
    }

    @Test
    void switchingToPistonOnlyStopsTheDirectEngineAndSwitchingBackResumesIt() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        firstTick(controller);
        int afterDirect = harness.sink.attempts;

        controller.setMode(Mode.PISTON_ONLY);
        assertEquals(Mode.PISTON_ONLY, controller.mode());
        for (int tick = 2; tick <= 10; tick++) controller.tick(tick);
        assertEquals(afterDirect, harness.sink.attempts,
            "no direct-engine packet is sent after switching to PistonOnly");
        DirectSequence.State stopped = controller.stack().engineHost().sequenceState();
        assertTrue(stopped == DirectSequence.State.ABORTED || stopped == DirectSequence.State.IDLE,
            "the direct sequence is stopped, was " + stopped);

        controller.setMode(Mode.DIRECT_ONLY);
        assertEquals(Mode.DIRECT_ONLY, controller.mode());
        controller.setMode(Mode.HYBRID);
        assertEquals(Mode.HYBRID, controller.mode(), "serialised Hybrid is accepted in this build");
        controller.setMode(Mode.DIRECT_ONLY);
    }

    /**
     * Phase 6.3 evidence: the controller records the effective setting and active mode for the paired
     * serialised-vs-footprint-scoped table. Off is the shipped default, so the off bucket must be the one
     * that grows when the setting is untouched.
     */
    @Test
    void hybridRecordsTheConcurrencySettingAndModeForTheTesterTable() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        controller.setMode(Mode.HYBRID);
        assertTrue(controller.enable());
        firstTick(controller);
        controller.tick(2);

        assertEquals(1, controller.metrics().concurrencySetting(false),
            "the setting bucket is written once per change, and this run never enabled it");
        assertEquals(0, controller.metrics().concurrencySetting(true));
        assertTrue(controller.metrics().concurrencyMode("HYBRID") >= 1,
            "the active mode is recorded every Hybrid tick");
    }

    /**
     * Phase 6.4 stage 1: with no committed trap the Hybrid path is the direct engine's own dispatch path;
     * the trap stays disarmed by this stack (it has no trap controller) and nothing new sends.
     */
    @Test
    void hybridRunsTheDirectEngineWhileNoTrapIsCommitted() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        controller.setMode(Mode.HYBRID);
        assertTrue(controller.enable());
        firstTick(controller);

        assertEquals(Mode.HYBRID, controller.mode());
        assertEquals(1, harness.placeCount(), "Hybrid still dispatches the direct plan");
        assertEquals(0, harness.sink.count(dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink.Kind.PISTON),
            "no trap is committed, so no trap packet exists");
        assertFalse(controller.infoString().isEmpty(), "Hybrid publishes a visible status");
    }

    @Test
    void directOnlyPathPlacesThroughDispatchAndDisableStopsAllSends() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        assertNotNull(controller.stack().engineHost());
        assertTrue(CrystalCombatGate.suppressMeteor());

        firstTick(controller);
        assertEquals(1, harness.placeCount());
        assertEquals(0, harness.attackCount());

        controller.disable();
        assertFalse(CrystalCombatGate.suppressMeteor());
        for (int tick = 2; tick < 10; tick++) controller.tick(tick);
        assertEquals(1, harness.placeCount());
        assertEquals(0, harness.attackCount());
    }

    @Test
    void disableAfterASentAttackSendsNothingElseAndRetainsTheHazard() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        firstTick(controller);
        UUID uuid = UUID.randomUUID();
        harness.client.putCrystal(2005, uuid, CRYSTAL);
        controller.observeSpawn(new EntityKey(harness.connection, 2005, uuid), CRYSTAL,
            EngineHarness.END_CRYSTAL);
        assertEquals(1, harness.attackCount());
        HazardId hazard = harness.engine.sequence().hazard();
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();
        int attempts = harness.sink.attempts;

        controller.disable();
        for (int tick = 2; tick <= 10; tick++) controller.tick(tick);

        assertEquals(1, harness.attackCount(), "no attack after disable");
        assertEquals(attempts, harness.sink.attempts, "no packet attempt after disable");
        assertTrue(harness.crystals.unresolved(hazard), "disable never retires the sent hazard");
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk),
            "disable keeps the sent risk outstanding");
    }

    @Test
    void spawnIsAttackedInTheSameClientThreadTaskAsTheObservation() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        firstTick(controller);
        assertEquals(1, harness.placeCount());

        UUID uuid = UUID.randomUUID();
        int id = 2001;
        harness.client.putCrystal(id, uuid, CRYSTAL);
        EntityKey crystal = new EntityKey(harness.connection, id, uuid);
        controller.observeSpawn(crystal, CRYSTAL, EngineHarness.END_CRYSTAL);

        assertEquals(1, harness.attackCount());
        assertEquals(DirectSequence.State.ATTACK_SENT,
            controller.stack().engineHost().sequenceState());
    }

    @Test
    void pauseSendsNothingAndIsVisibleInTheWaitReason() {
        EngineHarness harness = ready();
        harness.world.eating = true;
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        for (int tick = 1; tick <= 5; tick++) controller.tick(tick);

        assertEquals(0, harness.placeCount());
        assertEquals(0, harness.attackCount());
        assertEquals(DirectPause.EATING, harness.engine.pause());
        assertEquals("paused: eating", controller.waitReason());
    }

    @Test
    void deathSuspendsTheEngineAndSendsNothingAfterwards() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        firstTick(controller);
        UUID uuid = UUID.randomUUID();
        int id = 2002;
        harness.client.putCrystal(id, uuid, CRYSTAL);
        EntityKey crystal = new EntityKey(harness.connection, id, uuid);
        controller.observeSpawn(crystal, CRYSTAL, EngineHarness.END_CRYSTAL);
        assertEquals(1, harness.attackCount());

        controller.onDeath();
        for (int tick = 2; tick <= 6; tick++) controller.tick(tick);

        assertEquals(1, harness.attackCount());
        assertEquals(DirectPause.SUSPENDED, harness.engine.pause());
    }

    @Test
    void respawnTransitionSendsNothing() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        controller.onRespawn();
        for (int tick = 1; tick <= 4; tick++) controller.tick(tick);

        assertEquals(0, harness.placeCount());
        assertEquals(0, harness.attackCount());
    }

    @Test
    void deathAbortsAPendingPlacementAndNeverAttacksTheLateSpawn() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        firstTick(controller);
        HazardId hazard = harness.engine.sequence().hazard();
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();

        controller.onDeath();
        assertEquals(DirectSequence.State.ABORTED,
            controller.stack().engineHost().sequenceState(), "death aborts synchronously");

        UUID uuid = UUID.randomUUID();
        harness.client.putCrystal(2003, uuid, CRYSTAL);
        controller.observeSpawn(new EntityKey(harness.connection, 2003, uuid), CRYSTAL,
            EngineHarness.END_CRYSTAL);
        for (int tick = 2; tick <= 5; tick++) controller.tick(tick);

        assertEquals(0, harness.attackCount(), "a spawn bound to a pre-death hazard is never attacked");
        assertEquals(1, harness.placeCount(), "no retry into the unresolved pre-death cell");
        assertTrue(harness.crystals.unresolved(hazard));
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk));
    }

    @Test
    void configChangeAbortsAPendingPlacementBeforeTheSpawnArrives() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        firstTick(controller);
        assertEquals(1, harness.placeCount());

        controller.onConfigChanged();
        assertEquals(DirectSequence.State.ABORTED,
            controller.stack().engineHost().sequenceState());

        UUID uuid = UUID.randomUUID();
        harness.client.putCrystal(2004, uuid, CRYSTAL);
        controller.observeSpawn(new EntityKey(harness.connection, 2004, uuid), CRYSTAL,
            EngineHarness.END_CRYSTAL);
        for (int tick = 2; tick <= 5; tick++) controller.tick(tick);

        assertEquals(0, harness.attackCount());
        assertEquals(1, harness.placeCount());
    }

    @Test
    void dimensionChangeAbortsExactlyOnceThroughTheGenerationCheck() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        firstTick(controller);
        assertEquals(1, harness.placeCount());

        harness.runtime.onDimensionChanged();
        controller.tick(2);
        assertEquals(1, harness.engine.generationAborts(),
            "the generation change aborts the pending sequence once");
        controller.tick(3);
        assertEquals(1, harness.engine.generationAborts(), "the next tick must not abort again");
        assertEquals(0, harness.attackCount());
        assertEquals(1, harness.placeCount());
    }

    @Test
    void connectionCloseAbortsAndSendsNothingAfterwards() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        firstTick(controller);
        assertEquals(1, harness.placeCount());

        controller.onLeft();
        assertEquals(DirectSequence.State.ABORTED,
            controller.stack().engineHost().sequenceState());

        for (int tick = 2; tick <= 5; tick++) controller.tick(tick);
        assertEquals(0, harness.attackCount());
        assertEquals(1, harness.placeCount());
    }

    @Test
    void reEnableNeverAttacksASpawnBoundBeforeTheDisable() {
        EngineHarness harness = ready();
        CrystalCombatController controller = controller(harness);
        assertTrue(controller.enable());
        firstTick(controller);
        HazardId hazard = harness.engine.sequence().hazard();
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();

        controller.disable();
        assertTrue(controller.enable());
        UUID uuid = UUID.randomUUID();
        harness.client.putCrystal(2005, uuid, CRYSTAL);
        EntityKey crystal = new EntityKey(harness.connection, 2005, uuid);
        controller.observeSpawn(crystal, CRYSTAL, EngineHarness.END_CRYSTAL);
        for (int tick = 2; tick <= 5; tick++) controller.tick(tick);

        assertEquals(0, harness.attackCount(), "re-enable must not attack the pre-disable spawn");
        assertEquals(1, harness.placeCount());
        assertTrue(harness.crystals.unresolved(hazard));
        assertTrue(harness.crystals.own(crystal));
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk));
    }
}
