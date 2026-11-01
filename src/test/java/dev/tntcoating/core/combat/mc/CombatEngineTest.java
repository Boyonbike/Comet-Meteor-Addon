/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.CrystalState;
import dev.tntcoating.core.combat.DirectPause;
import dev.tntcoating.core.combat.DirectSequence;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.HazardId;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import org.junit.jupiter.api.Test;

/** Engine-level Phase 3.4/3.6 behaviour on the fake world/connection harness. */
class CombatEngineTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);
    private static final Cell BASE = new Cell(2, -61, 0);
    private static final Cell CRYSTAL_CELL = new Cell(2, -60, 0);
    private static final Vec CRYSTAL_POSITION = new Vec(2.5, -60.0, 0.5);

    private static EngineHarness harness() {
        EngineHarness harness = EngineHarness.create(SELF);
        harness.setBase(BASE);
        harness.addTarget(new Vec(2.5, -60.0, 3.0));
        return harness;
    }

    @Test
    void placesOnceThenSpawnsAndBreaksAndRetiresTheRiskOnRemovalEvidence() {
        EngineHarness harness = harness();
        harness.tick();

        assertEquals(1, harness.placeCount(), "one placement per tick, through dispatch only");
        assertEquals(1, harness.engine.placementsSent());
        HazardId hazard = harness.engine.sequence().hazard();
        assertNotNull(hazard);
        assertTrue(harness.crystals.unresolved(hazard));
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();
        assertNotNull(risk);
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk));

        EntityKey crystal = harness.spawnCrystal(CRYSTAL_POSITION);
        assertEquals(1, harness.attackCount(), "the unique spawn is attacked in the same task");
        assertEquals(FakeSink.Kind.ATTACK, harness.sink.last(FakeSink.Kind.ATTACK).kind());
        assertEquals(crystal.id(), harness.sink.last(FakeSink.Kind.ATTACK).number());
        assertEquals(1, harness.engine.attempts(crystal));
        assertEquals(DirectSequence.State.ATTACK_SENT, harness.engine.sequence().state());
        assertEquals(harness.connection, harness.budget.boundCrystal(risk).orElseThrow().conn());

        harness.removeCrystal(crystal);
        harness.tick();
        assertEquals(DirectSequence.State.IDLE, harness.engine.sequence().state());

        harness.explosion(crystal, CRYSTAL_POSITION);
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk),
            "explosion alone does not retire the risk");
        harness.selfHealth(19.0, 0.0);
        assertEquals(CombatRiskBudget.State.RETIRED, harness.budget.state(risk));
    }

    @Test
    void rejectedSpawnKeepsHazardAndRiskAndNeverRetriesThatCell() {
        EngineHarness harness = harness();
        harness.tick();
        assertEquals(1, harness.placeCount());
        HazardId hazard = harness.engine.sequence().hazard();
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();

        harness.tick(40);

        assertEquals(1, harness.placeCount(), "no retry into the unresolved cell");
        assertEquals(0, harness.attackCount());
        assertTrue(harness.crystals.unresolved(hazard));
        assertTrue(harness.crystals.blocksCell(CRYSTAL_CELL));
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk));
        assertEquals(DirectSequence.State.PLACE_SENT, harness.engine.sequence().state());
    }

    @Test
    void pauseBeforeAnyWorkSendsNothing() {
        EngineHarness harness = harness();
        harness.world.eating = true;
        harness.tick(5);
        assertEquals(0, harness.sink.attempts);
        assertEquals(DirectPause.EATING, harness.engine.pause());
        assertTrue(harness.engine.sequence().isIdle());
    }

    @Test
    void pauseKeepsSentHazardAndRiskAndAttacksAfterItLifts() {
        EngineHarness harness = harness();
        harness.tick();
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();
        HazardId hazard = harness.engine.sequence().hazard();
        harness.world.screenOpen = true;
        harness.tick(3);
        assertEquals(1, harness.placeCount());
        assertEquals(0, harness.attackCount());
        assertTrue(harness.crystals.unresolved(hazard));
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk));

        harness.world.screenOpen = false;
        EntityKey crystal = harness.spawnCrystal(CRYSTAL_POSITION);
        assertEquals(1, harness.attackCount(), "the break happens after the pause lifts");
        assertEquals(crystal.id(), harness.sink.last(FakeSink.Kind.ATTACK).number());
    }

    @Test
    void disabledEngineSendsNothingEvenWhenAnAttackIsReady() {
        EngineHarness harness = harness();
        harness.tick();
        harness.engine.onDisabled();
        harness.world.screenOpen = false;
        harness.spawnCrystal(CRYSTAL_POSITION);
        harness.tick(5);
        assertEquals(0, harness.attackCount());
        assertEquals(1, harness.placeCount());
    }

    @Test
    void tickAndEventInTheSameTickSendAtMostOneAttack() {
        EngineHarness harness = harness();
        harness.tick();
        EntityKey crystal = harness.spawnCrystal(CRYSTAL_POSITION);
        assertEquals(1, harness.attackCount());
        int sameTick = harness.serverTick;
        harness.engine.onSpawnObserved(crystal, harness.world, harness.snapshot(), harness.gateway);
        harness.runtime.tick(sameTick);
        harness.engine.tick(sameTick, harness.world, harness.snapshot(), harness.gateway);
        assertEquals(1, harness.attackCount(), "event and tick paths share the per-entity guard");
    }

    @Test
    void breakAttemptCapStopsAfterTwoSends() {
        EngineHarness harness = harness();
        harness.tick();
        EntityKey crystal = harness.spawnCrystal(CRYSTAL_POSITION);
        assertEquals(1, harness.attackCount());
        harness.tick();
        assertEquals(2, harness.attackCount(), "second attempt is allowed");
        harness.tick(5);
        assertEquals(2, harness.attackCount(), "attempts are capped at two");
        assertEquals(2, harness.engine.attempts(crystal));
    }

    @Test
    void highSelfDamageRejectsThePlacement() {
        EngineHarness harness = harness();
        harness.world.selfUpper = new dev.tntcoating.core.combat.MitigatedDamage(7.0);
        harness.tick(3);
        assertEquals(0, harness.sink.attempts);
        assertTrue(harness.engine.sequence().isIdle());
    }

    @Test
    void friendUpperAboveCeilingRejectsThePlacement() {
        EngineHarness harness = harness();
        EntityKey friend = harness.addFriend(new Vec(2.5, -60.0, 1.0), 20.0);
        harness.setFriendUpper(friend, 2.5);
        harness.tick(3);
        assertEquals(0, harness.sink.attempts);
    }

    @Test
    void tinySelfHealthFloorPauses() {
        EngineHarness low = EngineHarness.create(SELF);
        low.setBase(BASE);
        low.addTarget(new Vec(2.5, -60.0, 3.0));
        low.world.selfHealth = 3.0;
        low.tick(3);
        assertEquals(0, low.sink.attempts, "health floor stops all work");
        assertEquals(DirectPause.HEALTH, low.engine.pause());
    }

    @Test
    void itemEntityInThePlacementVolumeRejectsTheCell() {
        EngineHarness harness = harness();
        harness.client.putEntity(77, new java.util.UUID(0L, 77L), "minecraft:item",
            new Vec(2.5, -59.5, 0.5),
            new dev.tntcoating.core.combat.Box(new Vec(2.1, -60.0, 0.1), new Vec(2.9, -59.4, 0.9)));
        harness.tick(2);
        assertEquals(0, harness.sink.attempts, "an item entity in the 1x2x1 volume rejects the placement");
        assertTrue(harness.engine.sequence().isIdle());
    }

    @Test
    void generationBoundaryAbortsTheSequenceExactlyOnceAndKeepsHazardAndRisk() {
        EngineHarness harness = harness();
        harness.tick();
        HazardId hazard = harness.engine.sequence().hazard();
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();

        // Death, respawn, config, dimension and connection close all reach this same engine boundary.
        harness.engine.onGenerationEnded();
        assertEquals(DirectSequence.State.ABORTED, harness.engine.sequence().state());
        assertEquals(1, harness.engine.generationAborts());
        harness.engine.onGenerationEnded();
        assertEquals(1, harness.engine.generationAborts(), "one abort per boundary, not one per call");

        harness.tick(4);
        assertEquals(0, harness.attackCount());
        assertEquals(1, harness.placeCount());
        assertTrue(harness.crystals.unresolved(hazard), "the spawn hazard survives the boundary");
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk),
            "the sent risk debt survives the boundary");
    }

    @Test
    void reEnableNeverAttacksASpawnBoundToAPreDisableHazard() {
        EngineHarness harness = harness();
        harness.tick();
        HazardId hazard = harness.engine.sequence().hazard();
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();

        harness.engine.onDisabled();
        harness.engine.onEnabled();
        EntityKey crystal = harness.spawnCrystal(CRYSTAL_POSITION);
        assertEquals(0, harness.attackCount(), "the pre-disable hazard is not attacked after re-enable");

        harness.tick(4);
        assertEquals(0, harness.attackCount());
        assertEquals(1, harness.placeCount(), "no retry into the unresolved cell");
        assertTrue(harness.crystals.unresolved(hazard));
        assertTrue(harness.crystals.own(crystal));
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk));
    }

    @Test
    void aClosedSessionNeverSendsFromStaleEngineState() {
        EngineHarness harness = harness();
        harness.tick();
        harness.engine.onGenerationEnded();
        harness.session.close();

        harness.engine.tick(harness.serverTick + 1, harness.world, harness.snapshot(), harness.gateway);
        harness.engine.onSpawnObserved(harness.self(), harness.world, harness.snapshot(), harness.gateway);

        assertEquals(0, harness.attackCount());
        assertEquals(1, harness.placeCount());
    }

    @Test
    void spawnEventForcesTheOwnedCrystalBreakOverAHigherScoringPlacement() {
        EngineHarness harness = harness();
        harness.tick();
        assertEquals(1, harness.placeCount());
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();

        // A fresh obsidian base whose placement would outscore the owned-crystal break.
        harness.setBase(new Cell(0, -61, 2));
        harness.setEstimateAt(centre -> centre.equals(new Vec(0.5, -60.0, 2.5))
            ? new dev.tntcoating.core.combat.TargetEstimate(4.0, 20.0, 25.0)
            : new dev.tntcoating.core.combat.TargetEstimate(4.0, 10.0, 12.0));

        EntityKey crystal = harness.spawnCrystal(CRYSTAL_POSITION);
        assertEquals(1, harness.attackCount(),
            "the spawn event forces the owned-crystal break, not the higher-scoring placement");
        assertEquals(1, harness.placeCount());
        assertEquals(DirectSequence.State.ATTACK_SENT, harness.engine.sequence().state());
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk));
        assertEquals(0, harness.budget.neverRetirableComponents(),
            "the attack reuses the crystal risk, so its bound is the spawn's");
        assertEquals(1, harness.engine.attempts(crystal));

        harness.tick();
        assertEquals(1, harness.attackCount(), "the next tick does not duplicate the event attack");
    }
}
