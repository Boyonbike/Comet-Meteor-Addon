/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.DirectSequence;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.HazardId;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import org.junit.jupiter.api.Test;

/** End-to-end DIRECT engine through the real runtime, scheduler, ledger and risk budget. */
final class CombatEngineTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);
    private static final Vec BASE = new Vec(2.0, -61.0, 0.0);
    private static final Vec CRYSTAL = new Vec(2.5, -60.0, 0.5);
    private static final Vec TARGET = new Vec(2.5, -60.0, 3.0);

    private static EngineHarness harness() {
        EngineHarness harness = EngineHarness.create(SELF);
        harness.setBase(EngineHarness.cellOf(BASE));
        harness.addTarget(TARGET);
        return harness;
    }

    @Test
    void placementSendsOneCrystalAndHoldsAnUnresolvedHazard() {
        EngineHarness harness = harness();
        harness.tick();
        assertEquals(1, harness.placeCount());
        assertEquals(1, harness.engine.placementsSent());
        assertEquals(DirectSequence.State.PLACE_SENT, harness.engine.sequence().state());
        HazardId hazard = harness.engine.sequence().hazard();
        assertNotNull(hazard);
        assertTrue(harness.crystals.unresolved(hazard));
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk));
        assertTrue(harness.crystals.blocksCell(cellOf(CRYSTAL)));
    }

    @Test
    void spawnObservedAttackUsesTheRealEntityId() {
        EngineHarness harness = harness();
        harness.tick();
        EntityKey crystal = harness.spawnCrystal(CRYSTAL);
        assertEquals(1, harness.attackCount());
        assertEquals(crystal.id(), harness.sink.last(FakeSink.Kind.ATTACK).number());
        assertEquals(1, harness.engine.attempts(crystal));
        assertEquals(DirectSequence.State.ATTACK_SENT, harness.engine.sequence().state());
    }

    @Test
    void removalEndsTheSequenceWithoutRetiringRisk() {
        EngineHarness harness = harness();
        harness.tick();
        EntityKey crystal = harness.spawnCrystal(CRYSTAL);
        harness.tick();
        harness.removeCrystal(crystal);
        harness.tick();
        assertTrue(harness.engine.sequence().isIdle());
    }

    @Test
    void explosionAloneDoesNotRetireButALaterHealthSnapshotDoes() {
        EngineHarness harness = harness();
        harness.tick();
        EntityKey crystal = harness.spawnCrystal(CRYSTAL);
        harness.tick();
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();
        harness.explosion(crystal, CRYSTAL);
        harness.budget.tick(harness.serverTick);
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk));
        harness.selfHealth(19.0, 0.0);
        harness.budget.tick(harness.serverTick);
        assertEquals(CombatRiskBudget.State.RETIRED, harness.budget.state(risk));
    }

    @Test
    void rejectedSpawnIsNotRetriedWithinTheQuarantine() {
        EngineHarness harness = harness();
        harness.tick();
        harness.tick(40);
        assertEquals(1, harness.placeCount());
        assertEquals(0, harness.attackCount());
        HazardId hazard = harness.engine.sequence().hazard();
        assertNotNull(hazard);
        assertTrue(harness.crystals.unresolved(hazard));
        assertTrue(harness.crystals.blocksCell(CombatEngineTest.cellOf(CRYSTAL)));
    }

    @Test
    void pausePreventsEveryAttemptAndLiftsWithTheCause() {
        EngineHarness harness = harness();
        harness.world.eating = true;
        harness.tick();
        assertEquals(0, harness.placeCount());
        assertNotNull(harness.engine.pause());
        assertEquals(dev.tntcoating.core.combat.DirectPause.EATING, harness.engine.pause());
        harness.world.eating = false;
        harness.tick();
        assertEquals(1, harness.placeCount());
    }

    @Test
    void disabledEngineNeverActs() {
        EngineHarness harness = harness();
        harness.engine.onDisabled();
        harness.tick();
        assertEquals(0, harness.placeCount());
        assertEquals(0, harness.attackCount());
        assertNull(harness.engine.sequence().hazard());
    }

    @Test
    void oneSpawnAfterATickSendsAtMostOneAttack() {
        EngineHarness harness = harness();
        harness.tick();
        harness.spawnCrystal(CRYSTAL);
        harness.tick();
        harness.spawnCrystal(new Vec(2.5, -60.0, 0.5));
        harness.tick();
        assertTrue(harness.attackCount() <= 2);
    }

    static dev.tntcoating.core.combat.Cell cellOf(Vec position) {
        return EngineHarness.cellOf(position);
    }
}
