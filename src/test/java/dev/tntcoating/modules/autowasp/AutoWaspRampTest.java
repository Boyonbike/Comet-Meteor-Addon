package dev.tntcoating.modules.autowasp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWaspRampTest {
    private static final double EPSILON = 1e-9;
    // VolytraFly's horizontal boost: 60 b/s at once, tapering towards 300 b/s.
    private static final AutoWaspRamp.Settings DEFAULTS = new AutoWaspRamp.Settings(AutoWaspSpeed.HORIZONTAL_START,
        AutoWaspSpeed.HORIZONTAL_TOP, .3, 15, 0, AutoWaspSpeed.HORIZONTAL_TOP);

    @Test void followsVolytrasPlateauCurveFromTheStartSpeed() {
        AutoWaspRamp ramp = new AutoWaspRamp(DEFAULTS);
        ramp.engage(0);
        assertEquals(3, ramp.speed(), EPSILON, "boost starts at 60 b/s at once");

        ramp.advance();
        assertEquals(3 + .3 * (15 - 3) / 15, ramp.speed(), EPSILON);
        for (int tick = 0; tick < 2000; tick++) ramp.advance();
        assertTrue(ramp.speed() <= 15 && ramp.speed() > 14.99, "the curve approaches but never passes the plateau");
    }

    @Test void reachesTheBunchedSafeTopInAboutThreeAndAHalfSeconds() {
        AutoWaspRamp ramp = new AutoWaspRamp(new AutoWaspRamp.Settings(AutoWaspSpeed.HORIZONTAL_START,
            AutoWaspSpeed.HORIZONTAL_TOP, .3, 15, 1, AutoWaspSpeed.BUNCHED_SAFE));
        ramp.engage(.4);
        int ticks = 0;
        while (ramp.speed() < AutoWaspSpeed.BUNCHED_SAFE && ticks < 200) {
            double before = ramp.speed();
            ramp.advance();
            assertTrue(ramp.speed() - before <= .3 + EPSILON, "never gains more than the step per tick");
            ticks++;
        }
        assertEquals(AutoWaspSpeed.BUNCHED_SAFE, ramp.speed(), EPSILON);
        assertTrue(ticks >= 65 && ticks <= 72, "60 to 240 b/s takes " + ticks + " ticks");
    }

    @Test void verticalBoostClimbsFromThirtyTowardsSixtyBlocksPerSecond() {
        AutoWaspRamp ramp = new AutoWaspRamp(new AutoWaspRamp.Settings(AutoWaspSpeed.VERTICAL_START,
            AutoWaspSpeed.VERTICAL_TOP, AutoWaspSpeed.VERTICAL_STEP, 15, 1, AutoWaspSpeed.VERTICAL_TOP));
        ramp.engage(0);
        assertEquals(1.5, ramp.speed(), EPSILON);
        for (int tick = 0; tick < 20; tick++) ramp.advance();
        assertTrue(ramp.speed() > 2.9 && ramp.speed() <= 3, "a second of climbing nears 60 b/s: " + ramp.speed());
    }

    @Test void engagingCarriesExistingSpeedWithinTheStartAndCeiling() {
        AutoWaspRamp ramp = new AutoWaspRamp(DEFAULTS);
        ramp.engage(5);
        assertEquals(5, ramp.speed(), EPSILON);
        ramp.engage(.2);
        assertEquals(3, ramp.speed(), EPSILON);
        ramp.engage(40);
        assertEquals(15, ramp.speed(), EPSILON);
        ramp.engage(Double.NaN);
        assertEquals(3, ramp.speed(), EPSILON);
    }

    @Test void aSetbackRewindsInsteadOfRestartingAndNeverBelowTheStart() {
        AutoWaspRamp ramp = new AutoWaspRamp(DEFAULTS);
        ramp.engage(12);
        ramp.corrected();
        assertEquals(AutoWaspRamp.rewind(12, .3, 15, 3, 15), ramp.speed(), EPSILON);
        assertTrue(ramp.speed() < 12 && ramp.speed() > 3);

        ramp.engage(3.5);
        ramp.corrected();
        assertEquals(3, ramp.speed(), EPSILON, "slow setbacks rewind to the start speed");
    }

    @Test void aSetbackNearTopSpeedLosesUnderOnePercent() {
        AutoWaspRamp ramp = new AutoWaspRamp(DEFAULTS);
        ramp.engage(0);
        for (int tick = 0; tick < 400; tick++) ramp.advance();
        double before = ramp.speed();
        ramp.corrected();
        assertTrue(ramp.speed() < before && ramp.speed() > before * .99);
    }

    @Test void afterASetbackTheRampHoldsItsRewoundSpeedBeforeAcceleratingAgain() {
        AutoWaspRamp ramp = new AutoWaspRamp(new AutoWaspRamp.Settings(3, 15, .3, 15, 2, 15));
        ramp.engage(10);
        ramp.corrected();
        double rewound = AutoWaspRamp.rewind(10, .3, 15, 3, 15);
        ramp.advance();
        ramp.advance();
        assertEquals(rewound, ramp.speed(), EPSILON, "held for two ticks");
        ramp.advance();
        assertEquals(AutoWaspRamp.next(rewound, .3, 15, 15), ramp.speed(), EPSILON, "then accelerates");
    }

    @Test void chainedSetbacksOnlyEverRewindAndTheRampClimbsBackEachTime() {
        AutoWaspRamp ramp = new AutoWaspRamp(DEFAULTS);
        ramp.engage(14.9);
        for (int setback = 0; setback < 50; setback++) {
            double before = ramp.speed();
            ramp.corrected();
            assertEquals(AutoWaspRamp.rewind(before, .3, 15, 3, 15), ramp.speed(), EPSILON);
            for (int tick = 0; tick < 15; tick++) ramp.advance();
        }
        assertTrue(ramp.speed() > 14.8, "fifteen clean ticks win back a fifteen-tick rewind: " + ramp.speed());
    }

    @Test void collisionLossesContinueFromWhatWasKeptAboveTheStart() {
        AutoWaspRamp ramp = new AutoWaspRamp(DEFAULTS);
        ramp.engage(10);
        ramp.limitTo(12);
        assertEquals(10, ramp.speed(), EPSILON, "a collision never adds speed");
        ramp.limitTo(8);
        assertEquals(8, ramp.speed(), EPSILON);
        ramp.advance();
        assertEquals(AutoWaspRamp.next(8, .3, 15, 15), ramp.speed(), EPSILON, "and speeds up from there");
        ramp.limitTo(0);
        assertEquals(3, ramp.speed(), EPSILON, "never below the start speed");
    }

    @Test void aSetbackWhileReleasedChangesNothing() {
        AutoWaspRamp ramp = new AutoWaspRamp(DEFAULTS);
        ramp.engage(9);
        ramp.disengage();
        ramp.corrected();
        ramp.limitTo(2);
        assertEquals(0, ramp.speed(), EPSILON);
        ramp.engage(9);
        assertEquals(9, ramp.speed(), EPSILON);
    }

    @Test void aLimitStopsTheCurveWithoutReshapingIt() {
        AutoWaspRamp free = new AutoWaspRamp(DEFAULTS);
        AutoWaspRamp capped = new AutoWaspRamp(new AutoWaspRamp.Settings(3, 15, .3, 15, 0, 7.5));
        free.engage(0);
        capped.engage(0);
        for (int tick = 0; tick < 200; tick++) {
            assertEquals(Math.min(7.5, free.speed()), capped.speed(), EPSILON, "tick " + tick);
            free.advance();
            capped.advance();
        }
        assertEquals(7.5, capped.ceiling(), EPSILON);
    }

    @Test void aCapBelowTheStartSpeedHoldsTheRampThere() {
        AutoWaspRamp ramp = new AutoWaspRamp(DEFAULTS);
        ramp.engage(14);
        ramp.configure(new AutoWaspRamp.Settings(3, 15, .3, 15, 0, 2));
        assertEquals(2, ramp.speed(), EPSILON);
        ramp.engage(0);
        assertEquals(2, ramp.speed(), EPSILON);
    }

    @Test void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AutoWaspRamp.Settings(1, 15, .3, 15, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new AutoWaspRamp.Settings(-1, 15, .3, 15, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> new AutoWaspRamp.Settings(1, Double.NaN, .3, 15, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> new AutoWaspRamp.Settings(1, 15, 0, 15, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> new AutoWaspRamp.Settings(1, 15, .3, -1, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> new AutoWaspRamp.Settings(1, 15, .3, 15, -1, 15));
    }
}
