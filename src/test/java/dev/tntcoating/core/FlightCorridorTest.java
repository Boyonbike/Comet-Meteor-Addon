package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static dev.tntcoating.core.FlightCorridor.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class FlightCorridorTest {
    private final AABB body = new AABB(.2, 65, .2, .8, 66.8, .8);

    @Test void negativeCoordinatesUseFloorAndNeverReadCollisionFromMissingChunks() {
        Probe probe = new Probe((x, z) -> x >= 0);
        var result = FlightCorridor.inspect(body.move(-1, 0, 0), new Vec3(-1, 0, 0), 4, probe);
        assertEquals(CHUNKS, result.reason());
        assertEquals(0, result.clearDistance());
        assertTrue(probe.chunks.contains("-1,0"));
        assertEquals(0, probe.boxes.size());
        assertEquals(0, result.collisionChecks());
    }

    @Test void playerSideCrossingChunkBoundaryIsCheckedEvenBeforeItsCenter() {
        Probe probe = new Probe((x, z) -> x == 0);
        AABB overhanging = new AABB(15.6, 65, .2, 16.2, 65.6, .8);
        var result = FlightCorridor.inspect(overhanging, new Vec3(0, 0, 1), 2, probe);
        assertEquals(CHUNKS, result.reason());
        assertEquals(0, result.clearDistance());
        assertTrue(probe.chunks.contains("1,0"));
        assertTrue(probe.boxes.isEmpty());
    }

    @Test void exactExclusiveBoxEdgeDoesNotRequireTheNextChunk() {
        Probe positive = new Probe((x, z) -> x == 0);
        AABB atPositiveEdge = new AABB(15.4, 65, .2, 16, 65.6, .8);
        assertEquals(CLEAR, FlightCorridor.inspect(atPositiveEdge, new Vec3(0, 0, 1), 1, positive).reason());
        assertFalse(positive.chunks.contains("1,0"));

        Probe negative = new Probe((x, z) -> x == -1);
        AABB atZeroEdge = new AABB(-.6, 65, .2, 0, 65.6, .8);
        assertEquals(CLEAR, FlightCorridor.inspect(atZeroEdge, new Vec3(0, 0, 1), 1, negative).reason());
        assertFalse(negative.chunks.contains("0,0"));
    }

    @Test void diagonalPathChecksAdjacentChunkTouchedByBody() {
        Probe probe = new Probe((x, z) -> x != 1 || z != 0);
        AABB corner = new AABB(15.2, 65, 15.2, 15.8, 65.6, 15.8);
        var result = FlightCorridor.inspect(corner, new Vec3(1, 0, 1), 2, probe);
        assertEquals(CHUNKS, result.reason());
        assertCloseBefore(.2 * Math.sqrt(2), result.clearDistance());
        assertTrue(probe.chunks.contains("1,0"));
        assertEveryCollisionBoxWasLoaded(probe);
    }

    @Test void highSpeedPathCannotSkipAnIntermediateMissingChunk() {
        Probe probe = new Probe((x, z) -> x != 2);
        var result = FlightCorridor.inspect(body, new Vec3(100, 0, 0), 64, probe);
        assertEquals(CHUNKS, result.reason());
        assertCloseBefore(31.2, result.clearDistance());
        assertEveryCollisionBoxWasLoaded(probe);
    }

    @Test void negativeHighSpeedPathChecksEveryIntermediateChunk() {
        Probe probe = new Probe((x, z) -> x != -3);
        var result = FlightCorridor.inspect(body.move(-1, 0, 0), new Vec3(-100, 0, 0), 64, probe);
        assertEquals(CHUNKS, result.reason());
        assertCloseBefore(31.2, result.clearDistance());
        assertEveryCollisionBoxWasLoaded(probe);
    }

    @Test void refinementAllowsSmallStepsCloseToAnUnloadedBoundary() {
        Probe probe = new Probe((x, z) -> x >= 0);
        var result = FlightCorridor.inspect(body, new Vec3(-1, 0, 0), 2, probe);
        assertEquals(CHUNKS, result.reason());
        assertCloseBefore(.2, result.clearDistance());
        assertTrue(result.clearDistance() > .19);
    }

    @Test void sweptBodyCatchesThinObstaclesBetweenEndpoints() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(1.2, 65, .2, 1.21, 66, .8));
        var result = FlightCorridor.inspect(body, new Vec3(1, 0, 0), 8, probe);
        assertEquals(OBSTACLE, result.reason());
        assertCloseBefore(.4, result.clearDistance());
    }

    @Test void sweptBodyIncludesTheHeadAndPlayerSides() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(1.4, 66.6, .75, 2, 67, 1));
        var result = FlightCorridor.inspect(body, new Vec3(1, 0, 0), 4, probe);
        assertEquals(OBSTACLE, result.reason());
        assertCloseBefore(.6, result.clearDistance());
    }

    @Test void verticalFlightChecksTheCompleteSweptHeight() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(0, 68, 0, 1, 69, 1));
        var result = FlightCorridor.inspect(body, new Vec3(0, 20, 0), 10, probe);
        assertEquals(OBSTACLE, result.reason());
        assertCloseBefore(1.2, result.clearDistance());
    }

    @Test void touchingSupportingFloorDoesNotBlockHorizontalMovement() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(-1, 64, -1, 8, 65, 2));
        var result = FlightCorridor.inspect(body, new Vec3(1, 0, 0), 6, probe);
        assertEquals(CLEAR, result.reason());
        assertEquals(6, result.clearDistance());
    }

    @Test void anAlreadyIntersectingObstacleFailsClosed() {
        Probe probe = new Probe();
        probe.obstacles.add(body);
        var result = FlightCorridor.inspect(body, new Vec3(1, 0, 0), 2, probe);
        assertEquals(OBSTACLE, result.reason());
        assertEquals(0, result.clearDistance());
    }

    @Test void directionMagnitudeDoesNotChangeWorldDistance() {
        Probe probe = new Probe();
        var result = FlightCorridor.inspect(body, new Vec3(1e300, 0, 0), 6, probe);
        assertEquals(CLEAR, result.reason());
        assertEquals(6, result.clearDistance());
        assertEquals(6.8, probe.boxes.getLast().maxX, 1e-12);
    }

    @Test void hugeDistanceRequestsHaveStrictProbeAndSegmentBounds() {
        Probe probe = new Probe();
        var result = FlightCorridor.inspect(body, new Vec3(1, 1, 1), Double.MAX_VALUE, probe);
        assertEquals(CLEAR, result.reason());
        assertEquals(64, result.clearDistance());
        assertEquals(32, result.segments());
        assertEquals(32, result.collisionChecks());
        assertTrue(result.chunkChecks() <= 32 * FlightCorridor.MAX_CHUNKS_PER_SEGMENT);
        for (AABB box : probe.boxes) {
            assertTrue(box.maxX - box.minX <= body.maxX - body.minX + 2);
            assertTrue(box.maxY - box.minY <= body.maxY - body.minY + 2 + 1e-12);
            assertTrue(box.maxZ - box.minZ <= body.maxZ - body.minZ + 2);
        }
    }

    @Test void lateObstacleCannotExceedTheRefinementBudget() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(64, 65, 0, 65, 67, 1));
        var result = FlightCorridor.inspect(body, new Vec3(1, 0, 0), Double.MAX_VALUE, probe);
        assertEquals(OBSTACLE, result.reason());
        assertCloseBefore(63.2, result.clearDistance());
        assertEquals(40, result.segments());
        assertEquals(40, result.collisionChecks());
        assertTrue(result.chunkChecks() <= 40 * FlightCorridor.MAX_CHUNKS_PER_SEGMENT);
    }

    @Test void invalidInputDoesNotProbeTheWorld() {
        Probe probe = new Probe();
        for (double distance : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertEquals(INVALID, FlightCorridor.inspect(body, new Vec3(1, 0, 0), distance, probe).reason());
        }
        assertEquals(INVALID, FlightCorridor.inspect(body, new Vec3(Double.NaN, 0, 0), 2, probe).reason());
        assertEquals(INVALID, FlightCorridor.inspect(new AABB(0, 0, 0, 1e100, 1, 1), new Vec3(1, 0, 0), 2, probe).reason());
        assertEquals(INVALID, FlightCorridor.inspect(null, new Vec3(1, 0, 0), 2, probe).reason());
        assertTrue(probe.boxes.isEmpty());
        assertTrue(probe.chunks.isEmpty());
    }

    @Test void zeroMovementDoesNotProbeTheWorld() {
        Probe probe = new Probe();
        assertEquals(0, FlightCorridor.inspect(body, Vec3.ZERO, 64, probe).clearDistance());
        assertEquals(0, FlightCorridor.inspect(body, new Vec3(1, 0, 0), 0, probe).clearDistance());
        assertTrue(probe.boxes.isEmpty());
        assertTrue(probe.chunks.isEmpty());
    }

    @Test void descendingCanContactFloorOrSlabBelowCurrentFeet() {
        Vec3 down = new Vec3(1, -.2, 0);
        assertTrue(FlightCorridor.floorContactAllowed(body, down, 65));
        assertTrue(FlightCorridor.floorContactAllowed(body, down, 64.5));
        assertTrue(FlightCorridor.floorContactAllowed(body, down, 65 + 5e-8));
    }

    @Test void floorContactDoesNotIgnoreWallsAboveFeetOrOtherMovementDirections() {
        assertFalse(FlightCorridor.floorContactAllowed(body, new Vec3(1, -.2, 0), 65.01));
        assertFalse(FlightCorridor.floorContactAllowed(body, new Vec3(1, -.2, 0), 66.8));
        assertFalse(FlightCorridor.floorContactAllowed(body, new Vec3(0, .2, 0), 65));
        assertFalse(FlightCorridor.floorContactAllowed(body, new Vec3(1, 0, 0), 65));
        assertFalse(FlightCorridor.floorContactAllowed(body, Vec3.ZERO, 65));
    }

    @Test void invalidFloorContactInputsFailClosed() {
        Vec3 down = new Vec3(0, -.2, 0);
        assertFalse(FlightCorridor.floorContactAllowed(null, down, 65));
        assertFalse(FlightCorridor.floorContactAllowed(body, null, 65));
        assertFalse(FlightCorridor.floorContactAllowed(body, down, Double.NaN));
        assertFalse(FlightCorridor.floorContactAllowed(body, down, Double.NEGATIVE_INFINITY));
        assertFalse(FlightCorridor.floorContactAllowed(body, new Vec3(Double.NaN, -.2, 0), 65));
        assertFalse(FlightCorridor.floorContactAllowed(body, new Vec3(0, Double.NEGATIVE_INFINITY, 0), 65));
        assertFalse(FlightCorridor.floorContactAllowed(new AABB(0, 0, 0, 1e100, 1, 1), down, 0));
    }

    @Test void repeatedGuardedDescentCanReachAndRemainOnTheFloor() {
        AABB floor = new AABB(-1, 64, -1, 2, 65, 2);
        AABB current = body.move(0, 1, 0);
        Vec3 motion = new Vec3(0, -.4, 0);
        for (int tick = 0; tick < 8; tick++) {
            AABB before = current;
            FlightCorridor.Probe probe = new FlightCorridor.Probe() {
                @Override public boolean loaded(int x, int z) { return true; }
                @Override public boolean clear(AABB swept) {
                    return !swept.intersects(floor)
                        || FlightCorridor.floorContactAllowed(before, motion, floor.maxY);
                }
            };
            var result = FlightCorridor.inspect(current, motion, .4, probe);
            assertEquals(CLEAR, result.reason());
            assertEquals(.4, result.clearDistance());
            // The game resolves physical downward movement against the floor. The
            // corridor must allow that move instead of braking above the surface.
            double requestedY = -result.clearDistance();
            double resolvedY = Math.max(requestedY, floor.maxY - current.minY);
            current = current.move(0, resolvedY, 0);
            assertTrue(current.minY >= floor.maxY);
        }
        assertEquals(floor.maxY, current.minY, 1e-12);
    }

    @Test void startInspectionDetectsBodyOverhangingMissingNeighborWithoutCollisionReads() {
        Probe probe = new Probe((x, z) -> x == 0);
        AABB overhanging = new AABB(15.6, 65, .2, 16.2, 65.6, .8);
        var result = FlightCorridor.inspectStart(overhanging, probe);
        assertEquals(CHUNKS, result.reason());
        assertEquals(0, result.clearDistance());
        assertEquals(1, result.segments());
        assertEquals(2, result.chunkChecks());
        assertEquals(0, result.collisionChecks());
        assertTrue(probe.chunks.contains("1,0"));
        assertTrue(probe.boxes.isEmpty());
    }

    @Test void startInspectionDetectsAnExistingBodyObstacleOverlap() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(.7, 65.1, .7, 1, 66, 1));
        var result = FlightCorridor.inspectStart(body, probe);
        assertEquals(OBSTACLE, result.reason());
        assertEquals(1, result.segments());
        assertEquals(1, result.collisionChecks());
        assertEquals(body, probe.boxes.getFirst());
        assertTrue(result.chunkChecks() <= FlightCorridor.MAX_CHUNKS_PER_SEGMENT);
    }

    @Test void safeStartIsDistinctFromAnImmediatelyBlockedFlightPath() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(.85, 65, .2, .86, 66, .8));
        var start = FlightCorridor.inspectStart(body, probe);
        assertEquals(CLEAR, start.reason());
        assertEquals(1, start.segments());
        assertEquals(1, start.collisionChecks());

        var path = FlightCorridor.inspect(body, new Vec3(1, 0, 0), 2, probe);
        assertEquals(OBSTACLE, path.reason());
        assertCloseBefore(.05, path.clearDistance());
        assertTrue(path.clearDistance() > 0);
    }

    private static void assertCloseBefore(double boundary, double distance) {
        assertTrue(distance <= boundary + 1e-10, "must stop before the unsafe boundary");
        assertTrue(boundary - distance <= 2.0 / 256 + 1e-10, "must use the refined safe prefix");
    }

    private static void assertEveryCollisionBoxWasLoaded(Probe probe) {
        for (AABB box : probe.boxes) {
            int minX = (int) Math.floor(box.minX / 16);
            int maxX = (int) Math.floor(Math.nextDown(box.maxX / 16));
            int minZ = (int) Math.floor(box.minZ / 16);
            int maxZ = (int) Math.floor(Math.nextDown(box.maxZ / 16));
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) assertTrue(probe.loaded.test(x, z));
            }
        }
    }

    private static final class Probe implements FlightCorridor.Probe {
        final BiPredicate<Integer, Integer> loaded;
        final Set<String> chunks = new HashSet<>();
        final List<AABB> boxes = new ArrayList<>();
        final List<AABB> obstacles = new ArrayList<>();

        Probe() { this((x, z) -> true); }
        Probe(BiPredicate<Integer, Integer> loaded) { this.loaded = loaded; }

        @Override public boolean loaded(int chunkX, int chunkZ) {
            chunks.add(chunkX + "," + chunkZ);
            return loaded.test(chunkX, chunkZ);
        }

        @Override public boolean clear(AABB sweptSegment) {
            boxes.add(sweptSegment);
            return obstacles.stream().noneMatch(sweptSegment::intersects);
        }
    }
}
