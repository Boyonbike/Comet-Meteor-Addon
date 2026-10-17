package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static dev.tntcoating.core.TrailTracker.State.*;
import static org.junit.jupiter.api.Assertions.*;

class TrailTrackerTest {
    private static final float EAST = -90;

    @Test void acquiresOnlyAfterTenUniqueTrailChunksInTheWindow() {
        TrailTracker tracker = new TrailTracker();
        for (int i = 0; i < 9; i++)
            assertTrue(tracker.onTrailChunk(i, 0, i * 100L, 0, 8, EAST));
        assertEquals(SEARCHING, tracker.update(800, 0, 8, EAST).state());
        assertTrue(tracker.onTrailChunk(9, 0, 900, 0, 8, EAST));
        var result = tracker.update(900, 0, 8, EAST);
        assertEquals(FOLLOWING, result.state());
        assertEquals(EAST, result.yaw(), 1e-5);
        assertTrue(result.targetX() >= 64, "Steer to a distant point on the line, rather than the arrival centroid.");
        assertEquals(8, result.targetZ(), 1e-5);
        assertEquals(EAST, tracker.axisYaw(), 1e-5);
    }

    @Test void sparseNoiseCannotAccumulateAcrossAcquisitionWindows() {
        TrailTracker tracker = new TrailTracker();
        for (int i = 0; i < 20; i++)
            tracker.onTrailChunk(i, 0, i * 1_000L, 0, 8, EAST);
        var result = tracker.update(19_000, 0, 8, EAST);
        assertEquals(SEARCHING, result.state());
        assertEquals(6, result.acquisitionChunks());
        // Searching does not give up merely because no trail was ever acquired.
        assertEquals(SEARCHING, tracker.update(100_000, 0, 8, EAST).state());
    }

    @Test void acquisitionIncludesExactWindowBoundaryThenExpiresIt() {
        TrailTracker tracker = tracker(2, 5_000, 20, 32);
        tracker.onTrailChunk(0, 0, 0, 0, 8, EAST);
        assertEquals(1, tracker.update(5_000, 0, 8, EAST).acquisitionChunks());
        assertEquals(0, tracker.update(5_001, 0, 8, EAST).acquisitionChunks());
        tracker.onTrailChunk(1, 0, 5_001, 0, 8, EAST);
        assertEquals(SEARCHING, tracker.update(5_001, 0, 8, EAST).state());
        tracker.onTrailChunk(2, 0, 10_001, 0, 8, EAST);
        assertEquals(FOLLOWING, tracker.update(10_001, 0, 8, EAST).state());
    }

    @Test void repeatedChunkLoadsNeitherAcquireNorRefreshTheLostTimer() {
        TrailTracker tracker = tracker(1, 5_000, 20, 32);
        tracker.onTrailChunk(1, 0, 0, 0, 8, EAST);
        assertFalse(tracker.onTrailChunk(1, 0, 4_999, 0, 8, EAST));
        assertEquals(RECOVERING, tracker.update(5_000, 0, 8, EAST).state());
        assertFalse(tracker.onTrailChunk(1, 0, 29_999, 0, 8, EAST));
        assertEquals(EXHAUSTED, tracker.update(30_000, 0, 8, EAST).state());

        TrailTracker searching = new TrailTracker();
        for (int i = 0; i < 20; i++) searching.onTrailChunk(1, 0, i * 100L, 0, 8, EAST);
        assertEquals(1, searching.update(2_000, 0, 8, EAST).acquisitionChunks());
        assertEquals(SEARCHING, searching.update(2_000, 0, 8, EAST).state());
    }

    @Test void duplicateSuppressionOutlivesTheSteeringHistory() {
        TrailTracker tracker = tracker(1, 5_000, 2, 32);
        for (int i = 0; i < 10; i++) tracker.onTrailChunk(i, 0, i, 0, 8, EAST);
        assertEquals(2, tracker.update(10, 0, 8, EAST).trailChunks());
        assertFalse(tracker.onTrailChunk(0, 0, 11, 0, 8, EAST));
    }

    @Test void keepsTheSupportedLineWhenTheRecentArrivalWindowMovesToSideChunks() {
        TrailTracker tracker = tracker(1, 5_000, 3, 32);
        for (int i = 1; i <= 10; i++) tracker.onTrailChunk(i, 0, i, 0, 8, EAST);
        assertEquals(EAST, tracker.update(10, 0, 8, EAST).yaw(), 1e-5);
        for (int i = 1; i <= 12; i++) tracker.onTrailChunk(i, 4, i + 10, 0, 8, EAST);
        var result = tracker.update(22, 0, 8, EAST);
        assertEquals(3, result.trailChunks(), "The recent arrival history remains bounded.");
        assertEquals(EAST, result.yaw(), 1e-5, "Side chunks must not outweigh a supported straight continuation.");
    }

