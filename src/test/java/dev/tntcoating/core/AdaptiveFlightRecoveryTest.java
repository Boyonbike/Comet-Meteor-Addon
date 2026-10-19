package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightMemory.Limits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFlightRecoveryTest {
    private static final double EPSILON = 1e-9;
    private static final double BACKOFF = .75;

    @Test void ordinaryVerticalExplorationStartsAfterTheFeedbackWindow() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
            for (int tick = 0; tick < AdaptiveFlightLearning.FEEDBACK_TICKS; tick++) {
                flyVertical(learning, direction, true, .0025);
                assertEquals(.75, vertical(learning.limits(), direction), EPSILON,
                    "a fresh probe must wait for the previous movement's server feedback");
            }

            for (int tick = AdaptiveFlightLearning.FEEDBACK_TICKS; tick < 30; tick++)
                flyVertical(learning, direction, true, .0025);

            assertTrue(vertical(learning.limits(), direction) > .75,
                "ordinary vertical exploration should start soon after the feedback window");
        }
    }

    @Test void defaultRecoveryMakesUsefulVerticalProgressWithinTwoHundredCleanTicks() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
            for (int tick = 0; tick < 200; tick++) flyVertical(learning, direction, true, .0025);

            assertTrue(vertical(learning.limits(), direction) > 1,
                "full-speed clean flight should not need many seconds to gain a tiny amount of speed");
            assertTrue(vertical(learning.remembered(), direction) > .75);
            assertEquals(.75, vertical(learning.limits(), -direction), EPSILON);
        }
    }

    @Test void theRecoveryRateScalesTheSizeOfVerticalProbes() {
        AdaptiveFlightLearning ordinary = new AdaptiveFlightLearning();
        AdaptiveFlightLearning faster = new AdaptiveFlightLearning();
        for (int tick = 0; tick < 30; tick++) {
            flyVertical(ordinary, 1, true, .0025);
            flyVertical(faster, 1, true, .005);
        }

        assertTrue(ordinary.limits().ascent() > .75);
        assertTrue(faster.limits().ascent() > ordinary.limits().ascent(),
            "raising the recovery rate should increase useful progress within the same feedback window");
    }

    @Test void aTransientSetbackRecoversThePreviouslyProvenVerticalSpeedPromptly() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
            for (int tick = 0; tick < 25; tick++) {
                learning.tick(60, .0025);
                learning.observe(new Motion(0, direction * .75, 0), direction, true, true, 1);
            }
            assertTrue(vertical(learning.remembered(), direction) >= .75 - EPSILON);

            assertTrue(learning.corrected(BACKOFF));
            assertEquals(.75 * BACKOFF, vertical(learning.limits(), direction), EPSILON);
            for (int tick = 0; tick < 200; tick++) {
                double previous = vertical(learning.limits(), direction);
                flyVertical(learning, direction, true, .0025);
                assertTrue(vertical(learning.limits(), direction) - previous <= .25 + EPSILON,
                    "recovery should use bounded probes even when a faster speed was previously proven");
            }

            assertTrue(vertical(learning.limits(), direction) >= .75 - EPSILON,
                "one transient setback must not strand a previously proven speed below its old limit");
        }
    }

    @Test void alternatingVerticalDirectionsPreservesEachDirectionsCleanProgress() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        for (int tick = 0; tick < 200; tick++) {
            int direction = tick / 5 % 2 == 0 ? 1 : -1;
            flyVertical(learning, direction, true, .0025);
        }

        assertTrue(learning.limits().ascent() > .75,
            "descending between ascent samples must not discard clean ascent progress");
        assertTrue(learning.limits().descent() > .75,
            "ascending between descent samples must not discard clean descent progress");
    }

    @Test void anUncleanOrMissingObservationRestartsTheVerticalFeedbackStreak() {
        for (boolean missing : new boolean[] {false, true}) {
            AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
            for (int tick = 0; tick < AdaptiveFlightLearning.FEEDBACK_TICKS; tick++)
                flyVertical(learning, 1, true, .0025);

            if (missing) learning.tick(60, .0025);
            else flyVertical(learning, 1, false, .0025);

            for (int tick = 0; tick < AdaptiveFlightLearning.FEEDBACK_TICKS; tick++)
                flyVertical(learning, 1, true, .0025);

            assertEquals(.75, learning.limits().ascent(), EPSILON,
                "fresh clean evidence is required after " + (missing ? "a missing observation" : "unclean motion"));
        }
    }

    @Test void zeroRecoveryRateDisablesGrowthEvenWithFullSpeedCleanFlight() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.restore(new Limits(1, .75, .75, .05), Limits.UNKNOWN);
        Limits before = learning.limits();
        for (int tick = 0; tick < 400; tick++) {
            learning.tick(60, 0);
            int direction = tick % 2 == 0 ? 1 : -1;
            learning.observe(new Motion(1, direction * .75, 0), direction, true, true, 1, 1);
        }

        assertEquals(before, learning.limits());
    }

    @Test void lowerPercentagesCannotProveAnUntestedVerticalMaximum() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        for (int tick = 0; tick < 300; tick++) {
            learning.tick(60, .0025);
            learning.observe(new Motion(0, .75 * .25, 0), 1, true, true, 1, .25);
        }

        assertEquals(.75, learning.limits().ascent(), EPSILON);
        assertEquals(64, learning.remembered().ascent(), EPSILON,
            "partial movement cannot become evidence that the full maximum survived server feedback");
    }

    @Test void mixedCorrectionsLearnTotalDisplacementWithoutPunishingEitherAxis() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
            Limits before = learning.limits();
            Motion diagonal = new Motion(3, direction * .75, 4);
            learning.observe(diagonal, direction, true, true, 1, 1);

            assertTrue(learning.corrected(BACKOFF));
            assertEquals(diagonal.length() * BACKOFF, learning.limits().total(), EPSILON);
            assertEquals(before.horizontal(), learning.limits().horizontal(), EPSILON);
            assertEquals(before.ascent(), learning.limits().ascent(), EPSILON);
            assertEquals(before.descent(), learning.limits().descent(), EPSILON);
            assertEquals(before.verticalAcceleration(), learning.limits().verticalAcceleration(), EPSILON);
            assertEquals(diagonal.length() * BACKOFF, learning.remembered().total(), EPSILON);
            assertEquals(64, learning.ceilings().horizontal(), EPSILON);
            assertEquals(64, learning.ceilings().ascent(), EPSILON);
            assertEquals(64, learning.ceilings().descent(), EPSILON);
        }
    }

    @Test void pureAxisCorrectionsStillLearnTheirOwnDirectionalLimit() {
        AdaptiveFlightLearning horizontal = new AdaptiveFlightLearning();
        horizontal.observe(new Motion(3, 0, 4), 0, true, true, 1, 1);
        assertTrue(horizontal.corrected(BACKOFF));
        assertEquals(5 * BACKOFF, horizontal.limits().horizontal(), EPSILON);
        assertEquals(.75, horizontal.limits().ascent(), EPSILON);
        assertEquals(64, horizontal.limits().total(), EPSILON);

        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightLearning vertical = new AdaptiveFlightLearning();
            vertical.observe(new Motion(0, direction * .75, 0), direction, true, true, 1, 1);
            assertTrue(vertical.corrected(BACKOFF));
            assertEquals(.75 * BACKOFF, vertical(vertical.limits(), direction), EPSILON);
            assertEquals(.75, vertical(vertical.limits(), -direction), EPSILON);
            assertEquals(64, vertical.limits().horizontal(), EPSILON);
            assertEquals(64, vertical.limits().total(), EPSILON);
        }
    }

    @Test void fullSprintDiagonalMotionCanRecoverTheSharedTotalBudget() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(1, .75, 0), 1, true, true, 1, 1);
        assertTrue(learning.corrected(BACKOFF));
        double correctedTotal = learning.limits().total();

        for (int tick = 0; tick < 400; tick++) {
            learning.tick(60, .0025);
            double total = learning.limits().total();
            double y = Math.min(learning.limits().ascent(), total * .6);
            learning.observe(new Motion(Math.sqrt(total * total - y * y), y, 0),
                1, true, true, 1, 1);
        }

        assertTrue(learning.limits().total() > correctedTotal,
            "clean diagonal motion must be able to retest a total displacement correction");
        assertTrue(learning.remembered().total() > correctedTotal,
            "settled diagonal probes should survive reconnecting");
        assertTrue(learning.remembered().total() <= learning.limits().total() + EPSILON);
    }

    @Test void partialSprintMotionCannotAuthorizeGrowthOfTheFullTotalBudget() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(1, .75, 0), 1, true, true, 1, 1);
        assertTrue(learning.corrected(BACKOFF));
        double correctedTotal = learning.limits().total();

        for (int tick = 0; tick < 400; tick++) {
            learning.tick(60, .0025);
            double total = learning.limits().total() * .25;
            learning.observe(new Motion(total * .8, total * .6, 0), 1, true, true, .25, .25);
        }

        assertEquals(correctedTotal, learning.limits().total(), EPSILON);
    }

    @Test void horizontalBoundaryEvidenceRemainsValidWhileThePlayerMovesVertically() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.restore(new Limits(1, .75, .75, .05), Limits.UNKNOWN);
        for (int tick = 0; tick < 300; tick++) {
            learning.tick(60, .0025);
            learning.observe(new Motion(learning.limits().horizontal(), .2, 0), 1, true, true, 1, 1);
        }

        assertTrue(learning.limits().horizontal() > 1,
            "vertical input must not suppress independent horizontal boundary learning");
        assertTrue(learning.remembered().horizontal() > 1);
        assertEquals(.75, learning.limits().ascent(), EPSILON,
            "small vertical movement does not prove the vertical boundary");
    }

    @Test void lowSpeedJitterDoesNotReplacePreviouslyObservedVerticalEvidence() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        for (int tick = 0; tick < 25; tick++) {
            learning.tick(60, .0025);
            learning.observe(new Motion(0, .75, 0), 1, true, true, 1);
        }
        for (int tick = 0; tick < 25; tick++) {
            learning.tick(60, .0025);
            learning.observe(new Motion(0, .75 * .59, 0), 1, true, true, 1);
        }
        Limits before = learning.limits();

        assertFalse(learning.corrected(BACKOFF));
        assertEquals(before, learning.limits());
        assertEquals(Limits.UNKNOWN, learning.ceilings());
    }

    @Test void aLagPausePreservesLearningAndIgnoresCorrectionsUntilFeedbackCanSettle() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        for (int tick = 0; tick < 25; tick++) {
            learning.tick(60, .0025);
            learning.observe(new Motion(0, .75, 0), 1, true, true, 1);
        }
        Limits before = learning.limits();
        Limits remembered = learning.remembered();

        learning.pauseForLag();
        for (int tick = 0; tick < AdaptiveFlightLearning.FEEDBACK_TICKS; tick++) {
            flyVertical(learning, 1, true, .0025);
            assertFalse(learning.corrected(BACKOFF),
                "movement and corrections immediately after a stall cannot teach a speed boundary");
            assertEquals(before, learning.limits());
            assertEquals(remembered, learning.remembered());
        }

        flyVertical(learning, 1, true, .0025);
        assertTrue(learning.corrected(BACKOFF),
            "ordinary movement evidence should resume after the lag feedback window");
        assertTrue(learning.limits().ascent() < before.ascent());
    }

    private static void flyVertical(AdaptiveFlightLearning learning, int direction, boolean clean, double rate) {
        learning.tick(60, rate);
        learning.observe(new Motion(0, direction * vertical(learning.limits(), direction), 0),
            direction, true, clean, 1, 1);
    }

    @Test void delayedMixedAttributionUsesTheFastestActualDisplacementInTheFeedbackWindow() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(1, .1, 0), 1, true, true, 1);
        learning.tick(60, .0025);
        learning.observe(new Motion(5, 0, 0), 0, true, true, 1);

        assertTrue(learning.corrected(BACKOFF));
        assertEquals(5 * BACKOFF, learning.limits().total(), EPSILON,
            "a delayed correction must not set every axis's budget from an older slow diagonal sample");
        assertEquals(64, learning.limits().horizontal(), EPSILON);
        assertEquals(.75, learning.limits().ascent(), EPSILON);
    }

    @Test void cleanBrakingThroughAReversalKeepsBothDirectionsProgress() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        for (int tick = 0; tick < 20; tick++) flyVertical(learning, 1, true, .0025);
        // Input reverses before momentum does, then accelerates through slower samples.
        for (double y : new double[] {.6, .3, 0, -.2, -.5, -.75}) {
            learning.tick(60, .0025);
            learning.observe(new Motion(0, y, 0), -1, true, true, 1);
        }
        for (double y : new double[] {-.6, -.3, 0, .2, .5, .75, .75}) {
            learning.tick(60, .0025);
            learning.observe(new Motion(0, y, 0), 1, true, true, 1);
        }

        assertTrue(learning.limits().ascent() > .75,
            "ordinary braking and acceleration during a reversal must not erase earlier full-speed evidence");
        assertEquals(.75, learning.limits().descent(), EPSILON,
            "a brief descent has not yet demonstrated enough full-speed ticks for its own probe");
    }

    private static double vertical(Limits limits, int direction) {
        return direction > 0 ? limits.ascent() : limits.descent();
    }
}
