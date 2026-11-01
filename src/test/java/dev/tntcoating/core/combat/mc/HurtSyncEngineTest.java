/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatEngine;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.DirectPause;
import dev.tntcoating.core.combat.DirectSequence;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.HazardId;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.Test;

/**
 * Phase 4.3 end-to-end timing on the fake world/connection harness. A wait is not an action: it sends
 * nothing, leaves hazards/risk/sequence untouched, and the single dispatch path sends exactly once.
 */
class HurtSyncEngineTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);
    private static final Cell BASE = new Cell(2, -61, 0);
    private static final Vec CRYSTAL_POSITION = new Vec(2.5, -60.0, 0.5);
    private static final Vec TARGET = new Vec(2.5, -60.0, 3.0);

    private static EngineHarness harness(boolean hurtSync) {
        DirectSettings settings = DirectSettings.conservative(CombatDamagePolicy.conservativeSafety())
            .withHurtSync(hurtSync);
        EngineHarness harness = EngineHarness.create(settings, SELF);
        harness.setBase(BASE);
        harness.addTarget(TARGET);
        return harness;
    }

    @Test
    void deferralSendsNothingAndKeepsHazardRiskAndSequence() {
        EngineHarness harness = harness(true);
        harness.setHurtSyncDeferUntil(tick -> tick < 5 ? 5L : -1L);
        harness.tick();
        assertEquals(1, harness.placeCount());
        HazardId hazard = harness.engine.sequence().hazard();
        RiskId risk = harness.scheduler.riskId(CombatEngine.DIRECT_PLAN, 1L).orElseThrow();

        EntityKey crystal = harness.spawnCrystal(CRYSTAL_POSITION);

        assertEquals(0, harness.attackCount(), "a hurt-sync wait sends nothing");
        assertEquals(DirectPause.HURT_SYNC, harness.engine.pause());
        assertEquals(DirectSequence.State.SPAWN_OBSERVED, harness.engine.sequence().state(),
            "the sequence stays on the same observed spawn");
        assertTrue(harness.crystals.unresolved(hazard), "the spawn hazard stays reserved");
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk),
            "the sent risk stays booked");

        harness.engine.onSpawnObserved(crystal, harness.world, harness.snapshot(), harness.gateway);
        harness.engine.onSpawnObserved(crystal, harness.world, harness.snapshot(), harness.gateway);
        assertEquals(0, harness.attackCount(), "the wait tolerates duplicate spawn events");

        harness.tick(4);
        assertEquals(1, harness.attackCount(), "the attack is sent once the wait reaches its tick");
        assertEquals(DirectSequence.State.ATTACK_SENT, harness.engine.sequence().state());
        assertTrue(harness.crystals.unresolved(hazard), "the hazard is still the placement's");
        assertEquals(CombatRiskBudget.State.SENT, harness.budget.state(risk));

        harness.engine.onSpawnObserved(crystal, harness.world, harness.snapshot(), harness.gateway);
        assertEquals(1, harness.attackCount(), "the event path cannot duplicate the tick send");
    }

    @Test
    void offIsExactlyPhase3AndNeverConsultsTheWaitDecision() {
        EngineHarness harness = harness(false);
        harness.setHurtSyncDeferUntil(tick -> 99L);

        harness.tick();
        EntityKey crystal = harness.spawnCrystal(CRYSTAL_POSITION);

        assertEquals(1, harness.attackCount(), "hurt-sync off attacks on the spawn event as Phase 3 did");
        assertNotEquals(DirectPause.HURT_SYNC, harness.engine.pause());
        assertEquals(DirectSequence.State.ATTACK_SENT, harness.engine.sequence().state());
        assertEquals(1, harness.engine.attempts(crystal));
    }

    @Test
    void unknownWindowDoesNotDefer() {
        EngineHarness harness = harness(true);

        harness.tick();
        EntityKey crystal = harness.spawnCrystal(CRYSTAL_POSITION);

        assertEquals(1, harness.attackCount(), "an unfed hurt model is exactly the Phase 3 timing");
        assertNotEquals(DirectPause.HURT_SYNC, harness.engine.pause());
    }

    @Test
    void hurtSyncNeverChangesSelfOrFriendBounds() {
        EngineHarness off = harness(false);
        EngineHarness on = harness(true);
        on.setHurtSyncDeferUntil(tick -> tick < 5 ? 5L : -1L);
        EntityKey friend = on.addFriend(new Vec(0.5, -60.0, 3.0), 20.0);
        on.setFriendUpper(friend, 1.0);

        off.tick();
        on.tick();

        assertEquals(off.placeCount(), on.placeCount(),
            "the same placement is allowed; hurt-sync does not touch the friend bound");
        assertEquals(off.sink.attempts, on.sink.attempts);
    }
}
