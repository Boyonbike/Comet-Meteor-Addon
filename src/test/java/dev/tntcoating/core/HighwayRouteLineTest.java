package dev.tntcoating.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HighwayRouteLineTest {
    private static final double EPS = 1e-8;
    private static final double TEN_DIAGONAL_BLOCKS = 10 * Math.sqrt(2);

    @Test void positiveXAxisProjectsOntoTheDestinationLane() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(.5, 64, 4.5), new Vec3(1000.5, 120, 10.5));

        assertTarget(new Vec3(120.5, 120, 10.5), line.target(new Vec3(100.5, 64, 14.5), 20));
    }

    @Test void negativeXAxisProjectsOntoTheDestinationLane() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(.5, 64, 4.5), new Vec3(-999.5, 120, 10.5));

        assertTarget(new Vec3(-119.5, 120, 10.5), line.target(new Vec3(-99.5, 64, 14.5), 20));
    }

    @Test void positiveZAxisProjectsOntoTheDestinationLane() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(4.5, 64, .5), new Vec3(10.5, 120, 1000.5));

        assertTarget(new Vec3(10.5, 120, 120.5), line.target(new Vec3(14.5, 64, 100.5), 20));
    }

    @Test void negativeZAxisProjectsOntoTheDestinationLane() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(4.5, 64, .5), new Vec3(10.5, 120, -999.5));

        assertTarget(new Vec3(10.5, 120, -119.5), line.target(new Vec3(14.5, 64, -99.5), 20));
    }

    @Test void positiveDiagonalUsesDistanceAlongTheLine() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(1000.5, 120, 1000.5));

        assertTarget(new Vec3(15.5, 120, 15.5), line.target(new Vec3(8.5, 64, 2.5), TEN_DIAGONAL_BLOCKS));
    }

    @Test void negativeDiagonalUsesDistanceAlongTheLine() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(-999.5, 120, -999.5));

        assertTarget(new Vec3(-14.5, 120, -14.5), line.target(new Vec3(-1.5, 64, -7.5), TEN_DIAGONAL_BLOCKS));
    }

    @Test void positiveXNegativeZDiagonalKeepsTheBlockCenterOffset() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(1000.5, 120, -999.5));

        assertTarget(new Vec3(15.5, 120, -14.5), line.target(new Vec3(8.5, 64, -1.5), TEN_DIAGONAL_BLOCKS));
    }

    @Test void negativeXPositiveZDiagonalKeepsTheBlockCenterOffset() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(-999.5, 120, 1000.5));

        assertTarget(new Vec3(-14.5, 120, 15.5), line.target(new Vec3(-1.5, 64, 8.5), TEN_DIAGONAL_BLOCKS));
    }

    @Test void initialHeadingIsRoundedToTheNearestStandardDirection() {
        HighwayRouteLine cardinal = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(1000.5, 120, 200.5));
        HighwayRouteLine diagonal = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(1000.5, 120, 700.5));

        assertTarget(new Vec3(120.5, 120, 200.5), cardinal.target(new Vec3(100.5, 64, 8.5), 20));
        assertTarget(new Vec3(250.5, 120, -49.5), diagonal.target(new Vec3(100.5, 64, 80.5), TEN_DIAGONAL_BLOCKS));
    }

    @Test void recoveryPositionsCannotReselectOrShiftTheCenterline() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(.5, 120, 1000.5));

        assertTarget(new Vec3(.5, 120, 120.5), line.target(new Vec3(40.5, 64, 100.5), 20));
        assertTarget(new Vec3(.5, 120, 140.5), line.target(new Vec3(-80.5, 64, 120.5), 20));
        assertTarget(new Vec3(.5, 120, 1000.5), line.target(new Vec3(1000.5, 64, 990.5), 20));
    }

    @Test void forwardTargetsStopAtTheDestination() {
        Vec3 destination = new Vec3(.5, 120, 100.5);
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(.5, 64, .5), destination);

        assertTarget(destination, line.target(new Vec3(20.5, 64, 90.5), 20));
        assertTarget(destination, line.target(new Vec3(-20.5, 64, 110.5), 20));
        assertTarget(destination, line.target(destination, 20));
    }

    @Test void zeroDistanceStillProjectsOntoTheCenterline() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(.5, 120, 1000.5));

        assertTarget(new Vec3(.5, 120, 100.5), line.target(new Vec3(80.5, 64, 100.5), 0));
    }

    @Test void cardinalStandingTargetsCannotMoveToAdjacentLanes() {
        HighwayRouteLine line = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(.5, 120, 1000.5));

        assertTrue(line.onLine(new BlockPos(0, 64, 100)));
        assertTrue(line.onLine(new BlockPos(0, 70, -100)));
        assertFalse(line.onLine(new BlockPos(1, 64, 100)));
        assertFalse(line.onLine(new BlockPos(-1, 64, 100)));
    }

    @Test void diagonalStandingTargetsKeepTheExactBlockCenterLine() {
        HighwayRouteLine rising = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(1000.5, 120, 1000.5));
        HighwayRouteLine falling = HighwayRouteLine.from(new Vec3(.5, 64, .5), new Vec3(1000.5, 120, -999.5));

        assertTrue(rising.onLine(new BlockPos(10, 64, 10)));
        assertFalse(rising.onLine(new BlockPos(10, 64, 9)));
        assertTrue(falling.onLine(new BlockPos(10, 64, -10)));
        assertFalse(falling.onLine(new BlockPos(10, 64, -11)));
    }

    @Test void invalidCoordinatesAndDistancesAreRejected() {
        Vec3 start = new Vec3(.5, 64, .5);
        Vec3 destination = new Vec3(.5, 120, 1000.5);
        HighwayRouteLine line = HighwayRouteLine.from(start, destination);

        assertThrows(IllegalArgumentException.class,
            () -> HighwayRouteLine.from(new Vec3(Double.NaN, 64, .5), destination));
        assertThrows(IllegalArgumentException.class,
            () -> HighwayRouteLine.from(start, new Vec3(.5, Double.POSITIVE_INFINITY, 1000.5)));
        assertThrows(IllegalArgumentException.class,
            () -> line.target(new Vec3(.5, 64, Double.NaN), 20));
        assertThrows(IllegalArgumentException.class, () -> line.target(start, -1));
        assertThrows(IllegalArgumentException.class, () -> line.target(start, Double.POSITIVE_INFINITY));
    }

    private static void assertTarget(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.z, actual.z, EPS);
    }
}
