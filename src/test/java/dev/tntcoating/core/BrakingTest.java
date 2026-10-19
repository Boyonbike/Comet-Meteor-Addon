package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrakingTest {
    private static final double EPSILON = 1e-9;
    private static final double HORIZON = 64 - .35;
    private static final Braking BOOST = new Braking(.3, HORIZON, 15);

    @Test void withoutABoostTopSpeedBrakingIsTheConstantFormula() {
        for (Braking braking : new Braking[] {Braking.constant(.3), new Braking(.3, HORIZON, 0),
                new Braking(.3, HORIZON, 5)}) {
            assertEquals(.3, braking.capacity(15), EPSILON);
            for (double clearance : new double[] {0, .5, 3, 20, HORIZON}) {
                assertEquals(FlightCorridor.safeSpeed(.3, clearance), braking.envelope(clearance), 0);
            }
        }
    }

    @Test void theTopSpeedIsSustainableInAClearLookaheadAndCruiseBrakingBarelyChanges() {
        assertEquals(HORIZON, BOOST.reach(15), 1e-9, "a stop from 300 b/s fits the lookahead exactly");
        assertTrue(BOOST.envelope(HORIZON) >= 15 - 1e-9);
        assertEquals(15, BOOST.allowed(15, HORIZON), 1e-9);
        assertTrue(BOOST.capacity(2.06) < .34, "41 b/s cruise brakes about as gently as before: " + BOOST.capacity(2.06));
    }

    @Test void allowanceNeverExceedsClearanceAndRisesWithIt() {
        for (double speed : new double[] {0, 2, 8, 15, 30}) {
            double previous = 0;
            for (double clearance = 0; clearance <= HORIZON; clearance += .25) {
                double allowed = BOOST.allowed(speed, clearance);
                assertTrue(allowed <= clearance + EPSILON, speed + " / " + clearance);
                assertTrue(allowed + EPSILON >= previous, speed + " / " + clearance);
                previous = allowed;
            }
        }
    }

    @Test void cruiseApproachesBrakeGraduallyAtTheComfortRate() {
        Approach approach = approach(BOOST, 2, 60);
        assertTrue(approach.hardest <= BOOST.capacity(2) + EPSILON, "40 b/s braking " + approach.hardest);
        assertTrue(approach.stopGap >= 0);
    }

    @Test void boostSeesAWallAtTheLookaheadAndBrakesGentlyEnoughToStop() {
        Approach approach = approach(BOOST, 15, HORIZON - .01);
        assertTrue(approach.hardest < 2, "300 b/s braking " + approach.hardest);
        assertTrue(approach.stopGap >= 0 && approach.stopGap < 2, "stops just short: " + approach.stopGap);
    }

    @Test void somethingAppearingCloseIsStillAvoidedWithBrakingAsHardAsNeeded() {
        for (double clearance : new double[] {.5, 3, 10, 25}) {
            for (double speed : new double[] {2, 8, 15, 30}) {
                Approach approach = approach(BOOST, speed, clearance);
                assertTrue(approach.stopGap >= -EPSILON, speed + " into " + clearance);
            }
        }
        assertTrue(approach(BOOST, 15, 10).hardest > 5, "a close surprise at 300 b/s needs hard braking");
    }

    private record Approach(double hardest, double stopGap) {}

    /** Flies at {@code speed} towards something {@code clearance} ahead, never speeding up, until stopped. */
    private static Approach approach(Braking braking, double speed, double clearance) {
        double hardest = 0;
        for (int tick = 0; tick < 1000 && speed > 1e-6; tick++) {
            double next = Math.min(speed, braking.allowed(speed, clearance));
            hardest = Math.max(hardest, speed - next);
            speed = next;
            clearance -= speed;
            if (clearance < -EPSILON) return new Approach(hardest, clearance);
        }
        return new Approach(hardest, clearance);
    }
}
