/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Phase 4.3 pure hurt-sync arithmetic and timing. */
final class HurtSyncTest {
    private static final ConnectionKey CONN = new ConnectionKey(7L);
    private static final EntityKey TARGET =
        new EntityKey(CONN, 42, UUID.fromString("00000000-0000-0000-0000-000000000042"));
    private static final MitigationState NO_MITIGATION = new MitigationState(
        CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0, false, true, false);
    private static final EffectiveHealth HEALTH_20 =
        new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L);

    private static RawDamage raw(double value) {
        return new RawDamage(value);
    }

    private static TargetEstimate estimate(CombatHurtModel model, double lower, double expected,
                                           double optimistic, Arrival arrival) {
        return HurtSync.estimate(model, TARGET, raw(lower), raw(expected), raw(optimistic),
            NO_MITIGATION, NO_MITIGATION, arrival);
    }

    @Test
    void unknownPriorHurtKeepsTheExpectedValueAndZerosTheGuaranteedLower() {
        CombatHurtModel model = new CombatHurtModel();
        TargetEstimate phase3 = CombatDamage.targetEstimate(raw(12), raw(20), raw(30),
            NO_MITIGATION, NO_MITIGATION);

        TargetEstimate adjusted = estimate(model, 12, 20, 30, HurtSync.immediateArrival(100));

        assertEquals(0.0, adjusted.guaranteedLower(), "an unobserved prior hit cannot guarantee anything");
        assertEquals(phase3.expected(), adjusted.expected(), "expected damage is unchanged");
        assertEquals(phase3.optimistic(), adjusted.optimistic(), "optimistic damage is unchanged");
        assertEquals(HurtSync.NO_DEFER, HurtSync.deferUntil(model, TARGET, raw(12), raw(20), raw(30),
            NO_MITIGATION, NO_MITIGATION, HurtSync.immediateArrival(100), 100, adjusted, HEALTH_20));
    }

    @Test
    void missingModelProducesTheExactPhase3Estimate() {
        TargetEstimate phase3 = CombatDamage.targetEstimate(raw(12), raw(20), raw(30),
            NO_MITIGATION, NO_MITIGATION);
        TargetEstimate adjusted = estimate(null, 12, 20, 30, new Arrival(100, 102));
        assertEquals(phase3, adjusted);
    }

    @Test
    void strongerFollowUpInsideTheWindowOnlyGuaranteesTheIncrement() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(TARGET, 8.0, Arrival.at(100));

        TargetEstimate adjusted = estimate(model, 12, 20, 30, Arrival.at(105));

        assertEquals(4.0, adjusted.guaranteedLower(), 1e-9,
            "only the amount above the prior hit is guaranteed");
        assertEquals(12.0, adjusted.expected(), 1e-9);
        assertEquals(22.0, adjusted.optimistic(), 1e-9);
    }

    @Test
    void weakerFollowUpInsideTheWindowDoesNoDamage() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(TARGET, 30.0, Arrival.at(100));

        TargetEstimate adjusted = estimate(model, 12, 20, 30, Arrival.at(105));

        assertEquals(0.0, adjusted.guaranteedLower(), 1e-9,
            "a larger prior hit may still be inside its window, so nothing is guaranteed");
        assertEquals(0.0, adjusted.expected(), 1e-9);
        assertEquals(0.0, adjusted.optimistic(), 1e-9);
    }

    @Test
    void windowBoundarySeparatesInsideAtPlusNineFromExpiryAtPlusTen() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(TARGET, 8.0, Arrival.at(100));

        TargetEstimate inside = estimate(model, 12, 20, 30, Arrival.at(109));
        assertEquals(4.0, inside.guaranteedLower(), 1e-9);
        assertEquals(12.0, inside.expected(), 1e-9);

        TargetEstimate expired = estimate(model, 12, 20, 30, Arrival.at(110));
        assertEquals(12.0, expired.guaranteedLower(), 1e-9, "expiry restores the full raw damage");
        assertEquals(20.0, expired.expected(), 1e-9);
        assertEquals(30.0, expired.optimistic(), 1e-9);

        TargetEstimate straddle = estimate(model, 12, 20, 30, new Arrival(109, 112));
        assertEquals(0.0, straddle.guaranteedLower(), 1e-9,
            "an arrival that straddles the edge can never guarantee a positive lower bound");
        assertTrue(straddle.expected() > 0.0 && straddle.expected() < 20.0,
            "a straddling arrival keeps a range, not a point: " + straddle.expected());
    }

    @Test
    void arrivalWholelyBeforeTheRecordedHitIsUnknownPriorHurt() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(TARGET, 8.0, Arrival.at(100));

        TargetEstimate adjusted = estimate(model, 12, 20, 30, Arrival.at(50));

        assertEquals(0.0, adjusted.guaranteedLower(), 1e-9);
        assertEquals(20.0, adjusted.expected(), 1e-9);
    }

    @Test
    void immediateArrivalSequenceIsExpressedInServerTicks() {
        assertEquals(new Arrival(8L, 10L), HurtSync.immediateArrival(7L));
        assertEquals(new Arrival(110L, 112L), HurtSync.arrivalFrom(109L));
        assertEquals(110L, HurtSync.expiryTick(WindowState.of(Arrival.at(100), 8.0)));
    }

    @Test
    void benefitialDelayWaitsJustAfterTheWindowEnds() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(TARGET, 30.0, Arrival.at(100));
        Arrival immediateArrival = HurtSync.immediateArrival(100);
        TargetEstimate immediate = estimate(model, 10, 40, 40, immediateArrival);
        assertEquals(10.0, immediate.expected(), 1e-9, "the window suppresses all but the increment");

        long defer = HurtSync.deferUntil(model, TARGET, raw(10), raw(40), raw(40),
            NO_MITIGATION, NO_MITIGATION, immediateArrival, 100, immediate, HEALTH_20);

        assertEquals(109L, defer, "deferral ends one tick before expiry, so the send lands after it");
        TargetEstimate delayed = estimate(model, 10, 40, 40, HurtSync.arrivalFrom(defer));
        assertEquals(40.0, delayed.expected(), 1e-9);
        assertTrue(delayed.expected() > immediate.expected(), "the gain is what justifies waiting");
    }

    @Test
    void lethalImmediateLowerBoundNeverWaits() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(TARGET, 5.0, Arrival.at(100));
        Arrival immediateArrival = HurtSync.immediateArrival(100);
        TargetEstimate immediate = estimate(model, 20, 20, 20, immediateArrival);
        assertEquals(15.0, immediate.guaranteedLower(), 1e-9);
        EffectiveHealth lowHealth = new EffectiveHealth(10.0, 0.0, Capability.KNOWN, 0L);

        assertEquals(HurtSync.NO_DEFER, HurtSync.deferUntil(model, TARGET, raw(20), raw(20), raw(20),
            NO_MITIGATION, NO_MITIGATION, immediateArrival, 100, immediate, lowHealth));
    }

    @Test
    void deferralIsBoundedByTheTwentyTickHorizon() {
        CombatHurtModel model = new CombatHurtModel();
        // A wide observation interval keeps the window edge 21 ticks away from one planning tick and
        // 20 ticks away from the next one.
        model.observeHit(TARGET, 30.0, new Arrival(100L, 120L));

        Arrival atTwenty = HurtSync.immediateArrival(109);
        TargetEstimate immediate = estimate(model, 10, 40, 40, atTwenty);
        assertEquals(10.0, immediate.expected(), 1e-9, "the arrival is wholly inside the window");
        assertEquals(129L, HurtSync.deferUntil(model, TARGET, raw(10), raw(40), raw(40),
            NO_MITIGATION, NO_MITIGATION, atTwenty, 109, immediate, HEALTH_20),
            "twenty ticks ahead is still inside the horizon");

        Arrival atTwentyOne = HurtSync.immediateArrival(108);
        TargetEstimate earlyEstimate = estimate(model, 10, 40, 40, atTwentyOne);
        assertEquals(HurtSync.NO_DEFER, HurtSync.deferUntil(model, TARGET, raw(10), raw(40), raw(40),
            NO_MITIGATION, NO_MITIGATION, atTwentyOne, 108, earlyEstimate, HEALTH_20),
            "twenty-one ticks ahead exceeds the horizon");
    }

    @Test
    void anAlreadyExpiredArrivalNeverWaits() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(TARGET, 30.0, Arrival.at(100));
        Arrival immediateArrival = HurtSync.immediateArrival(110);
        TargetEstimate immediate = estimate(model, 10, 40, 40, immediateArrival);

        assertEquals(HurtSync.NO_DEFER, HurtSync.deferUntil(model, TARGET, raw(10), raw(40), raw(40),
            NO_MITIGATION, NO_MITIGATION, immediateArrival, 110, immediate, HEALTH_20));
    }

    @Test
    void unknownWindowNeverDefers() {
        CombatHurtModel model = new CombatHurtModel();
        TargetEstimate immediate = new TargetEstimate(0.0, 10.0, 12.0);
        assertEquals(HurtSync.NO_DEFER, HurtSync.deferUntil(model, TARGET, raw(10), raw(40), raw(40),
            NO_MITIGATION, NO_MITIGATION, HurtSync.immediateArrival(100), 100, immediate, HEALTH_20));
    }

    @Test
    void unknownTargetHealthCapabilityCannotClaimLethality() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(TARGET, 5.0, Arrival.at(100));
        Arrival immediateArrival = HurtSync.immediateArrival(100);
        TargetEstimate immediate = estimate(model, 20, 40, 40, immediateArrival);
        EffectiveHealth unknown = new EffectiveHealth(10.0, 0.0, Capability.UNKNOWN, 0L);

        assertEquals(109L, HurtSync.deferUntil(model, TARGET, raw(20), raw(40), raw(40),
            NO_MITIGATION, NO_MITIGATION, immediateArrival, 100, immediate, unknown),
            "without a known health upper bound the lethal guard has no evidence to act on");
    }

    @Test
    void noGainMeansNoDeferral() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(TARGET, 30.0, Arrival.at(100));
        Arrival immediateArrival = HurtSync.immediateArrival(100);
        TargetEstimate immediate = estimate(model, 10, 40, 40, immediateArrival);
        // Deliberately claim the immediate detonation is already the full amount: waiting cannot beat it.
        TargetEstimate inflated = new TargetEstimate(immediate.guaranteedLower(), 40.0, 40.0);

        assertEquals(HurtSync.NO_DEFER, HurtSync.deferUntil(model, TARGET, raw(10), raw(40), raw(40),
            NO_MITIGATION, NO_MITIGATION, immediateArrival, 100, inflated, HEALTH_20));
    }
}
