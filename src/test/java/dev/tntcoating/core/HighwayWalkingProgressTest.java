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

    @Test void nonpositiveTimeoutIsRejected() {
        HighwayWalkingProgress progress = new HighwayWalkingProgress();

        assertThrows(IllegalArgumentException.class, () -> progress.stalled(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> progress.stalled(null, 0, -1));
    }
}
