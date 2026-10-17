package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrailRoutePathTest {
    @Test void curvedPathCanCrossHistoryEvenWhenItsGoalHeadingIsClear() {
        var memory = new TrailRouteMemory();
        memory.recordPosition(184, 136);
        assertFalse(memory.wouldRetrace(8, 8, 8, 520));
        assertTrue(TrailRoutePath.wouldRevisit(memory,
            List.of(point(8, 8), point(8, 136), point(240, 136)), 8, 8, 512));
    }

    @Test void alreadyFlownPrefixIsSkippedAtTheNearestSegment() {
        var memory = new TrailRouteMemory();
        memory.recordPosition(8, -240);
        memory.recordPosition(8, 8);
        var path = List.of(point(8, -240), point(8, -120), point(8, 120), point(8, 360));
        assertFalse(TrailRoutePath.wouldRevisit(memory, path, 8, 8, 256));
    }

    @Test void manySmallSegmentsDoNotRestartTheInitialGraceDistance() {
        var memory = new TrailRouteMemory();
        memory.recordPosition(8, 136);
        var path = new ArrayList<TrailRoutePath.Point>();
        for (int z = 8; z <= 248; z += 8) path.add(point(8, z));
        assertTrue(TrailRoutePath.wouldRevisit(memory, path, 8, 8, 240));
    }

    @Test void onlyTheOriginalCellIsExemptNotOtherNearbyFlownCells() {
        var originOnly = new TrailRouteMemory();
        originOnly.recordPosition(8, 8);
        var path = List.of(point(8, 8), point(56, 8), point(120, 8));
        assertFalse(TrailRoutePath.wouldRevisit(originOnly, path, 8, 8, 112));
        for (int chunk = 1; chunk <= 4; chunk++) originOnly.rememberTrailChunk(chunk, 0);
        assertTrue(TrailRoutePath.wouldRevisit(originOnly, path, 8, 8, 112));
    }

    @Test void clipsByArcLengthAcrossCorners() {
        var memory = new TrailRouteMemory();
        memory.recordPosition(200, 300);
        var path = List.of(point(8, 8), point(8, 300), point(240, 300));
        assertFalse(TrailRoutePath.wouldRevisit(memory, path, 8, 8, 256));
        assertTrue(TrailRoutePath.wouldRevisit(memory, path, 8, 8, 512));
    }

    @Test void aLongEdgeIsCheckedBetweenItsEndpoints() {
        var memory = new TrailRouteMemory();
        memory.recordPosition(8, 248);
        assertTrue(TrailRoutePath.wouldRevisit(memory,
            List.of(point(8, 8), point(8, 800)), 8, 8, 512));
    }

    @Test void verticalOrRepeatedNodesDoNotHideLaterCrossings() {
        var memory = new TrailRouteMemory();
        memory.recordPosition(8, 136);
        assertTrue(TrailRoutePath.wouldRevisit(memory,
            List.of(point(8, 8), point(8, 8), point(8, 72), point(8, 72), point(8, 248)), 8, 8, 256));
    }

    @Test void calculatedUTurnAHundredBlocksToTheSideIsRejectedBeforeRetracing() {
        var memory = horizontalRoute();
        var path = List.of(point(1_008, 8), point(1_056, 40), point(1_056, 88),
            point(1_024, 108), point(512, 108));
        assertTrue(TrailRoutePath.wouldRevisit(memory, path, 1_008, 8, 512));
    }

    @Test void smallSegmentsCannotResetTheWideCorridorDirectionCheck() {
        var memory = horizontalRoute();
        var path = new ArrayList<TrailRoutePath.Point>();
        for (int x = 1_008; x >= 512; x -= 8) path.add(point(x, 108));
        assertTrue(TrailRoutePath.wouldRevisit(memory, path, 1_008, 108, 256));
    }

    @Test void forwardAndOutwardPathsCanLeaveTheCurrentFrontier() {
        var memory = horizontalRoute();
        assertFalse(TrailRoutePath.wouldRevisit(memory,
            List.of(point(1_008, 8), point(1_072, 8), point(1_520, 8)), 1_008, 8, 512));
        assertFalse(TrailRoutePath.wouldRevisit(memory,
            List.of(point(1_008, 8), point(1_008, 72), point(1_008, 520)), 1_008, 8, 512));
    }

    @Test void longTailDoesNotObstructForwardOrPerpendicularPathsAtAnyHeading() {
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
                assertFalse(TrailRoutePath.wouldRevisit(memory,
                    List.of(point(x, z), point(x + directionX * 512, z + directionZ * 512)), x, z, 512), context);
                assertFalse(TrailRoutePath.wouldRevisit(memory,
                    List.of(point(x, z), point(x - directionZ * 512, z + directionX * 512)), x, z, 512), context);
                assertFalse(TrailRoutePath.wouldRevisit(memory,
                    List.of(point(x, z), point(x + directionZ * 512, z - directionX * 512)), x, z, 512), context);
            }
        }
    }

    @Test void anEmptyUncalculatedPathIsNotReportedAsARouteCrossing() {
        var memory = new TrailRouteMemory();
        assertFalse(TrailRoutePath.wouldRevisit(memory, List.of(), 8, 8, 512));
        assertFalse(TrailRoutePath.wouldRevisit(memory, List.of(point(8, 8)), 8, 8, 512));
    }

    @Test void aStalePathFarFromThePlayerCannotBeCertifiedSafe() {
        assertTrue(TrailRoutePath.wouldRevisit(new TrailRouteMemory(),
            List.of(point(1_000, 1_000), point(1_000, 1_500)), 8, 8, 512));
    }

    @Test void validatesBoundsAndNonFiniteInputs() {
        var memory = new TrailRouteMemory();
        assertThrows(IllegalArgumentException.class, () -> point(Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () ->
            TrailRoutePath.wouldRevisit(memory, List.of(), 0, 0, 1_025));
        assertThrows(IllegalArgumentException.class, () ->
            TrailRoutePath.wouldRevisit(memory, List.of(), Double.POSITIVE_INFINITY, 0, 512));
    }

    private static TrailRoutePath.Point point(double x, double z) { return new TrailRoutePath.Point(x, z); }

    private static TrailRouteMemory horizontalRoute() {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        memory.record(1_008, 8);
        return memory;
    }
}
