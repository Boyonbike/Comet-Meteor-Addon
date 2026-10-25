package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
