package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoostRampTest {
    private static final double EPSILON = 1e-9;
    private static final BoostRamp.Settings VOLYTRA = new BoostRamp.Settings(2.999, 14.999, .3, 15, true);

    @Test void followsVolytrasPlateauCurveFromTheStartSpeed() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(0);
        assertEquals(2.999, ramp.speed(), EPSILON);

        ramp.advance();
        assertEquals(2.999 + .3 * (14.999 - 2.999) / 14.999, ramp.speed(), EPSILON);
        for (int tick = 0; tick < 1000; tick++) ramp.advance();
        assertTrue(ramp.speed() <= 14.999 && ramp.speed() > 14.99, "the curve approaches but never passes the maximum");
    }

    @Test void engagingCarriesExistingSpeedWithinTheStartAndMaximum() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(5);
        assertEquals(5, ramp.speed(), EPSILON);
        ramp.engage(1);
        assertEquals(2.999, ramp.speed(), EPSILON);
        ramp.engage(40);
        assertEquals(14.999, ramp.speed(), EPSILON);
    }

    @Test void aSetbackNearTopSpeedRewindsInsteadOfRestarting() {
        BoostRamp ramp = new BoostRamp(new BoostRamp.Settings(2.999, 14.999, .3, 15, false));
        ramp.engage(0);
        for (int tick = 0; tick < 400; tick++) ramp.advance();
        double before = ramp.speed();

        ramp.corrected(0, before);

        assertTrue(ramp.speed() < before);
        assertTrue(ramp.speed() > before * .99, "VolytraFly loses under 1% at top speed");
        assertEquals(BoostRamp.rewind(before, .3, 14.999, 2.999, 15), ramp.speed(), EPSILON);
    }

    @Test void slowSetbacksRewindFurtherButNeverBelowTheStart() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(4);
        ramp.corrected(0, 4);
        assertEquals(2.999, ramp.speed(), EPSILON);
    }

    @Test void threeSetbacksWithinThreeSecondsCapBelowTheSlowestForTenSeconds() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(14);
        ramp.corrected(0, 14);
        ramp.corrected(20, 13);
        assertFalse(ramp.capped());
        ramp.corrected(60, 14.5);

        assertTrue(ramp.capped());
        assertEquals(13 * BoostRamp.CAP_MARGIN, ramp.cap(), EPSILON);
        assertEquals(13 * BoostRamp.CAP_MARGIN, ramp.ceiling(), EPSILON);
        assertTrue(ramp.speed() <= ramp.cap() + EPSILON);
        for (int tick = 0; tick < 50; tick++) ramp.advance();
        assertEquals(ramp.cap(), ramp.speed(), EPSILON, "the ramp holds at the cap instead of climbing into setbacks");

        for (int tick = 1; tick < BoostRamp.CAP_DURATION_TICKS; tick++) ramp.tick();
        assertTrue(ramp.capped());
        ramp.tick();
        assertFalse(ramp.capped());
        ramp.advance();
        assertTrue(ramp.speed() > 13 * BoostRamp.CAP_MARGIN, "an expired cap climbs again");
    }

    @Test void aBoostMaximumAboveTheVanillaElytraLimitSettlesBelowItThroughRewindAndCap() {
        double limit = Math.sqrt(300); // Vanilla: squared move minus squared server glide over 300 is a setback.
        BoostRamp ramp = new BoostRamp(new BoostRamp.Settings(2.999, 18, .3, 15, true));
        ramp.engage(16);
        int setbacks = 0, firstCap = -1;
        for (int tick = 0; tick < 2000; tick++) {
            ramp.tick();
            ramp.advance();
            if (ramp.speed() > limit) {
                setbacks++;
                ramp.corrected(tick, ramp.speed());
            }
            if (firstCap < 0 && ramp.capped()) firstCap = tick;
            if (ramp.capped()) assertTrue(ramp.speed() < limit, "a capped ramp stays under the server limit");
        }
        assertTrue(firstCap >= 0 && firstCap < 200, "the cap engages within seconds: " + firstCap);
        assertTrue(ramp.cap() > limit * .9, "the cap sits just below the limit: " + ramp.cap());
        assertTrue(setbacks <= 2000 / BoostRamp.CAP_DURATION_TICKS * 3 + 3,
            "setbacks only recur when an expired cap climbs again: " + setbacks);
    }

    @Test void spreadOutSetbacksOrADisabledCapNeverCap() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(14);
        ramp.corrected(0, 14);
        ramp.corrected(61, 14);
        ramp.corrected(122, 14);
        assertFalse(ramp.capped());

        BoostRamp disabled = new BoostRamp(new BoostRamp.Settings(2.999, 14.999, .3, 15, false));
        disabled.engage(14);
        for (int tick = 0; tick < 5; tick++) disabled.corrected(tick, 14);
        assertFalse(disabled.capped());
    }

    @Test void theCapNeverDropsBelowTheStartSpeed() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(0);
        for (int tick = 0; tick < 3; tick++) ramp.corrected(tick, 1);
        assertEquals(2.999, ramp.cap(), EPSILON);
        assertEquals(2.999, ramp.speed(), EPSILON);
    }

    @Test void collisionLossesContinueFromWhatWasKeptAboveTheStart() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(10);
        ramp.limitTo(12);
        assertEquals(10, ramp.speed(), EPSILON, "a collision never adds speed");
        ramp.limitTo(8);
        assertEquals(8, ramp.speed(), EPSILON);
        ramp.limitTo(1);
        assertEquals(2.999, ramp.speed(), EPSILON);
    }

    @Test void releasingReportsNoSpeedButRemembersItForLateSetbacks() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(9);
        ramp.disengage();
        assertFalse(ramp.engaged());
        assertEquals(0, ramp.speed(), EPSILON);
        assertEquals(9, ramp.lastSpeed(), EPSILON);
        ramp.advance();
        assertEquals(0, ramp.speed(), EPSILON);
    }

    @Test void loweringTheMaximumPullsAnEngagedRampDown() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(14);
        ramp.configure(new BoostRamp.Settings(2.999, 7.5, .3, 15, true));
        assertEquals(7.5, ramp.speed(), EPSILON);
    }

    @Test void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BoostRamp.Settings(-1, 15, .3, 15, true));
        assertThrows(IllegalArgumentException.class, () -> new BoostRamp.Settings(3, Double.NaN, .3, 15, true));
        assertThrows(IllegalArgumentException.class, () -> new BoostRamp.Settings(3, 15, 0, 15, true));
        assertThrows(IllegalArgumentException.class, () -> new BoostRamp.Settings(3, 15, .3, -1, true));
    }
}
