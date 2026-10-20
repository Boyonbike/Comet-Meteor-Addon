package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoostSpeedLimitTest {
    private static final double EPSILON = 1e-9;

    @Test void aSetbackLearnsTheRejectedSpeedRatherThanTheRecoverySpeed() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0)); // 280 blocks/second
        double boundary = 14 * BoostSpeedLimit.BACKOFF;
        assertEquals(boundary, limit.ceiling(), EPSILON);

        // One rejection can produce a train of lower-speed corrections while the server settles.
        // These are variable recovery speeds, rather than evidence of a 60 blocks/second limit.
        for (int tick = 0; tick < 100; tick++) {
            limit.tick();
            limit.observe(new Motion(2.5 + tick % 7 * .2, 0, 0), true);
            if (tick % 10 == 0) assertFalse(limit.corrected());
            assertEquals(boundary, limit.ceiling(), EPSILON);
        }
    }

    @Test void delayedFeedbackUsesTheRecentPeakBeforeTheLastSlowerMove() {
        BoostSpeedLimit limit = new BoostSpeedLimit();
        limit.observe(new Motion(14, 0, 0), true);
        for (int tick = 0; tick < BoostSpeedLimit.FEEDBACK_TICKS; tick++) {
            limit.tick();
            limit.observe(new Motion(3, 0, 0), true);
        }

        assertTrue(limit.corrected());
        assertEquals(14 * BoostSpeedLimit.BACKOFF, limit.ceiling(), EPSILON);
    }

    @Test void mixedAscentAndDescentUseTheSameFullThreeDimensionalBudget() {
        for (double y : new double[] {-7, 7}) {
            Motion rejected = new Motion(10, y, 6);
            BoostSpeedLimit mixed = rejected(rejected);
            BoostSpeedLimit level = rejected(new Motion(rejected.length(), 0, 0));
            BoostSpeedLimit vertical = rejected(new Motion(0, rejected.length(), 0));

            assertEquals(rejected.length() * BoostSpeedLimit.BACKOFF, mixed.ceiling(), EPSILON);
            assertEquals(level.ceiling(), mixed.ceiling(), EPSILON);
            assertEquals(vertical.ceiling(), mixed.ceiling(), EPSILON);
            Motion allowed = rejected.scaled(mixed.ceiling() / rejected.length());
            assertTrue(allowed.horizontalLength() < mixed.ceiling(), "climbing consumes the shared budget");
        }
    }

    @Test void theBoundarySurvivesTheOldTenSecondCapExpiry() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
        double boundary = limit.ceiling();
        for (int tick = 0; tick < BoostSpeedLimit.HOLD_TICKS + 1000; tick++) limit.tick();

        assertTrue(limit.limited());
        assertEquals(boundary, limit.ceiling(), EPSILON, "time alone must never reopen the failed maximum");
    }

    @Test void cleanSettledFlightProbesInSmallStepsAfterTheHold() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
        double boundary = limit.ceiling();
        for (int tick = 0; tick < BoostSpeedLimit.HOLD_TICKS - 1; tick++) {
            fly(limit, boundary, true);
            assertEquals(boundary, limit.ceiling(), EPSILON);
        }
        for (int tick = 0; tick < BoostSpeedLimit.PROBE_TICKS - 1; tick++) {
            fly(limit, boundary, true);
            assertEquals(boundary, limit.ceiling(), EPSILON);
        }
        fly(limit, boundary, true);
        assertEquals(boundary + BoostSpeedLimit.PROBE_STEP, limit.ceiling(), EPSILON);

        for (int tick = 0; tick < BoostSpeedLimit.PROBE_TICKS; tick++)
            fly(limit, limit.ceiling(), true);
        assertEquals(boundary + 2 * BoostSpeedLimit.PROBE_STEP, limit.ceiling(), EPSILON);
    }

    @Test void rejectingASmallProbeRestoresItsProvenBaseWithoutAnotherFivePercentLoss() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
        double proven = limit.ceiling();
        for (int attempt = 0; attempt < 5; attempt++) {
            while (limit.holdTicksRemaining() > 0) limit.tick();
            for (int tick = 0; tick < BoostSpeedLimit.PROBE_TICKS; tick++)
                fly(limit, proven, true);
            assertEquals(proven + BoostSpeedLimit.PROBE_STEP, limit.ceiling(), EPSILON);
            fly(limit, limit.ceiling(), true);

            assertTrue(limit.corrected());
            assertEquals(proven, limit.ceiling(), EPSILON);
        }
    }

    @Test void aRealLowerBoundaryCanBeLearnedAfterTheEpisodeSettles() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
        for (int tick = 0; tick <= BoostSpeedLimit.FEEDBACK_TICKS; tick++) limit.tick();
        limit.observe(new Motion(2.5, 0, 0), true);

        assertTrue(limit.corrected());
        assertEquals(2.5 * BoostSpeedLimit.BACKOFF, limit.ceiling(), EPSILON,
            "a genuine limit below boost-start-speed must remain enforceable");
    }

    @Test void everySecondaryCorrectionRequiresAnotherFullQuietFeedbackWindow() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
        for (int episode = 0; episode < 4; episode++) {
            for (int tick = 0; tick < BoostSpeedLimit.FEEDBACK_TICKS; tick++)
                fly(limit, 3, true);
            assertFalse(limit.corrected());
            assertEquals(BoostSpeedLimit.HOLD_TICKS, limit.holdTicksRemaining());
        }
        assertEquals(14 * BoostSpeedLimit.BACKOFF, limit.ceiling(), EPSILON);
    }

    @Test void releasingBoostStillAllowsLateFeedbackWithinTheWindow() {
        BoostSpeedLimit limit = new BoostSpeedLimit();
        limit.observe(new Motion(12, 5, 0), true);
        for (int tick = 0; tick < BoostSpeedLimit.FEEDBACK_TICKS; tick++) limit.tick();

        assertTrue(limit.corrected());
        assertEquals(13 * BoostSpeedLimit.BACKOFF, limit.ceiling(), EPSILON);
    }

    @Test void expiredMovementCannotTeachALimitAfterBoostHasStopped() {
        BoostSpeedLimit limit = new BoostSpeedLimit();
        limit.observe(new Motion(14, 0, 0), true);
        for (int tick = 0; tick <= BoostSpeedLimit.FEEDBACK_TICKS; tick++) limit.tick();

        assertFalse(limit.corrected());
        assertFalse(limit.limited());
    }

    @Test void terrainGlideAndCollisionClippedMotionCannotBecomeRejectionEvidence() {
        BoostSpeedLimit limit = new BoostSpeedLimit();
        for (int tick = 0; tick < 25; tick++) fly(limit, 3, false);

        assertFalse(limit.corrected());
        assertFalse(limit.limited());
    }

    @Test void missingOrUncleanFlightRestartsTheProbeProof() {
        for (boolean missing : new boolean[] {false, true}) {
            BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
            double boundary = limit.ceiling();
            while (limit.holdTicksRemaining() > 0) limit.tick();
            for (int tick = 0; tick < BoostSpeedLimit.PROBE_TICKS - 1; tick++) fly(limit, boundary, true);
            if (missing) limit.tick();
            else fly(limit, boundary, false);
            for (int tick = 0; tick < BoostSpeedLimit.PROBE_TICKS - 1; tick++) fly(limit, boundary, true);

            assertEquals(boundary, limit.ceiling(), EPSILON);
            fly(limit, boundary, true);
            assertEquals(boundary + BoostSpeedLimit.PROBE_STEP, limit.ceiling(), EPSILON);
        }
    }

    @Test void flyingBelowTheBoundaryCannotAuthorizeFasterMovement() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
        double boundary = limit.ceiling();
        for (int tick = 0; tick < 1000; tick++) fly(limit, boundary * .9, true);

        assertEquals(boundary, limit.ceiling(), EPSILON);
    }

    @Test void duplicateMoveEventsCannotAccelerateTheProbeSchedule() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
        double boundary = limit.ceiling();
        while (limit.holdTicksRemaining() > 0) limit.tick();
        for (int tick = 0; tick < BoostSpeedLimit.PROBE_TICKS - 1; tick++) {
            limit.tick();
            for (int move = 0; move < 10; move++) limit.observe(new Motion(boundary, 0, 0), true);
        }

        assertEquals(boundary, limit.ceiling(), EPSILON);
        fly(limit, boundary, true);
        assertEquals(boundary + BoostSpeedLimit.PROBE_STEP, limit.ceiling(), EPSILON);
    }

    @Test void invalidatingFeedbackPreservesTheBoundaryAndDiscardsStaleMovement() {
        BoostSpeedLimit unknown = new BoostSpeedLimit();
        unknown.observe(new Motion(3, 0, 0), true);
        unknown.invalidateEvidence();
        assertFalse(unknown.corrected());
        assertFalse(unknown.limited());

        BoostSpeedLimit known = rejected(new Motion(14, 0, 0));
        double boundary = known.ceiling();
        known.invalidateEvidence();
        assertEquals(boundary, known.ceiling(), EPSILON);
        assertFalse(known.corrected());
        assertEquals(boundary, known.ceiling(), EPSILON);
    }

    @Test void disablingAutoCapBypassesLearningAndRestoringItKeepsTheKnownBoundary() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
        double boundary = limit.ceiling();
        limit.configure(false);
        assertFalse(limit.limited());
        limit.observe(new Motion(3, 0, 0), true);
        assertFalse(limit.corrected());
        for (int tick = 0; tick < 1000; tick++) limit.tick();
        limit.configure(true);

        assertEquals(boundary, limit.ceiling(), EPSILON);
        assertFalse(limit.corrected(), "disabled movement cannot become evidence when reenabled");
    }

    @Test void resettingForANewSessionForgetsTheBoundaryAndPendingEvidence() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
        limit.reset();

        assertFalse(limit.limited());
        assertEquals(0, limit.holdTicksRemaining());
        assertFalse(limit.corrected());
    }

    @Test void aManualResetClearsTheCapAndDrainsOldCorrectionsBeforeLearningFreshFlight() {
        BoostSpeedLimit limit = rejected(new Motion(14, 0, 0));
        limit.forget();
        assertFalse(limit.limited(), "the reset must clear Boost capped immediately");
        assertEquals(0, limit.holdTicksRemaining());

        for (int tick = 0; tick <= BoostSpeedLimit.FEEDBACK_TICKS; tick++) {
            if (tick > 0) limit.tick();
            limit.observe(new Motion(3 + tick % 3 * .2, 0, 0), true);
            assertFalse(limit.corrected(), "pre-reset feedback must not restore the old cap");
            assertFalse(limit.limited());
        }

        limit.tick();
        assertFalse(limit.corrected(), "an arrival before new movement provides no speed evidence");
        limit.observe(new Motion(13, 4, 0), true);
        limit.tick();
        assertTrue(limit.corrected(), "new feedback must resume learning after the old window drains");
        assertEquals(Math.hypot(13, 4) * BoostSpeedLimit.BACKOFF, limit.ceiling(), EPSILON);
    }

    private static BoostSpeedLimit rejected(Motion motion) {
        BoostSpeedLimit limit = new BoostSpeedLimit();
        limit.observe(motion, true);
        assertTrue(limit.corrected());
        return limit;
    }

    private static void fly(BoostSpeedLimit limit, double speed, boolean clean) {
        limit.tick();
        limit.observe(new Motion(speed, 0, 0), clean);
    }
}
