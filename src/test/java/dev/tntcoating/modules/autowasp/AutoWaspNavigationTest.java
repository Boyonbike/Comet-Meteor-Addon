package dev.tntcoating.modules.autowasp;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWaspNavigationTest {
    @Test void shortSegmentStillChecksBlockedDestination() {
        assertFalse(AutoWaspPathfinder.isDirectPathClear(Vec3.ZERO, new Vec3(0.1, 0, 0),
            point -> point.x < 0.05));
    }

    @Test void lastPartialStepCannotSkipAnObstacle() {
        assertFalse(AutoWaspPathfinder.isDirectPathClear(Vec3.ZERO, new Vec3(1.2, 0, 0),
            point -> point.x < 1.15));
    }

    @Test void wallBetweenClearEndpointsBlocksRoute() {
        assertFalse(AutoWaspPathfinder.isDirectPathClear(Vec3.ZERO, new Vec3(3, 0, 0),
            point -> point.x < 1 || point.x > 2));
        assertTrue(AutoWaspPathfinder.isDirectPathClear(Vec3.ZERO, new Vec3(3, 0, 0), point -> true));
    }

    @Test void blockedStartAndInvalidCoordinatesAreRejected() {
        assertFalse(AutoWaspPathfinder.isDirectPathClear(Vec3.ZERO, Vec3.ZERO, point -> false));
        assertFalse(AutoWaspPathfinder.isDirectPathClear(Vec3.ZERO, new Vec3(Double.NaN, 0, 0), point -> true));
    }

    @Test void partialRouteStopsAtReachableEndpoint() {
        var path = new ArrayList<>(List.of(Vec3.ZERO, new Vec3(2, 0, 0)));
        AutoWaspPathSearch.appendReachableGoal(path, new Vec3(4, 0, 0), (from, to) -> false);
        assertEquals(List.of(Vec3.ZERO, new Vec3(2, 0, 0)), path);
    }

    @Test void unobstructedFinalApproachIsRetained() {
        var path = new ArrayList<>(List.of(Vec3.ZERO, new Vec3(2, 0, 0)));
        var goal = new Vec3(4, 0, 0);
        AutoWaspPathSearch.appendReachableGoal(path, goal, (from, to) -> true);
        assertEquals(List.of(Vec3.ZERO, new Vec3(2, 0, 0), goal), path);
    }
}