    @Test void averagesForksWithoutReversingDirection() {
        TrailTracker tracker = tracker(1, 5_000, 20, 32);
        for (int i = 1; i <= 5; i++) {
            tracker.onTrailChunk(i, i, i * 2L, 0, 8, EAST);
            tracker.onTrailChunk(i, -i, i * 2L + 1, 0, 8, EAST);
        }
        var result = tracker.update(11, 0, 8, EAST);
        assertEquals(EAST, result.yaw(), 1e-5);
        assertTrue(result.targetX() > 0);
        assertEquals(8, result.targetZ(), 1e-5);
    }

    @Test void rejectsFarBehindChunksAndPrunesThemAsPlayerPasses() {
        TrailTracker tracker = tracker(2, 5_000, 20, 32);
        assertFalse(tracker.onTrailChunk(-3, 0, 0, 0, 8, EAST));
        assertEquals(0, tracker.update(0, 0, 8, EAST).acquisitionChunks());
        tracker.onTrailChunk(0, 0, 1, 0, 8, EAST);
        tracker.onTrailChunk(1, 0, 2, 0, 8, EAST);
        var result = tracker.update(3, 100, 8, EAST);
        assertEquals(0, result.trailChunks());
        assertEquals(0, result.acquisitionChunks());
        assertEquals(EAST, result.yaw(), 1e-5);
        assertTrue(result.targetX() > 100);
        assertFalse(tracker.onTrailChunk(-2, 0, 4_999, 100, 8, EAST));
        assertEquals(RECOVERING, tracker.update(5_002, 100, 8, EAST).state());
    }

    @Test void aSinglePassedSideChunkCannotCauseATurn() {
        TrailTracker tracker = tracker(1, 5_000, 20, 32);
        tracker.onTrailChunk(0, 1, 0, 16, 8, EAST);
        var result = tracker.update(0, 16, 8, EAST);
        assertTrue(result.targetX() >= 24 - 1e-6);
        assertEquals(8, result.targetZ(), 1e-5);
        assertEquals(EAST, result.yaw(), 1e-5);
    }

    @Test void aShortGapMaintainsFollowingThenRecoveryCirclesByElapsedTime() {
        TrailTracker tracker = tracker(1, 5_000, 20, 32);
        tracker.onTrailChunk(1, 0, 1_000, 0, 8, EAST);
        assertEquals(FOLLOWING, tracker.update(5_999, 0, 8, EAST).state());
        var start = tracker.update(6_000, 0, 8, EAST);
        var later = tracker.update(9_000, 0, 8, EAST);
        assertEquals(RECOVERING, start.state());
        assertEquals(EAST, start.yaw(), 1e-5);
        assertEquals(-30, later.yaw(), 1e-5); // 20 degrees/second for three seconds.
        assertEquals(RECOVERING, tracker.update(30_999, 0, 8, EAST).state());
        assertEquals(EXHAUSTED, tracker.update(31_000, 0, 8, EAST).state());
    }

    @Test void recoveryDoesNotDependOnTickFrequency() {
        TrailTracker frequent = tracker(1, 5_000, 20, 32);
        TrailTracker sparse = tracker(1, 5_000, 20, 32);
        frequent.onTrailChunk(1, 0, 0, 0, 8, EAST);
        sparse.onTrailChunk(1, 0, 0, 0, 8, EAST);
        for (int now = 5_000; now < 10_000; now += 50) frequent.update(now, 0, 8, EAST);
        assertEquals(frequent.update(10_000, 0, 8, EAST).yaw(),
            sparse.update(10_000, 0, 8, EAST).yaw(), 1e-5);
    }

    @Test void aNewTrailChunkReacquiresAndRestartsTheSilenceTimer() {
        TrailTracker tracker = tracker(2, 5_000, 20, 32);
        tracker.onTrailChunk(1, 0, 0, 0, 8, EAST);
        tracker.onTrailChunk(2, 0, 100, 0, 8, EAST);
        assertEquals(RECOVERING, tracker.update(6_000, 0, 8, EAST).state());
        assertTrue(tracker.onTrailChunk(3, 0, 6_100, 0, 8, EAST));
        assertEquals(FOLLOWING, tracker.update(6_100, 0, 8, EAST).state());
        assertEquals(FOLLOWING, tracker.update(11_099, 0, 8, EAST).state());
        assertEquals(RECOVERING, tracker.update(11_100, 0, 8, EAST).state());
        assertEquals(EXHAUSTED, tracker.update(36_100, 0, 8, EAST).state());
    }

