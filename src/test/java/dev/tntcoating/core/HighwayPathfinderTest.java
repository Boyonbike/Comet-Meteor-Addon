package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HighwayPathfinderTest {
    private static final AABB BODY = new AABB(-.3, 0, -.3, .3, 1.8, .3);
    private static final HighwayPathfinder.Config CONFIG =
        new HighwayPathfinder.Config(5, 3, 8, 2, 1, 1, -10, 10);

    @Test
    void findsStraightPathInOpenCorridor() {
        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY, Vec3.ZERO, new Vec3(0, 0, 1), 0,
            CONFIG, probe(List.of()));

        assertTrue(path.found());
        assertEquals(8, path.progress());
        assertTrue(path.complete());
        assertTrue(path.points().size() > 2);
        assertEquals(Vec3.ZERO, path.points().get(0));
    }

    @Test
    void routesThroughOneBlockGapInAFullWidthWall() {
        List<AABB> walls = List.of(
            new AABB(-3, 0, 7.9, .5, 3, 8.1),
            new AABB(1.5, 0, 7.9, 3, 3, 8.1));
        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY, Vec3.ZERO, new Vec3(0, 0, 1), 0,
            CONFIG, probe(walls));

        assertTrue(path.found());
        assertTrue(path.points().stream().anyMatch(point -> point.x > .5));
    }

    @Test
    void reportsBlockedForASolidWall() {
        List<AABB> walls = List.of(new AABB(-3, 0, 7.9, 3, 3, 8.1));
        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY, Vec3.ZERO, new Vec3(0, 0, 1), 0,
            CONFIG, probe(walls));

        assertTrue(path.found());
        assertTrue(path.progress() < 8);
        assertFalse(path.complete());
        assertTrue(path.points().stream().allMatch(point -> point.z < 8));
    }


    @Test
    void routesThroughDiagonalWallGap() {
        double inv = 1 / Math.sqrt(2);
        Vec3 diagonal = new Vec3(inv, 0, inv);
        FlightCorridor.Probe probe = new FlightCorridor.Probe() {
            @Override public boolean loaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override public boolean clear(AABB swept) {
                Vec3 centre = swept.getCenter();
                double forward = (centre.x + centre.z) * inv;
                double lateral = (-centre.x + centre.z) * inv;
                return forward < 7.5 || forward > 8.5 || Math.abs(lateral) <= .5;
            }
        };

        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY, Vec3.ZERO, diagonal, 0,
            CONFIG, probe);

        assertTrue(path.found());
        assertTrue(path.points().stream().anyMatch(point ->
            Math.abs(-point.x + point.z) * inv <= .5 && (point.x + point.z) * inv > 8));
    }


    @Test
    void threadsOneByOneGapWithFallFlyingBody() {
        AABB flyingBody = new AABB(-.3, 0, -.3, .3, .6, .3);
        List<AABB> walls = List.of(
            new AABB(-3, -1, 7.9, -.5, 3, 8.1),
            new AABB(.5, -1, 7.9, 3, 3, 8.1),
            new AABB(-.5, -1, 7.9, .5, 0, 8.1),
            new AABB(-.5, 1, 7.9, .5, 3, 8.1));
        HighwayPathfinder.Path path = HighwayPathfinder.find(flyingBody, Vec3.ZERO, new Vec3(0, 0, 1), 0,
            CONFIG, probe(walls));

        assertTrue(path.found());
        assertEquals(8, path.progress());
        assertTrue(path.complete());
        assertTrue(path.points().stream().anyMatch(point -> point.z > 8));
    }


    @Test
    void prefersSideDetourOverUnderAFloatingBlock() {
        AABB flyingBody = new AABB(-.3, 0, -.3, .3, .6, .3);
        List<AABB> walls = List.of(new AABB(-.5, 1, 4, .5, 2, 6));
        HighwayPathfinder.Path path = HighwayPathfinder.find(flyingBody, Vec3.ZERO, new Vec3(0, 0, 1), 0,
            CONFIG, probe(walls));

        assertTrue(path.found());
        assertTrue(path.points().stream()
            .filter(point -> point.z >= 4 && point.z <= 6)
            .anyMatch(point -> Math.abs(point.x) > .5));
    }

    @Test
    void alignsSidewaysBeforeAnImmediatelyAdjacentWall() {
        List<AABB> walls = List.of(new AABB(-3, -2, .5, .5, 4, 2));
        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY, Vec3.ZERO, new Vec3(0, 0, 1), 0,
            CONFIG, probe(walls));

        assertTrue(path.found(), "Must move sideways before advancing into the wall");
        assertEquals(8, path.progress());
        assertTrue(path.complete());
        assertTrue(path.points().stream().anyMatch(point -> point.z == 0 && point.x >= 1));
        assertSafeEdges(path, BODY, probe(walls));
    }

    @Test
    void climbsBeforeAnImmediatelyAdjacentLowObstacle() {
        AABB flyingBody = new AABB(-.3, 0, -.3, .3, .6, .3);
        List<AABB> walls = List.of(new AABB(-3, -2, .5, 3, .8, 2));
        HighwayPathfinder.Path path = HighwayPathfinder.find(flyingBody, Vec3.ZERO, new Vec3(0, 0, 1), 0,
            CONFIG, probe(walls));

        assertTrue(path.found(), "Must climb in place before moving over the obstacle");
        assertEquals(8, path.progress());
        assertTrue(path.complete());
        assertSafeEdges(path, flyingBody, probe(walls));
    }

    @Test
    void reportsChunksWhenEveryForwardNodeIsUnloaded() {
        Vec3 start = new Vec3(8, 0, 15);
        FlightCorridor.Probe chunks = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return z == 0; }
            @Override public boolean clear(AABB box) { return true; }
        };
        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY.move(start), start, new Vec3(0, 0, 1),
            0, CONFIG, chunks);
        assertEquals(HighwayPathfinder.Reason.CHUNKS, path.reason());
        assertTrue(path.points().isEmpty());
    }

    @Test
    void neverProbesUnloadedChunksForExtraClearance() {
        Vec3 start = new Vec3(8, 0, 13.6);
        FlightCorridor.Probe chunks = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return x == 0 && z == 0; }
            @Override public boolean clear(AABB box) {
                assertTrue(box.minX >= 0 && box.maxX < 16 && box.minZ >= 0 && box.maxZ < 16,
                    "Clearance scoring must respect the non-loading probe contract");
                return true;
            }
        };
        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY.move(start), start, new Vec3(0, 0, 1),
            0, CONFIG, chunks);
        assertTrue(path.found());
        assertEquals(1, path.progress());
        assertEquals(HighwayPathfinder.Reason.CHUNKS, path.reason());
        assertFalse(path.complete());
    }

    @Test
    void refinesToFindFractionallyOffsetOneBlockOpening() {
        List<AABB> walls = List.of(
            new AABB(-3, -2, 7.9, .8, 4, 8.1),
            new AABB(1.8, -2, 7.9, 3, 4, 8.1));
        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY, Vec3.ZERO, new Vec3(0, 0, 1), 0,
            CONFIG, probe(walls));

        assertEquals(8, path.progress(), "The coarse grid cannot fit the body through this opening");
        assertSafeEdges(path, BODY, probe(walls));
    }

    @Test
    void diagonalMixedFrontierWaitsForUnknownContinuationDespiteBlockedAlternative() {
        Vec3 start = new Vec3(13,0,13);
        FlightCorridor.Probe mixed = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x,int z) { return z==0; }
            @Override public boolean clear(AABB box) {
                return !new AABB(16,-5,0,25,5,16).intersects(box);
            }
        };
        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY.move(start),start,new Vec3(1,0,1),0,
            CONFIG,mixed);
        assertFalse(path.complete());
        assertEquals(HighwayPathfinder.Reason.CHUNKS,path.reason(),
            "A blocked alternative cannot prove the unknown forward continuation is a dead end");
    }

    @Test
    void irrelevantUnloadedSideBranchDoesNotHideSolidDeadEnd() {
        Vec3 start = new Vec3(1, 0, 4);
        FlightCorridor.Probe corridor = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return x >= 0 || z != 0; }
            @Override public boolean clear(AABB box) {
                return !new AABB(-10, -5, 17.9, 10, 5, 18.1).intersects(box);
            }
        };
        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY.move(start), start,
            new Vec3(0, 0, 1), 0, CONFIG, corridor);

        assertEquals(HighwayPathfinder.Reason.FOUND, path.reason());
        assertTrue(path.progress() < 8);
        assertFalse(path.complete());
    }

    @Test
    void refinesVerticallyForOffsetOneBlockOpening() {
        AABB flyingBody = new AABB(-.3, 0, -.3, .3, .6, .3);
        List<AABB> walls = List.of(
            new AABB(-3, -3, 7.9, 3, .35, 8.1),
            new AABB(-3, 1.35, 7.9, 3, 4, 8.1));
        HighwayPathfinder.Path path = HighwayPathfinder.find(flyingBody, Vec3.ZERO,
            new Vec3(0, 0, 1), 0, CONFIG, probe(walls));

        assertTrue(path.complete());
        assertSafeEdges(path, flyingBody, probe(walls));
        assertTrue(path.points().stream().allMatch(point -> Math.abs(point.x) <= 2
            && point.y >= -1 && point.y <= 1 && point.z <= 16));
    }

    @Test
    void capsProbeWorkAndDoesNotMistakeBudgetExhaustionForChunks() {
        int[] calls = {0};
        FlightCorridor.Probe bounded = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { calls[0]++; return true; }
            @Override public boolean clear(AABB box) {
                calls[0]++;
                return !new AABB(-10, -5, 125, 10, 10, 127).intersects(box);
            }
        };
        HighwayPathfinder.Path path = HighwayPathfinder.find(BODY, Vec3.ZERO, new Vec3(0, 0, 1), 0,
            new HighwayPathfinder.Config(9, 5, 64, 2, 1, 1, -10, 10), bounded);

        assertTrue(calls[0] <= 120_000, "Both search passes must share one probe budget");
        assertFalse(path.complete());
        assertFalse(path.reason() == HighwayPathfinder.Reason.CHUNKS);
        assertTrue(path.points().stream().allMatch(point -> Math.abs(point.x) <= 4
            && point.y >= -2 && point.y <= 2 && point.z <= 128));
    }

    @Test
    void manuallyConstructedFoundPathsRemainCompatible() {
        HighwayPathfinder.Path path = new HighwayPathfinder.Path(
            List.of(Vec3.ZERO, new Vec3(0, 0, 2)), HighwayPathfinder.Reason.FOUND, 1);
        assertTrue(path.found());
        assertTrue(path.complete());
    }

    private static void assertSafeEdges(HighwayPathfinder.Path path, AABB body, FlightCorridor.Probe probe) {
        for (int i = 1; i < path.points().size(); i++) {
            Vec3 from = path.points().get(i - 1);
            Vec3 delta = path.points().get(i).subtract(from);
            assertEquals(FlightCorridor.Reason.CLEAR,
                FlightCorridor.inspect(body.move(from), delta, delta.length(), probe).reason());
        }
    }

    private static FlightCorridor.Probe probe(List<AABB> walls) {
        return new FlightCorridor.Probe() {
            @Override public boolean loaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override public boolean clear(AABB swept) {
                for (AABB wall : walls) {
                    if (wall.intersects(swept)) return false;
                }
                return true;
            }
        };
    }
}
