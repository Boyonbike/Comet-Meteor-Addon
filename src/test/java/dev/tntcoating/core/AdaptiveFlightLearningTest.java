package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightMemory.Limits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFlightLearningTest {
    private static final double EPSILON = 1e-9;
    private static final double BACKOFF = .75;

    @Test void anUnknownServerStartsVerticalFlightConservativelyWithoutInventingEvidence() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();

        assertEquals(64, learning.limits().horizontal(), EPSILON);
        assertEquals(.75, learning.limits().ascent(), EPSILON);
        assertEquals(.75, learning.limits().descent(), EPSILON);
        assertEquals(.05, learning.limits().verticalAcceleration(), EPSILON);
        assertEquals(Limits.UNKNOWN, learning.remembered());
    }

    @Test void aVerticalSetbackLearnsTheActualSlowSpeedRatherThanTheUnusedMaximum() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
            learning.observe(new Motion(0, direction * .2, 0), direction, true, true, 1);

            assertTrue(learning.corrected(BACKOFF));
            assertTrue(vertical(learning.limits(), direction) <= .2 * BACKOFF + EPSILON,
                "a rejection at .2 blocks/tick must lower even the .75 initial ceiling");
            assertEquals(.75, vertical(learning.limits(), -direction), EPSILON);
            assertEquals(64, learning.limits().horizontal(), EPSILON,
                "a vertical setback must not erase the independent horizontal ceiling");
            assertTrue(learning.limits().verticalAcceleration() <= .05 * BACKOFF + EPSILON);
            assertTrue(vertical(learning.remembered(), direction) <= .2 * BACKOFF + EPSILON);
            assertEquals(64, vertical(learning.remembered(), -direction), EPSILON);
        }
    }

    @Test void delayedVerticalSetbacksStillApplyAfterTheVerticalKeyIsReleased() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
            learning.observe(new Motion(0, direction * .6, 0), direction, true, true, 1);
            for (int tick = 0; tick < 10; tick++) {
                learning.tick(60, .0025);
                learning.observe(Motion.ZERO, 0, true, true, 1);
            }

            assertTrue(learning.corrected(BACKOFF));
            assertTrue(vertical(learning.limits(), direction) <= .6 * BACKOFF + EPSILON);
            assertEquals(64, learning.limits().horizontal(), EPSILON,
                "the released vertical input remains relevant while a delayed setback arrives");
        }
    }

    @Test void pureHorizontalSetbacksLeaveAscentAndDescentIndependent() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(.8, 0, .6), 0, true, true, 1);

        assertTrue(learning.corrected(BACKOFF));
        assertTrue(learning.limits().horizontal() <= BACKOFF + EPSILON);
        assertEquals(.75, learning.limits().ascent(), EPSILON);
        assertEquals(.75, learning.limits().descent(), EPSILON);
        assertEquals(.05, learning.limits().verticalAcceleration(), EPSILON);
    }

    @Test void aBurstOfCorrectionsWithoutNewMovementDoesNotRepeatedlyPunishTheSameSample() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(0, .4, 0), 1, true, true, 1);
        assertTrue(learning.corrected(BACKOFF));
        Limits backedOff = learning.limits();

        for (int correction = 0; correction < 5; correction++) {
            assertFalse(learning.corrected(BACKOFF));
            assertEquals(backedOff, learning.limits());
        }
    }

    @Test void staleMovementCannotBeBlamedForAnUnrelatedLaterTeleport() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(2, .6, 0), 1, true, true, 1);
        for (int tick = 0; tick < 25; tick++) {
            learning.tick(60, .0025);
            learning.observe(Motion.ZERO, 0, false, true, 1);
        }

        Limits before = learning.limits();
        assertFalse(learning.corrected(BACKOFF));
        assertEquals(before, learning.limits());
        assertEquals(Limits.UNKNOWN, learning.remembered());
    }

    @Test void resettingMovementHistoryPreservesLimitsButPreventsStaleCorrectionAttribution() {
        Limits restored = new Limits(3, .4, .6, .025);
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.restore(restored);
        learning.observe(new Motion(0, .4, 0), 1, true, true, 1);

        learning.resetHistory();

        assertFalse(learning.corrected(BACKOFF));
        assertEquals(restored, learning.limits());
        assertEquals(restored, learning.remembered());
    }

    @Test void rememberedLimitsSurviveConfigurationAndUnknownAxesUseTheConfiguredStartingSpeed() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        Limits restored = new Limits(3, .3, 64, .02);
        learning.restore(restored);
        learning.configure(.6, .04);

        assertEquals(3, learning.limits().horizontal(), EPSILON);
        assertEquals(.3, learning.limits().ascent(), EPSILON);
        assertEquals(.6, learning.limits().descent(), EPSILON);
        assertEquals(.02, learning.limits().verticalAcceleration(), EPSILON,
            "changing tuning must respect the stricter learned acceleration");
        assertEquals(restored, learning.remembered());
    }

    @Test void aFreshServerUsesTheConfiguredVerticalAcceleration() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();

        learning.configure(.6, .1);

        assertEquals(.6, learning.limits().ascent(), EPSILON);
        assertEquals(.6, learning.limits().descent(), EPSILON);
        assertEquals(.1, learning.limits().verticalAcceleration(), EPSILON);
        assertEquals(Limits.UNKNOWN, learning.remembered(),
            "a configured starting value is not evidence from this server");
    }

    @Test void aCleanWarmStartKeepsItsProvenSpeedAndCanContinueLearning() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        Limits proven = new Limits(64, .9, .8, .05);

        learning.restore(proven, Limits.UNKNOWN);

        assertEquals(.9, learning.limits().ascent(), EPSILON);
        assertEquals(.8, learning.limits().descent(), EPSILON);
        assertEquals(proven, learning.remembered());
        for (int tick = 0; tick < 250; tick++) {
            learning.tick(40, .01);
            learning.observe(new Motion(0, learning.limits().ascent(), 0), 1, true, true, 1);
        }

        assertTrue(learning.limits().ascent() > .9,
            "a proven reconnect speed is a starting point, not a rejected ceiling");
        assertTrue(learning.remembered().ascent() > .9);
        assertEquals(.8, learning.limits().descent(), EPSILON);
        assertEquals(64, learning.ceilings().ascent(), EPSILON);
    }

    @Test void rememberedCorrectionGuidanceAllowsCautiousLearningAfterReconnect() {
        AdaptiveFlightLearning previousSession = new AdaptiveFlightLearning();
        previousSession.observe(new Motion(0, .4, 0), 1, true, true, 1);
        assertTrue(previousSession.corrected(BACKOFF));
        double rejected = previousSession.limits().ascent();
        AdaptiveFlightLearning reconnected = new AdaptiveFlightLearning();

        reconnected.restore(previousSession.remembered(), previousSession.ceilings());
        for (int tick = 0; tick < 43; tick++) {
            reconnected.tick(1, .1);
            reconnected.observe(new Motion(0, reconnected.limits().ascent(), 0), 1, true, true, 1);
        }
        assertEquals(rejected, reconnected.limits().ascent(), EPSILON,
            "retesting a corrected boundary requires twice the normal clean streak");

        for (int tick = 0; tick < 500; tick++) {
            reconnected.tick(1, .1);
            reconnected.observe(new Motion(0, reconnected.limits().ascent(), 0), 1, true, true, 1);
        }
        assertTrue(reconnected.limits().ascent() > rejected,
            "clean flight may cautiously probe beyond remembered guidance");
        assertTrue(reconnected.remembered().ascent() > rejected,
            "settled successful probes become the next warm start");
        assertEquals(rejected, reconnected.ceilings().ascent(), EPSILON,
            "the correction threshold remains guidance for cautious future steps");
    }

    @Test void switchingProfilesDiscardsThePreviousServersCorrectionHistory() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(2, .7, 0), 1, true, true, 1);
        Limits otherServer = new Limits(64, .4, .6, .03);

        learning.restore(otherServer, Limits.UNKNOWN);

        assertFalse(learning.corrected(BACKOFF),
            "a teleport after switching profiles cannot punish the previous server's movement");
        for (int tick = 0; tick < 25; tick++) learning.tick(40, .01);
        assertEquals(otherServer, learning.remembered());
        // Without correction guidance, slower remembered vertical speeds start at the configured initial speed.
        assertEquals(new Limits(64, .75, .75, .03), learning.limits());
        assertEquals(Limits.UNKNOWN, learning.ceilings());
    }

    @Test void correctionGuidanceDoesNotRecoverWhileIdleButCleanFlightCanRetestIt() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(0, .4, 0), 1, true, true, 1);
        learning.corrected(BACKOFF);
        double rejectedLimit = learning.limits().ascent();

        for (int tick = 0; tick < 500; tick++) learning.tick(1, .1);
        assertTrue(learning.limits().ascent() <= rejectedLimit + EPSILON);
        double firstProbe = 0;
        double laterProbe = 0;
        for (int tick = 0; tick < 500; tick++) {
            double previous = learning.limits().ascent();
            learning.tick(1, .1);
            learning.observe(new Motion(0, learning.limits().ascent(), 0), 1, true, true, 1);
            double step = learning.limits().ascent() - previous;
            if (step > EPSILON && firstProbe == 0) firstProbe = step;
            else if (step > EPSILON) laterProbe = Math.max(laterProbe, step);
        }

        assertTrue(learning.limits().ascent() > rejectedLimit);
        assertTrue(firstProbe <= .05 + EPSILON,
            "the probe crossing remembered correction guidance must use a small step");
        assertTrue(laterProbe > .05,
            "after clearing the guidance boundary, ordinary learning speed must resume");
        assertTrue(learning.remembered().ascent() > rejectedLimit);
    }

    @Test void randomLowSpeedCorrectionDoesNotReplaceSettledFasterEvidence() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        for (int tick = 0; tick < 25; tick++) {
            learning.tick(40, .01);
            learning.observe(new Motion(0, .6, 0), 1, true, true, 1);
        }
        for (int tick = 0; tick < 25; tick++) {
            learning.tick(40, .01);
            learning.observe(new Motion(0, .1, 0), 1, true, true, 1);
        }
        Limits before = learning.limits();

        assertFalse(learning.corrected(BACKOFF),
            "a low-speed outlier below settled clean evidence is not a learned speed boundary");
        assertEquals(before, learning.limits());
        assertEquals(Limits.UNKNOWN, learning.ceilings());
    }

    @Test void horizontalCorrectionGuidanceAlsoRecoversFromCleanLevelSprint() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(2, 0, 0), 0, true, true, 1, 1);
        assertTrue(learning.corrected(BACKOFF));
        double guidance = learning.limits().horizontal();

        for (int tick = 0; tick < 79; tick++) {
            learning.tick(1, .1);
            learning.observe(new Motion(learning.limits().horizontal(), 0, 0), 0, true, true, 1, 1);
        }
        assertEquals(guidance, learning.limits().horizontal(), EPSILON);

        for (int tick = 0; tick < 200; tick++) {
            learning.tick(1, .1);
            learning.observe(new Motion(learning.limits().horizontal(), 0, 0), 0, true, true, 1, 1);
        }
        assertTrue(learning.limits().horizontal() > guidance);
        assertTrue(learning.remembered().horizontal() > guidance);
        assertEquals(guidance, learning.ceilings().horizontal(), EPSILON);
    }

    @Test void aLaterCorrectionReplacesObsoleteLowerGuidance() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(0, .4, 0), 1, true, true, 1);
        assertTrue(learning.corrected(BACKOFF));
        double oldGuidance = learning.ceilings().ascent();

        for (int tick = 0; tick < 1_200; tick++) {
            learning.tick(1, .1);
            learning.observe(new Motion(0, learning.limits().ascent(), 0), 1, true, true, 1);
        }
        assertTrue(learning.limits().ascent() * BACKOFF > oldGuidance);
        assertTrue(learning.corrected(BACKOFF));
        assertTrue(learning.ceilings().ascent() > oldGuidance,
            "successful flight beyond old guidance makes a later correction the relevant threshold");
        assertEquals(learning.ceilings().ascent(), learning.limits().ascent(), EPSILON);
    }

    @Test void idleTerrainLimitedCruiseAndBelowBoundaryFlightCannotAuthorizeFasterVerticalSprint() {
        for (int scenario = 0; scenario < 4; scenario++) {
            AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
            Limits before = learning.limits();
            for (int tick = 0; tick < 300; tick++) {
                learning.tick(40, .1);
                if (scenario == 0) learning.observe(Motion.ZERO, 0, true, true, 1);
                else if (scenario == 1) learning.observe(new Motion(0, .75, 0), 1, true, false, 1);
                else if (scenario == 2) learning.observe(new Motion(0, .75, 0), 1, false, true, 1);
                else learning.observe(new Motion(0, .2, 0), 1, true, true, 1);
            }

            assertEquals(before.ascent(), learning.limits().ascent(), EPSILON, "scenario " + scenario);
            assertEquals(before.descent(), learning.limits().descent(), EPSILON, "scenario " + scenario);
        }
    }

    @Test void cleanBoostedBoundaryFlightProbesGraduallyAndPersistsOnlySettledEvidence() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        double initial = learning.limits().ascent();
        for (int tick = 0; tick < 19; tick++) {
            learning.tick(40, .1);
            learning.observe(new Motion(0, learning.limits().ascent(), 0), 1, true, true, 1);
        }
        assertEquals(initial, learning.limits().ascent(), EPSILON);
        assertEquals(Limits.UNKNOWN, learning.remembered(),
            "motion inside the correction settlement window is not yet proven");

        boolean grew = false;
        for (int tick = 0; tick < 300; tick++) {
            double previous = learning.limits().ascent();
            learning.tick(40, .1);
            learning.observe(new Motion(0, previous, 0), 1, true, true, 1);
            double current = learning.limits().ascent();
            assertTrue(current - previous <= .25 + EPSILON,
                "one probe must not jump more than .25 blocks/tick");
            if (current > previous + EPSILON) {
                grew = true;
                assertTrue(learning.remembered().ascent() <= previous + EPSILON,
                    "the fresh probe cannot already be persisted as proven safe");
            }
        }

        assertTrue(grew, "sustained clean flight must eventually discover a faster vertical ceiling");
        assertTrue(learning.remembered().ascent() > initial);
        assertTrue(learning.remembered().ascent() <= learning.limits().ascent() + EPSILON);
        assertEquals(.75, learning.limits().descent(), EPSILON);
        assertEquals(64, learning.remembered().descent(), EPSILON);
    }

    @Test void aSelectedPercentageDoesNotProveTheUntriedFullSpeedBoundary() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        double initial = learning.limits().ascent();
        for (int tick = 0; tick < 250; tick++) {
            learning.tick(40, .01);
            learning.observe(new Motion(0, learning.limits().ascent() * .25, 0), 1, true, true, .25);
        }

        assertEquals(initial, learning.limits().ascent(), EPSILON,
            "clean motion at 25% cannot justify faster full-speed Sprint");
        assertEquals(Limits.UNKNOWN, learning.remembered(),
            "reconnecting at 100% must not trust an extrapolated speed");
    }

    @Test void aPartialPercentageSetbackLearnsTheActualSpeedWithoutExtrapolating() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
            learning.observe(new Motion(0, direction * .2, 0), direction, true, true, .25);

            assertTrue(learning.corrected(BACKOFF));
            assertTrue(vertical(learning.limits(), direction) <= .2 * BACKOFF + EPSILON,
                "a setback at .2 must not be treated as a test of .8");
            assertTrue(vertical(learning.remembered(), direction) <= .2 * BACKOFF + EPSILON);
        }
    }

    @Test void forgettingAProfileRemovesBothLearnedLimitsAndRecentMovement() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.restore(new Limits(3, .4, .6, .02));
        learning.observe(new Motion(0, .4, 0), 1, true, true, 1);

        learning.forget();

        assertEquals(64, learning.limits().horizontal(), EPSILON);
        assertEquals(.75, learning.limits().ascent(), EPSILON);
        assertEquals(.75, learning.limits().descent(), EPSILON);
        assertEquals(.05, learning.limits().verticalAcceleration(), EPSILON);
        assertEquals(Limits.UNKNOWN, learning.remembered());
        assertFalse(learning.corrected(BACKOFF));
    }

    private static double vertical(Limits limits, int direction) {
        return direction > 0 ? limits.ascent() : limits.descent();
    }
}
