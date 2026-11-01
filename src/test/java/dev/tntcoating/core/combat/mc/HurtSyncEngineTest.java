/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Arrival;
import dev.tntcoating.core.combat.CombatDifficulty;
import dev.tntcoating.core.combat.CombatHurtModel;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.HurtSync;
import dev.tntcoating.core.combat.MitigationState;
import dev.tntcoating.core.combat.RawDamage;
import dev.tntcoating.core.combat.TargetEstimate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The hurt window: an unknown prior never grants a lower bound, and a known window becomes one. */
final class HurtSyncEngineTest {
    private static final MitigationState MITIGATION = new MitigationState(CombatDifficulty.NORMAL,
        0, 0, 0, 0, false, false, false);

    private static EntityKey target() {
        return new EntityKey(new ConnectionKey(1), 200, UUID.randomUUID());
    }

    @Test
    void unknownPriorKeepsTheGuaranteedLowerAtZero() {
        CombatHurtModel model = new CombatHurtModel();
        TargetEstimate estimate = HurtSync.estimate(model, target(), new RawDamage(6.0),
            new RawDamage(6.0), new RawDamage(12.0), MITIGATION, MITIGATION,
            HurtSync.immediateArrival(10));
        assertEquals(0.0, estimate.guaranteedLower(), 1.0e-9);
        assertTrue(estimate.expected() > 0.0);
    }

    @Test
    void aKnownWindowWhollyAfterExpiryGrantsTheFullLowerBound() {
        CombatHurtModel model = new CombatHurtModel();
        EntityKey target = target();
        model.observeHit(target, 6.0, HurtSync.immediateArrival(10));
        TargetEstimate estimate = HurtSync.estimate(model, target, new RawDamage(6.0),
            new RawDamage(6.0), new RawDamage(12.0), MITIGATION, MITIGATION,
            new Arrival(40, 42));
        assertTrue(estimate.guaranteedLower() > 0.0);
        assertTrue(estimate.guaranteedLower() <= estimate.expected() + 1.0e-9);
    }

    @Test
    void aWeakerInWindowHitGrantsNoGuaranteedComponent() {
        CombatHurtModel model = new CombatHurtModel();
        EntityKey target = target();
        model.observeHit(target, 12.0, new Arrival(11, 12));
        TargetEstimate estimate = HurtSync.estimate(model, target, new RawDamage(3.0),
            new RawDamage(3.0), new RawDamage(6.0), MITIGATION, MITIGATION, new Arrival(13, 14));
        assertEquals(0.0, estimate.guaranteedLower(), 1.0e-9);
    }

    @Test
    void deferUntilWaitsOnlyForAWindowThatExpiresInsideTheHorizon() {
        CombatHurtModel model = new CombatHurtModel();
        EntityKey target = target();
        model.observeHit(target, 6.0, HurtSync.immediateArrival(10));
        EffectiveHealth health = new EffectiveHealth(20.0, 0.0,
            dev.tntcoating.core.combat.Capability.KNOWN, 0L);
        TargetEstimate immediate = HurtSync.estimate(model, target, new RawDamage(6.0),
            new RawDamage(6.0), new RawDamage(12.0), MITIGATION, MITIGATION,
            HurtSync.immediateArrival(10));
        long defer = HurtSync.deferUntil(model, target, new RawDamage(6.0), new RawDamage(6.0),
            new RawDamage(12.0), MITIGATION, MITIGATION, HurtSync.immediateArrival(10), 12,
            immediate, health);
        assertNotEquals(HurtSync.NO_DEFER, defer);
        assertTrue(defer >= 12);
    }

    @Test
    void unknownWindowNeverDefers() {
        CombatHurtModel model = new CombatHurtModel();
        EffectiveHealth health = new EffectiveHealth(20.0, 0.0,
            dev.tntcoating.core.combat.Capability.KNOWN, 0L);
        assertEquals(HurtSync.NO_DEFER, HurtSync.deferUntil(model, target(),
            new RawDamage(6.0), new RawDamage(6.0), new RawDamage(12.0), MITIGATION, MITIGATION,
            HurtSync.immediateArrival(10), 10, new TargetEstimate(0.0, 6.0, 12.0), health));
    }

    @Test
    void invalidRawDamageIsRejected() {
        CombatHurtModel model = new CombatHurtModel();
        try {
            model.observeHit(target(), -1.0, HurtSync.immediateArrival(0));
            throw new AssertionError("negative raw damage accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("rawDamage"));
        }
    }
}