    @Test void exhaustionIsTerminalAndResetClearsAllRunState() {
        TrailTracker tracker = tracker(1, 5_000, 20, 32);
        tracker.onTrailChunk(1, 0, 100, 0, 8, EAST);
        tracker.update(30_100, 0, 8, EAST);
        assertFalse(tracker.onTrailChunk(2, 0, 30_101, 0, 8, EAST));
        assertEquals(EXHAUSTED, tracker.update(30_102, 0, 8, EAST).state());
        tracker.reset();
        var reset = tracker.update(0, 0, 8, EAST);
        assertEquals(SEARCHING, reset.state());
        assertEquals(0, reset.trailChunks());
        assertEquals(0, reset.acquisitionChunks());
        assertTrue(tracker.onTrailChunk(1, 0, 0, 0, 8, EAST));
        assertEquals(FOLLOWING, tracker.update(0, 0, 8, EAST).state());
    }

    @Test void negativeAndLargeChunkCoordinatesKeepStraightTargetsWithoutOverflow() {
        TrailTracker negative = tracker(1, 5_000, 20, 32);
        for (int x = -6; x <= -2; x++) negative.onTrailChunk(x, -3, x + 6, -100, -40, EAST);
        var result = negative.update(4, -100, -40, EAST);
        assertTrue(result.targetX() > -100);
        assertEquals(-40, result.targetZ(), 1e-5);
        assertEquals(EAST, result.yaw(), 1e-5);

        TrailTracker large = tracker(1, 5_000, 20, 32);
        double playerX = 3_199_999_900.0;
        for (int x = 199_999_995; x <= 200_000_001; x++)
            large.onTrailChunk(x, -200_000_000, x - 199_999_995, playerX, -3_199_999_992.0, EAST);
        var extreme = large.update(6, playerX, -3_199_999_992.0, EAST);
        assertTrue(extreme.targetX() > playerX);
        assertEquals(-3_199_999_992.0, extreme.targetZ(), 1e-5);
        assertEquals(EAST, extreme.yaw(), 1e-5);
    }

    @Test void longExplorationKeepsHistoryAndAcquisitionBounded() {
        TrailTracker tracker = new TrailTracker();
        for (int i = 0; i < 10_000; i++) tracker.onTrailChunk(i, 0, i, 0, 8, EAST);
        var result = tracker.update(10_000, 0, 8, EAST);
        assertEquals(20, result.trailChunks());
        assertEquals(10, result.acquisitionChunks());
        assertFalse(tracker.onTrailChunk(9_999, 0, 10_001, 0, 8, EAST));
        // The deliberately bounded duplicate cache eventually forgets remote old chunks.
        assertTrue(tracker.onTrailChunk(0, 0, 10_002, 0, 8, EAST));
    }

