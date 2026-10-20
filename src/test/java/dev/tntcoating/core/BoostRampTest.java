package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoostRampTest {
    private static final double EPSILON = 1e-9;
    private static final BoostRamp.Settings VOLYTRA = new BoostRamp.Settings(2.999, 14.999, .3, 15, 0);

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
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(0);
        for (int tick = 0; tick < 400; tick++) ramp.advance();
        double before = ramp.speed();

        ramp.corrected();

        assertTrue(ramp.speed() < before);
        assertTrue(ramp.speed() > before * .99, "VolytraFly loses under 1% at top speed");
        assertEquals(BoostRamp.rewind(before, .3, 14.999, 2.999, 15), ramp.speed(), EPSILON);
    }

    @Test void slowSetbacksRewindFurtherButNeverBelowTheStart() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(4);
        ramp.corrected();
        assertEquals(2.999, ramp.speed(), EPSILON);
    }

    @Test void chainedSetbacksOnlyEverRewindAndTheRampClimbsBackEachTime() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(14.9);
        for (int setback = 0; setback < 50; setback++) {
            double before = ramp.speed();
            ramp.corrected();
            assertEquals(BoostRamp.rewind(before, .3, 14.999, 2.999, 15), ramp.speed(), EPSILON,
                "no cap: every setback costs the same rewind, however close together");
            for (int tick = 0; tick < 15; tick++) ramp.advance();
        }
        assertTrue(ramp.speed() > 14.9, "fifteen clean ticks win back a fifteen-tick rewind: " + ramp.speed());
    }

    @Test void afterASetbackTheRampHoldsItsRewoundSpeedBeforeAcceleratingAgain() {
        BoostRamp ramp = new BoostRamp(new BoostRamp.Settings(2.999, 14.999, .3, 15, 2));
        ramp.engage(10);
        ramp.corrected();
        double rewound = BoostRamp.rewind(10, .3, 14.999, 2.999, 15);
        assertEquals(rewound, ramp.speed(), EPSILON);
        ramp.advance();
        ramp.advance();
        assertEquals(rewound, ramp.speed(), EPSILON, "held for two ticks");
        ramp.advance();
        assertEquals(BoostRamp.next(rewound, .3, 14.999, 14.999), ramp.speed(), EPSILON, "then accelerates");
    }

    @Test void aSetbackWhileReleasedChangesNothing() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(9);
        ramp.disengage();
        ramp.corrected();
        ramp.engage(9);
        assertEquals(9, ramp.speed(), EPSILON);
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

    @Test void releasingReportsNoSpeed() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(9);
        ramp.disengage();
        assertFalse(ramp.engaged());
        assertEquals(0, ramp.speed(), EPSILON);
        ramp.advance();
        assertEquals(0, ramp.speed(), EPSILON);
    }

    @Test void loweringTheMaximumPullsAnEngagedRampDown() {
        BoostRamp ramp = new BoostRamp(VOLYTRA);
        ramp.engage(14);
        ramp.configure(new BoostRamp.Settings(2.999, 7.5, .3, 15, 0));
        assertEquals(7.5, ramp.speed(), EPSILON);
    }

    @Test void aLimitStopsTheCurveWithoutReshapingIt() {
        BoostRamp free = new BoostRamp(VOLYTRA);
        BoostRamp capped = new BoostRamp(new BoostRamp.Settings(2.999, 14.999, .3, 15, 0, 7.5));
        free.engage(0);
        capped.engage(0);
        for (int tick = 0; tick < 200; tick++) {
            assertEquals(Math.min(7.5, free.speed()), capped.speed(), EPSILON, "tick " + tick);
            free.advance();
            capped.advance();
        }
        assertEquals(7.5, capped.speed(), EPSILON);
        assertEquals(7.5, capped.ceiling(), EPSILON);
    }

    @Test void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BoostRamp.Settings(3, 15, .3, 15, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new BoostRamp.Settings(-1, 15, .3, 15, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoostRamp.Settings(3, Double.NaN, .3, 15, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoostRamp.Settings(3, 15, 0, 15, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoostRamp.Settings(3, 15, .3, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoostRamp.Settings(3, 15, .3, 15, -1));
    }
}
