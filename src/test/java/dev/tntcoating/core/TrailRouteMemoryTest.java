package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrailRouteMemoryTest {
    @Test void remembersAllChunksBetweenFastPositionUpdates() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.record(328, 8);
        assertEquals(21, memory.size());
        for (int x = 0; x <= 20; x++) assertTrue(memory.isVisitedChunk(x, 0));
        assertFalse(memory.isVisitedChunk(21, 0));
    }

    @Test void reverseMovementRecordsTheSameUndirectedRoute() {
        var forward = new TrailRouteMemory();
        var reverse = new TrailRouteMemory();
        forward.record(-248, -88);
        forward.record(248, 104);
        reverse.record(248, 104);
        reverse.record(-248, -88);
        assertEquals(forward.size(), reverse.size());
        for (int x = -20; x <= 20; x++) for (int z = -10; z <= 10; z++)
            assertEquals(forward.isVisitedChunk(x, z), reverse.isVisitedChunk(x, z));
    }

    @Test void catchesAFlownTrailFromEitherDirectionButAllowsASteepCrossing() {
        var memory = horizontalRoute();
        assertTrue(memory.wouldRetrace(-128, 8, 384, 8));
        assertTrue(memory.wouldRetrace(384, 8, -128, 8));
        assertFalse(memory.wouldRetrace(104, -128, 104, 128), "a perpendicular crossing is not a repeat");
        assertFalse(memory.wouldRetrace(104, 128, 104, -128));
        assertTrue(memory.wouldRetrace(-128, -40, 384, 60), "a shallow crossing runs alongside the route");
        assertFalse(memory.wouldRetrace(-128, 168, 384, 168));
    }

    @Test void legacyCellsWithoutDirectionsBlockParallelAndShallowPassesButNotSquareCrossings() {
        var memory = TrailRouteMemory.restore(longHorizontalRoute().snapshot().keys());
        assertTrue(memory.wouldRetrace(-128, 8, 1_200, 8));
        assertTrue(memory.wouldRetrace(1_008, 108, 8, 108), "a parallel pass 100 blocks to the side");
        assertTrue(memory.wouldRetrace(200, -60, 700, 120), "a 20 degree crossing runs alongside the route");
        assertFalse(memory.wouldRetrace(504, -128, 504, 128), "the geometry of a square crossing is clear");
    }

    @Test void onlyEarlierSessionsCountAsEarlierFlightsUntilANewSessionStarts() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.record(520, 8);
        assertFalse(memory.isNearEarlierFlight(10, 3, 7), "this session's own path is tracked by the explorer");
        memory.startSession();
        assertTrue(memory.isNearEarlierFlight(10, 3, 7));
        assertFalse(memory.isNearEarlierFlight(10, 11, 7));
        memory.record(520, 520);
        assertFalse(memory.isNearEarlierFlight(32, 30, 7), "flown after the new session started");
        assertTrue(TrailRouteMemory.restore(memory.snapshot().keys()).isNearEarlierFlight(32, 30, 7), "a loaded file is earlier");
        var revision = memory.revision();
        memory.startSession();
        assertEquals(revision, memory.revision(), "starting a session changes nothing that is saved");
    }

    @Test void briefHistoryIsNotARouteButAShortRunAlongTheRayIs() {
        var memory = new TrailRouteMemory();
        // A 20-block hop in the same direction, 60 blocks to the side of the proposed line.
        memory.record(300, 68);
        memory.record(320, 68);
        assertFalse(memory.wouldRetrace(8, 8, 520, 8), "one hop cannot close a whole direction");
        memory.record(400, 68);
        assertTrue(memory.wouldRetrace(8, 8, 520, 8), "100 blocks of parallel flight is a route");
    }

    @Test void startingOnTheRouteCanLeaveForwardButCannotReverseAlongIt() {
        var memory = horizontalRoute();
        assertFalse(memory.wouldRetrace(264, 8, 520, 8));
        assertTrue(memory.wouldRetrace(264, 8, 8, 8));
        assertFalse(memory.wouldRetrace(264, 8, 264, 264));
    }

    @Test void aNewFlightOnAPreviousRouteMustDepartInsteadOfRetracingIt() {
        var memory = horizontalRoute();
        memory.breakContinuity();
        memory.record(136, 8);
        assertTrue(memory.wouldRetrace(136, 8, 392, 8));
        assertTrue(memory.wouldRetrace(136, 8, -120, 8));
        assertFalse(memory.wouldRetrace(136, 8, 136, 264));
    }

    @Test void recentHistoryElsewhereIsNotIgnoredAsAWholeTail() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.record(264, 8);
        memory.record(264, 264);
        memory.record(8, 264);
        assertTrue(memory.wouldRetrace(8, 264, 264, 264), "back along the last leg");
        assertTrue(memory.wouldRetrace(8, 264, 264, 8), "diagonally alongside the earlier legs");
        assertFalse(memory.wouldRetrace(8, 264, -248, 264));
        assertFalse(memory.wouldRetrace(8, 264, 8, -8), "a steep crossing of the first leg is allowed");
    }

    @Test void exactCorridorRejectsAdjacentParallelPassesInBothDirections() {
        var memory = horizontalRoute();
        assertTrue(memory.wouldRetrace(-128, 40, 384, 40, 2, 48));
        assertTrue(memory.wouldRetrace(384, -24, -128, -24, 2, 48));
        assertFalse(memory.wouldRetrace(-128, 72, 384, 72, 2, 48));
    }

    @Test void wideCorridorRejectsTheHundredBlockOffsetReturnInEitherDirection() {
        var memory = longHorizontalRoute();
        assertTrue(memory.wouldRetrace(1_008, 108, 8, 108));
        assertTrue(memory.wouldRetrace(8, -92, 1_008, -92));
        assertTrue(memory.wouldRetrace(1_008, -92, 8, -92));
        assertTrue(memory.wouldRetrace(8, 108, 1_008, 108));
        assertFalse(memory.wouldRetrace(8, 168, 1_008, 168));
    }

    @Test void broadCorridorDoesNotBlockForwardExtensionOrOutwardEscapeAtTheFrontier() {
        var memory = longHorizontalRoute();
        assertFalse(memory.wouldRetrace(1_008, 8, 1_520, 8));
        assertFalse(memory.wouldRetrace(1_008, 8, 1_008, 520));
        assertFalse(memory.wouldRetrace(1_008, 8, 1_008, -504));
        assertFalse(memory.wouldRetrace(1_008, 108, 1_520, 108));
        assertTrue(memory.wouldRetrace(1_008, 108, 496, 108));
        assertTrue(memory.wouldRetrace(1_008, 8, 496, 520));
    }

    @Test void aLongStraightTailCanContinueOrDepartAtEveryDegreeAndSubChunkOffset() {
        for (double offset : new double[] { 0.01, 8, 15.99 }) {
            for (int degrees = 0; degrees < 360; degrees++) {
                double directionX = Math.cos(Math.toRadians(degrees));
                double directionZ = Math.sin(Math.toRadians(degrees));
                double x = offset + directionX * 1_000;
                double z = offset + directionZ * 1_000;
                var memory = new TrailRouteMemory();
                memory.record(offset, offset);
                memory.record(x, z);
                String context = "heading " + degrees + ", offset " + offset;
                assertFalse(memory.wouldRetrace(x, z, x + directionX * 512, z + directionZ * 512), context);
                assertFalse(memory.wouldRetrace(x, z, x - directionZ * 512, z + directionX * 512), context);
                assertFalse(memory.wouldRetrace(x, z, x + directionZ * 512, z - directionX * 512), context);
                assertTrue(memory.wouldRetrace(x, z, x - directionX * 512, z - directionZ * 512), context);
            }
        }
    }

    @Test void aGradualTurnCannotUseItsNewTailToHideTheOldParallelTrail() {
        var memory = longHorizontalRoute();
        memory.record(1_056, 40);
        memory.record(1_056, 88);
        memory.record(1_024, 108);
        assertTrue(memory.wouldRetrace(1_024, 108, 512, 108));
    }

    @Test void oldChunkOnlySavesGainTheWideCorridorWithoutRewritingHistory() {
        var original = longHorizontalRoute();
        var restored = TrailRouteMemory.restore(original.snapshot().keys());
        assertEquals(original.size(), restored.size());
        assertTrue(restored.wouldRetrace(1_008, 108, 8, 108));
        assertFalse(restored.wouldRetrace(1_008, 8, 1_520, 8));
    }

    @Test void aClosedLoopCanBeCrossedButNotRetracedOrShadowed() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.record(520, 8);
        memory.record(520, 520);
        memory.record(8, 520);
        assertFalse(memory.wouldRetrace(8, 520, 8, 8), "crossing the first leg at a right angle");
        assertTrue(memory.wouldRetrace(8, 520, 520, 520), "reversing along the last leg");
        assertTrue(memory.wouldRetrace(8, 420, 520, 420), "a parallel pass 100 blocks inside the last leg");
        assertFalse(memory.wouldRetrace(8, 520, -504, 520));
    }

    @Test void explicitlyExcludingOriginDoesNotExcludeAdjacentOrLaterRouteCells() {
        var memory = new TrailRouteMemory();
        memory.rememberTrailChunk(0, 0);
        assertTrue(memory.isNearVisitedChunk(2, 2, 2));
        assertFalse(memory.isNearVisitedChunkExcept(2, 2, 2, 0, 0));
        memory.rememberTrailChunk(1, 0);
        assertTrue(memory.isNearVisitedChunkExcept(2, 2, 2, 0, 0));
        assertTrue(memory.isNearVisitedChunkExcept(1, 0, 0, 0, 0));
        memory.rememberTrailChunk(10, 0);
        assertTrue(memory.isNearVisitedChunkExcept(10, 0, 0, 0, 0));
    }

    @Test void explicitOriginExemptionHandlesNegativeAndExtremeCoordinates() {
        var memory = new TrailRouteMemory();
        memory.rememberTrailChunk(-1, -2);
        assertFalse(memory.isNearVisitedChunkExcept(-1, -1, 1, -1, -2));
        assertTrue(memory.isNearVisitedChunkExcept(-1, -1, 1, -2, -1));
        memory.rememberTrailChunk(Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertFalse(memory.isNearVisitedChunkExcept(Integer.MIN_VALUE, Integer.MAX_VALUE, 2,
            Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertTrue(memory.isNearVisitedChunkExcept(Integer.MIN_VALUE, Integer.MAX_VALUE, 2,
            Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @Test void originCellDoesNotPreventDepartingDiagonallyInAnyDirection() {
        for (int degrees = 0; degrees < 360; degrees += 15) {
            var memory = new TrailRouteMemory();
            memory.record(0.01, 0.01);
            double radians = Math.toRadians(degrees);
            assertFalse(memory.wouldRetrace(0.01, 0.01,
                0.01 + Math.cos(radians) * 256, 0.01 + Math.sin(radians) * 256), "angle " + degrees);
        }
    }

    @Test void crossingImmediatelyBeyondGraceIsStillDetected() {
        var memory = new TrailRouteMemory();
        memory.rememberTrailChunk(3, 0);
        memory.rememberTrailChunk(4, 0);
        assertTrue(memory.wouldRetrace(0, 8, 90, 8, 0, 48));
    }

    @Test void negativeCoordinatesUseFloorAndDoNotCollideWithPositiveOnes() {
        var memory = new TrailRouteMemory();
        memory.record(-0.01, -16.01);
        assertTrue(memory.isVisitedChunk(-1, -2));
        assertFalse(memory.isVisitedChunk(0, -1));
        memory.rememberTrailChunk(Integer.MIN_VALUE, Integer.MAX_VALUE);
        memory.rememberTrailChunk(Integer.MAX_VALUE, Integer.MIN_VALUE);
        assertTrue(memory.isNearVisitedChunk(Integer.MIN_VALUE, Integer.MAX_VALUE, 2));
        assertTrue(memory.isNearVisitedChunk(Integer.MAX_VALUE, Integer.MIN_VALUE, 2));
        assertFalse(memory.isVisitedChunk(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test void diagonalFastMovementCoversBothSidesOfChunkCorners() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.record(56, 56);
        for (int i = 0; i <= 3; i++) assertTrue(memory.isVisitedChunk(i, i));
        for (int i = 0; i < 3; i++) {
            assertTrue(memory.isVisitedChunk(i + 1, i));
            assertTrue(memory.isVisitedChunk(i, i + 1));
        }
    }

    @Test void aTeleportReservesOnlyItsDestination() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.record(10_008, 8);
        assertEquals(2, memory.size());
        assertFalse(memory.isVisitedChunk(300, 0));
    }

    @Test void aNewFlightNeverConnectsItsLocationToTheLastFlight() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.breakContinuity();
        memory.record(328, 8);
        assertEquals(2, memory.size());
        assertFalse(memory.isVisitedChunk(10, 0));
    }

    @Test void finishPositionRemembersTheWholeFinalTouchdownSegment() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.finishPosition(88, 8);
        assertEquals(6, memory.size());
        for (int x = 0; x <= 5; x++) assertTrue(memory.isVisitedChunk(x, 0));
        memory.record(328, 8);
        assertEquals(7, memory.size());
        assertFalse(memory.isVisitedChunk(10, 0));
    }

    @Test void groundedUpdatesAfterTouchdownDoNotRememberWalkingRoutes() {
        var memory = new TrailRouteMemory();
        memory.finishPosition(-248, 8);
        assertEquals(0, memory.size());
        memory.record(8, 8);
        memory.finishPosition(88, 8);
        long revision = memory.revision();
        memory.finishPosition(328, 8);
        memory.finishPosition(648, 8);
        assertEquals(6, memory.size());
        assertEquals(revision, memory.revision());
        assertFalse(memory.isVisitedChunk(20, 0));
        assertFalse(memory.isVisitedChunk(40, 0));
    }

    @Test void finishingAfterTeleportDoesNotInventAnInterveningFlight() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.finishPosition(10_008, 8);
        assertEquals(2, memory.size());
        assertFalse(memory.isVisitedChunk(300, 0));
        memory.finishPosition(10_328, 8);
        assertEquals(2, memory.size());
    }

    @Test void duplicateObservationsDoNotGrowTheHistoryOrDirtyRevision() {
        var memory = new TrailRouteMemory();
        memory.record(2, 8);
        memory.record(6, 8);
        long revision = memory.revision();
        memory.record(12, 8);
        memory.rememberTrailChunk(0, 0);
        assertEquals(1, memory.size());
        assertEquals(revision, memory.revision(), "the same chunk flown the same way is not new history");
        memory.record(12, 14);
        assertTrue(memory.revision() > revision, "a new direction through the chunk is saved");
        revision = memory.revision();
        memory.record(40, 14);
        assertTrue(memory.revision() > revision);
    }

    @Test void reachingCapacityKeepsOldHistoryAndFailsClosed() {
        var memory = new TrailRouteMemory(3);
        memory.record(8, 8);
        memory.record(104, 8);
        assertTrue(memory.isFull());
        assertEquals(3, memory.size());
        assertTrue(memory.isVisitedChunk(0, 0));
        assertTrue(memory.isVisitedChunk(1, 0));
        assertTrue(memory.isVisitedChunk(2, 0));
        assertFalse(memory.isVisitedChunk(6, 0));
        assertTrue(memory.wouldRetrace(1_000, 1_000, 1_256, 1_000));
    }

    @Test void overlongQueriesFailClosedWithoutWalkingTheWholeWorld() {
        assertTrue(new TrailRouteMemory().wouldRetrace(0, 0, 1_000_000_000, 0));
    }

    @Test void snapshotIsDetachedFromLaterFlightUpdates() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        var snapshot = memory.snapshot();
        memory.record(104, 8);
        assertEquals(1, snapshot.size());
        assertEquals(1, snapshot.revision());
        assertEquals(7, memory.size());
    }

    @Test void validatesCoordinatesCapacityAndCorridorOptions() {
        assertThrows(IllegalArgumentException.class, () -> new TrailRouteMemory(0));
        assertThrows(IllegalArgumentException.class, () -> new TrailRouteMemory(TrailRouteMemory.MAX_ENTRIES + 1));
        var memory = new TrailRouteMemory();
        assertThrows(IllegalArgumentException.class, () -> memory.record(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> memory.record(0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> memory.record(Double.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> memory.finishPosition(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> memory.isNearVisitedChunk(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> memory.isNearVisitedChunkExcept(0, 0, 17, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> memory.wouldRetrace(0, 0, 256, 0, 0, Double.NaN));
    }

    private static TrailRouteMemory horizontalRoute() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.record(264, 8);
        return memory;
    }

    private static TrailRouteMemory longHorizontalRoute() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.record(1_008, 8);
        return memory;
    }
}