    @Test void validatesConfigurationCoordinatesAndMonotonicTime() {
        assertThrows(IllegalArgumentException.class, () -> tracker(0, 5_000, 20, 32));
        assertThrows(IllegalArgumentException.class, () -> tracker(1, 0, 20, 32));
        assertThrows(IllegalArgumentException.class, () -> tracker(1, 5_000, 0, 32));
        assertThrows(IllegalArgumentException.class, () -> tracker(1, 5_000, 20, Double.NaN));
        TrailTracker tracker = new TrailTracker();
        assertThrows(IllegalArgumentException.class, () -> tracker.update(0, Double.NaN, 0, 0));
        tracker.update(10, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> tracker.update(9, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> tracker.onTrailChunk(1, 0, 10, 11, 0, 0, 0));
    }

    @Test void aNewAcquisitionDoesNotIncludeOldAheadHistory() {
        TrailTracker tracker = tracker(2, 5_000, 20, 32);
        tracker.onTrailChunk(100, 100, 0, 0, 8, EAST);
        tracker.onTrailChunk(1, 0, 6_000, 0, 8, EAST);
        tracker.onTrailChunk(2, 0, 6_100, 0, 8, EAST);
        var result = tracker.update(6_100, 0, 8, EAST);
        assertEquals(FOLLOWING, result.state());
        assertEquals(2, result.trailChunks());
        assertEquals(EAST, result.yaw(), 1e-5);
    }

    @Test void delayedClassificationsCannotTurnSparseArrivalsIntoAFreshBurst() {
        TrailTracker tracker = tracker(3, 5_000, 20, 32);
        tracker.onTrailChunk(1, 0, 10_000, 0, 0, 8, EAST);
        tracker.onTrailChunk(2, 0, 10_000, 4_000, 0, 8, EAST);
        tracker.onTrailChunk(3, 0, 10_000, 8_000, 0, 8, EAST);
        var result = tracker.update(10_000, 0, 8, EAST);
        assertEquals(SEARCHING, result.state());
        assertEquals(1, result.acquisitionChunks());
        assertEquals(1, result.trailChunks());
        assertFalse(tracker.onTrailChunk(4, 0, 40_000, 10_000, 0, 8, EAST));
    }

    @Test void olderClassificationsNeverMoveLastUsefulTimeBackwardsOrReacquireStaleTrail() {
        TrailTracker tracker = tracker(1, 5_000, 20, 32);
        tracker.onTrailChunk(1, 0, 10_000, 9_000, 0, 8, EAST);
        tracker.onTrailChunk(2, 0, 10_001, 7_000, 0, 8, EAST);
        assertEquals(FOLLOWING, tracker.update(13_999, 0, 8, EAST).state());
        assertEquals(RECOVERING, tracker.update(14_000, 0, 8, EAST).state());
        tracker.onTrailChunk(3, 0, 15_000, 9_500, 0, 8, EAST);
        assertEquals(RECOVERING, tracker.update(15_000, 0, 8, EAST).state());
        assertEquals(EXHAUSTED, tracker.update(39_500, 0, 8, EAST).state());
    }

    @Test void negativeMonotonicClockEpochWorks() {
        TrailTracker tracker = tracker(1, 5_000, 20, 32);
        tracker.onTrailChunk(1, 0, -10_000, -10_000, 0, 8, EAST);
        assertEquals(FOLLOWING, tracker.update(-5_001, 0, 8, EAST).state());
        assertEquals(RECOVERING, tracker.update(-5_000, 0, 8, EAST).state());
    }

    @Test void timelyArrivalDrainedAfterGiveUpDeadlineKeepsFollowing() {
        TrailTracker tracker = tracker(1, 5_000, 20, 32);
        tracker.onTrailChunk(1, 0, 0, 0, 8, EAST);
        assertEquals(RECOVERING, tracker.update(29_900, 0, 8, EAST).state());
        assertTrue(tracker.onTrailChunk(2, 0, 30_010, 29_990, 0, 8, EAST));
        assertEquals(FOLLOWING, tracker.update(30_010, 0, 8, EAST).state());
        assertEquals(RECOVERING, tracker.update(59_989, 0, 8, EAST).state());
        assertEquals(EXHAUSTED, tracker.update(59_990, 0, 8, EAST).state());
    }

    @Test void arrivalAtOrAfterGiveUpDeadlineCannotReviveRun() {
        for (long received : new long[] {30_000, 30_001}) {
            TrailTracker tracker = tracker(1, 5_000, 20, 32);
            tracker.onTrailChunk(1, 0, 0, 0, 8, EAST);
            assertFalse(tracker.onTrailChunk(2, 0, 30_010, received, 0, 8, EAST));
            assertEquals(EXHAUSTED, tracker.update(30_010, 0, 8, EAST).state());
        }
    }

    @Test void previouslyExhaustedRunRejectsEvenATimelyDelayedArrival() {
        TrailTracker tracker = tracker(1, 5_000, 20, 32);
        tracker.onTrailChunk(1, 0, 0, 0, 8, EAST);
        assertEquals(EXHAUSTED, tracker.update(30_000, 0, 8, EAST).state());
        assertFalse(tracker.onTrailChunk(2, 0, 30_010, 29_990, 0, 8, EAST));
        assertEquals(EXHAUSTED, tracker.update(30_010, 0, 8, EAST).state());
    }

    @Test void staleClassificationCannotReviveAfterTheDeadline() {
        TrailTracker tracker = tracker(1, 5_000, 20, 32);
        tracker.onTrailChunk(1, 0, 0, 0, 8, EAST);
        assertFalse(tracker.onTrailChunk(2, 0, 30_010, 0, 0, 8, EAST));
        assertEquals(EXHAUSTED, tracker.update(30_010, 0, 8, EAST).state());
    }

    private static TrailTracker tracker(int acquisition, long window, int history, double behind) {
        return new TrailTracker(new TrailTracker.Config(acquisition, window, history, behind,
            5_000, 30_000, 20));
    }
}
