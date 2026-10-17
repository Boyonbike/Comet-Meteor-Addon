package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
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

class AdaptiveFlightStepTest {
    private static final double EPSILON = 1e-10;
    private final AABB body = new AABB(.2, 65, .2, .8, 66.8, .8);

    @Test void releasingInputBrakesThroughTheProductionPipeline() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(2, 0, 0));
        Probe probe = new Probe();

        var first = AdaptiveFlightStep.move(controller, Motion.ZERO, 3, 1, body, probe, 64, .35);
        assertEquals(1.5, first.motion().x(), EPSILON);
        assertEquals(CLEAR, first.corridor().reason());
        assertEquals(32, probe.boxes.size(), "one complete corridor scan per moving step");
        assertFalse(first.lookaheadLimited());
        assertFalse(first.terrainLimited());
        assertEquals(first.motion(), controller.output());

        AABB moved = moved(body, first.motion());
        for (double expected : new double[] {1, .5, 0}) {
            controller.tick();
            var step = AdaptiveFlightStep.move(controller, Motion.ZERO, 3, 1, moved, probe, 64, .35);
            assertEquals(expected, step.motion().x(), EPSILON);
            moved = moved(moved, step.motion());
            if (expected == 0) assertNull(step.corridor());
        }
    }

    @Test void loweringStockLandingSpeedsImposesBothAxisCeilingsImmediately() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(3, 4, 0));
        Probe probe = new Probe();

        var slowed = AdaptiveFlightStep.move(controller, new Motion(.2, .05, 0),
            .2, .05, body, probe, 64, .1);
        assertTrue(slowed.motion().horizontalLength() <= .2 + EPSILON);
        assertTrue(Math.abs(slowed.motion().y()) <= .05 + EPSILON);
        assertEquals(slowed.motion(), controller.output());
        assertTrue(slowed.motion().length() < .21);

        int collisionCalls = probe.boxes.size();
        var stopped = AdaptiveFlightStep.move(controller, Motion.ZERO, 0, 0, body, probe, 64, .1);
        assertEquals(Motion.ZERO, stopped.motion());
        assertNull(stopped.corridor());
        assertEquals(collisionCalls, probe.boxes.size());
        assertEquals(Motion.ZERO, controller.output());
    }

    @Test void acceleratesThenBrakesAtALoadedEdgeAcrossManyTicksWithoutCrossing() {
        AdaptiveFlightController controller = controller(.5, .3);
        Probe probe = new Probe((x, z) -> x == 0);
        AABB position = body;
        double peak = 0;
        boolean decelerated = false;
        double previous = 0;

        for (int tick = 0; tick < 100; tick++) {
            controller.tick();
            int before = probe.boxes.size();
            var step = AdaptiveFlightStep.move(controller, new Motion(8, 0, 0),
                8, 0, position, probe, 64, .25);
            double speed = step.motion().length();
            peak = Math.max(peak, speed);
            decelerated |= speed + EPSILON < previous;
            previous = speed;
            position = moved(position, step.motion());
            assertTrue(position.maxX <= 15.75 + EPSILON, "body must retain its margin before chunk 1");
            assertEquals(step.motion(), controller.output());
            assertTrue(probe.boxes.size() - before <= FlightCorridor.MAX_SEGMENTS + FlightCorridor.REFINEMENT_STEPS);
        }

        assertTrue(peak > 1, "clear loaded terrain should permit acceleration");
        assertTrue(decelerated, "flight must slow before reaching the loaded edge");
        assertTrue(previous < .01);
        assertTrue(position.maxX > 15.70, "refinement should allow approaching the edge rather than stopping blocks early");
        assertCollisionBoxesLoaded(probe);
    }

    @Test void cornerTurnChecksTheBlendedPathBetweenOldAndRequestedDirections() {
        AdaptiveFlightController controller = controller(.5, .5);
        controller.accept(new Motion(2, 0, 0));
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(3.5, 65, 1.25, 3.6, 67, 1.35));

        var step = AdaptiveFlightStep.move(controller, new Motion(0, 0, 2),
            2, 0, body, probe, 64, .1);
        assertEquals(OBSTACLE, step.corridor().reason());
        double expectedX = 2 - .5 / Math.sqrt(2);
        double expectedZ = .5 / Math.sqrt(2);
        assertTrue(step.motion().length() < Math.hypot(expectedX, expectedZ));
        assertTrue(step.motion().x() > 0 && step.motion().z() > 0);
        assertEquals(expectedZ / expectedX, step.motion().z() / step.motion().x(), EPSILON);
        AABB firstScan = probe.boxes.getFirst();
        assertEquals(expectedZ / expectedX,
            (firstScan.maxZ - body.maxZ) / (firstScan.maxX - body.maxX), EPSILON);
        assertEquals(step.corridor().collisionChecks(), probe.boxes.size());
        assertTrue(step.corridor().segments() <= 2 + FlightCorridor.REFINEMENT_STEPS);
        assertFalse(step.lookaheadLimited());
        assertTrue(step.terrainLimited());
    }

    @Test void correctionPausePerformsNoTerrainQueries() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(3, 0, 0));
        controller.corrected(1);
        Probe probe = new Probe();

        for (int tick = 0; tick < 2; tick++) {
            controller.tick();
            var step = AdaptiveFlightStep.move(controller, new Motion(5, 0, 0),
                5, 1, body, probe, 64, .35);
            assertEquals(Motion.ZERO, step.motion());
            assertNull(step.corridor());
            assertFalse(step.lookaheadLimited());
        }
        assertTrue(probe.chunks.isEmpty());
        assertTrue(probe.boxes.isEmpty());
    }

    @Test void clearCappedHorizonStillImposesAnAbsoluteBrakingCeiling() {
        AdaptiveFlightController controller = controller(100, .3);
        Probe probe = new Probe();

        var step = AdaptiveFlightStep.move(controller, new Motion(64, 0, 0),
            64, 0, body, probe, 1000, .35);
        double clearance = FlightCorridor.MAX_DISTANCE - .35;
        double cap = Math.sqrt(.3 * .3 + 2 * .3 * clearance) - .3;
        assertEquals(CLEAR, step.corridor().reason());
        assertEquals(FlightCorridor.MAX_DISTANCE, step.corridor().clearDistance());
        assertEquals(cap, step.motion().length(), EPSILON);
        assertTrue(step.motion().length() <= clearance);
        assertTrue(step.lookaheadLimited());
        assertTrue(step.terrainLimited());
        assertEquals(FlightCorridor.MAX_SEGMENTS, probe.boxes.size());
        assertEquals(step.motion(), controller.output());
    }

    @Test void negativeChunkBoundaryUsesTheBodyAndFloorCoordinates() {
        AdaptiveFlightController controller = controller(5, .5);
        Probe probe = new Probe((x, z) -> x == -1 && z == -1);
        AABB negative = new AABB(-15.8, 65, -.8, -15.2, 66.8, -.2);

        var step = AdaptiveFlightStep.move(controller, new Motion(-5, 0, 0),
            5, 0, negative, probe, 64, .05);
        assertEquals(CHUNKS, step.corridor().reason());
        assertTrue(probe.chunks.contains("-2,-1"));
        assertTrue(step.motion().x() < 0 && step.motion().x() > -.15);
        assertTrue(moved(negative, step.motion()).minX > -15.95);
        assertCollisionBoxesLoaded(probe);
    }

    @Test void descendingMovementAllowsFloorContactThroughTheProductionProbePolicy() {
        AdaptiveFlightController controller = controller(1, .5);
        Probe probe = new Probe();
        probe.floorController = controller;
        probe.floorBody = body;
        probe.obstacles.add(new AABB(-5, 64, -5, 100, 65, 100));

        var step = AdaptiveFlightStep.move(controller, new Motion(1, -.1, 0),
            2, 1, body, probe, 8, .1);
        assertEquals(CLEAR, step.corridor().reason());
        assertTrue(step.motion().y() < 0, "Minecraft must receive downward motion to resolve touchdown");
        assertTrue(moved(body, step.motion()).minY < 65);
        assertTrue(probe.allowedFloorContacts > 0);

        // A block extending above the current feet is still a real obstacle.
        probe.obstacles.add(new AABB(.2, 64, .2, 1.5, 65.2, .8));
        var blocked = AdaptiveFlightStep.move(controller, new Motion(1, -.1, 0),
            2, 1, body, probe, 8, .1);
        assertEquals(OBSTACLE, blocked.corridor().reason());
        assertEquals(Motion.ZERO, blocked.motion());
    }

    @Test void invalidTerrainInputFailsClosedAndCannotPreservePreviousMomentum() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(3, 0, 0));
        Probe probe = new Probe();

        var step = AdaptiveFlightStep.move(controller, new Motion(3, 0, 0),
            3, 1, null, probe, 64, .1);
        assertEquals(INVALID, step.corridor().reason());
        assertEquals(Motion.ZERO, step.motion());
        assertEquals(Motion.ZERO, controller.output());
        assertTrue(probe.chunks.isEmpty());
        assertTrue(probe.boxes.isEmpty());
    }

    private static AdaptiveFlightController controller(double acceleration, double braking) {
        return new AdaptiveFlightController(new Settings(acceleration, braking, .5, 2, 3, .1));
    }

    private static AABB moved(AABB box, Motion motion) {
        return box.move(motion.x(), motion.y(), motion.z());
    }

    private static void assertCollisionBoxesLoaded(Probe probe) {
        for (AABB swept : probe.boxes) {
            int minX = (int) Math.floor(swept.minX / 16);
            int maxX = (int) Math.floor(Math.nextDown(swept.maxX / 16));
            int minZ = (int) Math.floor(swept.minZ / 16);
            int maxZ = (int) Math.floor(Math.nextDown(swept.maxZ / 16));
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
        AdaptiveFlightController floorController;
        AABB floorBody;
        int allowedFloorContacts;

        Probe() { this((x, z) -> true); }
        Probe(BiPredicate<Integer, Integer> loaded) { this.loaded = loaded; }

        @Override public boolean loaded(int chunkX, int chunkZ) {
            chunks.add(chunkX + "," + chunkZ);
            return loaded.test(chunkX, chunkZ);
        }

        @Override public boolean clear(AABB swept) {
            boxes.add(swept);
            for (AABB obstacle : obstacles) {
                if (!swept.intersects(obstacle)) continue;
                if (floorController != null) {
                    Motion motion = floorController.output();
                    if (FlightCorridor.floorContactAllowed(floorBody,
                        new Vec3(motion.x(), motion.y(), motion.z()), obstacle.maxY)) {
                        allowedFloorContacts++;
                        continue;
                    }
                }
                return false;
            }
            return true;
        }
    }
}
