package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HighwayWalkingProgressTest {
    @Test void advancingRouteNodesCanDetourForLongerThanThirtySeconds() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();

        // A sixty-second detour may initially head away from the destination.
        // Completing its walking movements still demonstrates useful progress.
        for (int tick = 0; tick < 1200; tick++) {
            assertFalse(progress.stalled(path, tick / 20, 600));
        }
    }

    @Test void unchangedMovementIndexStallsOnTheConfiguredTick() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();

        assertFalse(progress.stalled(path, 5, 3));
        assertFalse(progress.stalled(path, 5, 3));
        assertTrue(progress.stalled(path, 5, 3));
        assertTrue(progress.stalled(path, 5, 3));
    }

    @Test void completingANewMovementRestartsTheStallTimeout() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();

        assertFalse(progress.stalled(path, 0, 3));
        assertFalse(progress.stalled(path, 0, 3));
        assertFalse(progress.stalled(path, 1, 3));
        assertFalse(progress.stalled(path, 1, 3));
        assertFalse(progress.stalled(path, 1, 3));
        assertTrue(progress.stalled(path, 1, 3));
    }

    @Test void recalculatingPathsCannotResetTheStallTimeout() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();

        assertFalse(progress.stalled(new Object(), 0, 3));
        assertFalse(progress.stalled(new Object(), 100, 3));
        assertTrue(progress.stalled(new Object(), 200, 3));
    }

    @Test void equalButDifferentPathsEstablishSeparateBaselines() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();

        assertFalse(progress.stalled(new String("path"), 0, 3));
        assertFalse(progress.stalled(new String("path"), 1, 3));
        assertTrue(progress.stalled(new String("path"), 2, 3));
    }

    @Test void backwardOscillationNeverCountsAnOldMovementAgain() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();

        assertFalse(progress.stalled(path, 5, 4));
        assertFalse(progress.stalled(path, 3, 4));
        assertFalse(progress.stalled(path, 5, 4));
        assertTrue(progress.stalled(path, 3, 4));
    }

    @Test void missingPathSnapshotsCountAsStalledTicks() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();

        assertFalse(progress.stalled(null, 0, 2));
        assertTrue(progress.stalled(null, 0, 2));
    }

    @Test void missingSnapshotsDoNotForgetTheSamePathsCompletedMovements() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();

        assertFalse(progress.stalled(path, 5, 4));
        assertFalse(progress.stalled(null, 0, 4));
        assertFalse(progress.stalled(path, 3, 4));
        assertTrue(progress.stalled(path, 5, 4));
    }

    @Test void resettingStartsAFreshRecoveryAndForgetsItsOldPath() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();
        assertFalse(progress.stalled(path, 8, 2));
        assertTrue(progress.stalled(path, 8, 2));

        progress.reset();

        assertFalse(progress.stalled(path, 0, 2));
        assertFalse(progress.stalled(path, 1, 2));
        assertFalse(progress.stalled(path, 1, 2));
        assertTrue(progress.stalled(path, 1, 2));
    }

    @Test void normalEatingPreservesThePreviousStallDebt() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();
        assertFalse(progress.stalled(path, 0, 3));
        assertFalse(progress.stalled(path, 0, 3));

        // One normal apple takes about 32 ticks, even if eating starts near the deadline.
        for (int tick = 0; tick < 32; tick++) {
            assertFalse(progress.stalled(path, 0, 3, true));
        }

        assertTrue(progress.stalled(path, 0, 3, false));
    }

    @Test void continuousEatingOnlyDelaysTheWatchdogByFiveSeconds() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();

        for (int tick = 0; tick < 100; tick++) {
            assertFalse(progress.stalled(path, 0, 3, true));
        }
        assertFalse(progress.stalled(path, 0, 3, true));
        assertFalse(progress.stalled(path, 0, 3, true));
        assertTrue(progress.stalled(path, 0, 3, true));
    }

    @Test void stoppingAndRestartingEatingDoesNotRefreshTheGrace() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();

        for (int tick = 0; tick < 60; tick++) {
            assertFalse(progress.stalled(path, 0, 3, true));
        }
        assertFalse(progress.stalled(path, 0, 3, false));
        for (int tick = 0; tick < 40; tick++) {
            assertFalse(progress.stalled(path, 0, 3, true));
        }
        assertFalse(progress.stalled(path, 0, 3, true));
        assertTrue(progress.stalled(path, 0, 3, true));
    }

    @Test void recalculatingPathsCannotRefreshTheEatingGrace() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();

        for (int tick = 0; tick < 100; tick++) {
            assertFalse(progress.stalled(path, 0, 3, true));
        }
        assertFalse(progress.stalled(new Object(), 20, 3, true));
        assertFalse(progress.stalled(new Object(), 40, 3, true));
        assertTrue(progress.stalled(new Object(), 60, 3, true));
    }

    @Test void completingANewMovementRefreshesBothTheStallBudgetAndEatingGrace() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();

        for (int tick = 0; tick < 100; tick++) {
            assertFalse(progress.stalled(path, 0, 3, true));
        }
        assertFalse(progress.stalled(path, 0, 3, false));
        assertFalse(progress.stalled(path, 0, 3, false));
        assertFalse(progress.stalled(path, 1, 3, false));

        for (int tick = 0; tick < 100; tick++) {
            assertFalse(progress.stalled(path, 1, 3, true));
        }
        assertFalse(progress.stalled(path, 1, 3, false));
        assertFalse(progress.stalled(path, 1, 3, false));
        assertTrue(progress.stalled(path, 1, 3, false));
    }

    @Test void missingPathsStillHaveOnlyAFiniteEatingGrace() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();

        for (int tick = 0; tick < 100; tick++) {
            assertFalse(progress.stalled(null, 0, 2, true));
        }
        assertFalse(progress.stalled(null, 0, 2, true));
        assertTrue(progress.stalled(null, 0, 2, true));
    }

    @Test void resettingStartsAFreshEatingGrace() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();
        Object path = new Object();
        for (int tick = 0; tick < 100; tick++) {
            assertFalse(progress.stalled(path, 0, 1, true));
        }
        assertTrue(progress.stalled(path, 0, 1, true));

        progress.reset();

        for (int tick = 0; tick < 100; tick++) {
            assertFalse(progress.stalled(path, 0, 1, true));
        }
        assertTrue(progress.stalled(path, 0, 1, true));
    }

    @Test void nonpositiveTimeoutIsRejected() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();

        assertThrows(IllegalArgumentException.class, () -> progress.stalled(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> progress.stalled(null, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> progress.stalled(null, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> progress.stalled(null, 0, -1, true));
    }
}
