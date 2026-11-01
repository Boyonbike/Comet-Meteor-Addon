/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatHurtModelTest {
    private static final double EPS = 1.0e-9;
    private static final EntityKey VICTIM = new EntityKey(new ConnectionKey(1), 7,
        UUID.nameUUIDFromBytes("victim".getBytes(StandardCharsets.UTF_8)));

    @Test
    void unknownPriorHurtHasAZeroGuaranteedLowerBound() {
        CombatHurtModel model = new CombatHurtModel();
        HurtEstimate estimate = model.effectiveRaw(VICTIM, 10.0, Arrival.at(100));
        assertEquals(0.0, estimate.guaranteedLower(), EPS);
        assertEquals(10.0, estimate.expected(), EPS);
    }

    @Test
    void strongerFollowUpInsideTheWindowAppliesOnlyTheIncrement() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(VICTIM, 8.0, Arrival.at(100));

        HurtEstimate stronger = model.effectiveRaw(VICTIM, 10.0, Arrival.at(105));
        assertEquals(2.0, stronger.guaranteedLower(), EPS);
        assertEquals(2.0, stronger.expected(), EPS);
    }

    @Test
    void equalAndWeakerFollowUpsInsideTheWindowApplyNothing() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(VICTIM, 8.0, Arrival.at(100));

        assertEquals(0.0, model.effectiveRaw(VICTIM, 8.0, Arrival.at(105)).expected(), EPS);
        assertEquals(0.0, model.effectiveRaw(VICTIM, 6.0, Arrival.at(105)).expected(), EPS);
    }

    @Test
    void afterTheWindowExpiresTheFullRawValueApplies() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(VICTIM, 8.0, Arrival.at(100));

        // W = 10: the window covers [100, 110), so a hit arriving at 110 applies in full.
        HurtEstimate expired = model.effectiveRaw(VICTIM, 10.0, Arrival.at(110));
        assertEquals(10.0, expired.guaranteedLower(), EPS);
        assertEquals(10.0, expired.expected(), EPS);
    }

    @Test
    void anArrivalStraddlingTheWindowEdgeYieldsARangeNotAPoint() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(VICTIM, 8.0, new Arrival(100, 102));

        HurtEstimate straddle = model.effectiveRaw(VICTIM, 10.0, new Arrival(108, 112));
        // increment 2 when inside, full 10 when outside; the guaranteed lower bound stays the increment.
        assertEquals(2.0, straddle.guaranteedLower(), EPS);
        assertTrue(straddle.expected() > straddle.guaranteedLower(),
            "a straddling arrival must produce a range, not a point");
        assertTrue(straddle.expected() < 10.0);
    }

    @Test
    void anArrivalBeforeTheRecordedHitDoesNotInventCertainty() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(VICTIM, 8.0, Arrival.at(100));

        HurtEstimate earlier = model.effectiveRaw(VICTIM, 10.0, Arrival.at(50));
        assertEquals(0.0, earlier.guaranteedLower(), EPS);
        assertEquals(10.0, earlier.expected(), EPS);
    }

    @Test
    void bypassingTheCooldownAppliesTheFullValue() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(VICTIM, 8.0, Arrival.at(100));

        HurtEstimate bypass = model.effectiveRaw(VICTIM, 10.0, Arrival.at(101), true);
        assertEquals(10.0, bypass.guaranteedLower(), EPS);
        assertEquals(10.0, bypass.expected(), EPS);
    }

    @Test
    void windowStateReportsWhatIsKnown() {
        CombatHurtModel model = new CombatHurtModel();
        WindowState unknown = model.windowState(VICTIM);
        assertFalse(unknown.known());
        assertEquals(0.0, unknown.lastHurt(), EPS);

        model.observeHit(VICTIM, 8.0, Arrival.at(100));
        WindowState known = model.windowState(VICTIM);
        assertTrue(known.known());
        assertEquals(8.0, known.lastHurt(), EPS);
        assertTrue(known.possiblyInWindowAt(105));
        assertTrue(known.certainlyInWindowAt(105));
        assertFalse(known.possiblyInWindowAt(110));
    }

    @Test
    void theModelNeverLowersAGuaranteedLowerBoundThroughObservation() {
        CombatHurtModel model = new CombatHurtModel();
        model.observeHit(VICTIM, 8.0, new Arrival(100, 104));
        // Overlapping observation widens the interval and keeps the larger last hurt: conservative.
        model.observeHit(VICTIM, 6.0, new Arrival(98, 102));
        WindowState state = model.windowState(VICTIM);
        assertEquals(8.0, state.lastHurt(), EPS);
        assertEquals(98L, state.lastArrival().earliest());
        assertEquals(104L, state.lastArrival().latest());
    }

    @Test
    void malformedInputsAreRejected() {
        CombatHurtModel model = new CombatHurtModel();
        assertThrows(IllegalArgumentException.class,
            () -> model.observeHit(VICTIM, Double.NaN, Arrival.at(1)));
        assertThrows(IllegalArgumentException.class,
            () -> model.observeHit(VICTIM, -1.0, Arrival.at(1)));
        assertThrows(IllegalArgumentException.class,
            () -> model.effectiveRaw(VICTIM, Double.POSITIVE_INFINITY, Arrival.at(1)));
        assertThrows(IllegalArgumentException.class,
            () -> model.effectiveRaw(VICTIM, -1.0, Arrival.at(1)));
        assertThrows(NullPointerException.class,
            () -> model.observeHit(null, 1.0, Arrival.at(1)));
    }
}
