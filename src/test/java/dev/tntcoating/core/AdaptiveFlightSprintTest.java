package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import dev.tntcoating.core.AdaptiveFlightController.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static dev.tntcoating.core.FlightCorridor.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFlightSprintTest {
    private static final double EPSILON = 1e-10;
    private static final double UNLIMITED = Double.POSITIVE_INFINITY;
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);

    @Test void sprintFromRestImmediatelyReachesTheRequestedHorizontalSpeed() {
        AdaptiveFlightController controller = controller(.25, .5);

        Motion actual = controller.step(new Motion(3, 0, 4), UNLIMITED, true);

        assertMotion(new Motion(3, 0, 4), actual);
        assertEquals(5, actual.horizontalLength(), EPSILON);
        assertEquals(actual, controller.output());
    }

    @Test void sprintAcceleratesExistingAlignedMomentumImmediately() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(.6, .4, .8));

        Motion actual = controller.step(new Motion(3, .4, 4), UNLIMITED, true);

        assertMotion(new Motion(3, .4, 4), actual);
    }

    @Test void sprintKeepsVerticalMovementOnTheNormalAccelerationBudget() {
        AdaptiveFlightController controller = controller(.25, .5);

        Motion raised = controller.step(new Motion(5, 2, 0), UNLIMITED, true);
        assertMotion(new Motion(5, .25, 0), raised);
        Motion lowered = controller.step(new Motion(5, -2, 0), UNLIMITED, true);
        assertEquals(5, lowered.horizontalLength(), EPSILON);
        assertTrue(Math.abs(lowered.y() - raised.y()) <= .5 + EPSILON);
        assertTrue(lowered.y() > -2, "sprint must not snap vertical velocity");
    }

    @Test void normalFlightRetainsItsOriginalAccelerationWithEitherUnboostedEntryPoint() {
        AdaptiveFlightController explicit = controller(.25, .5);
        AdaptiveFlightController original = controller(.25, .5);
        Motion target = new Motion(4, 0, 0);

        assertMotion(new Motion(.25, 0, 0), explicit.step(target, UNLIMITED, false));
        assertEquals(explicit.output(), original.step(target, UNLIMITED));
    }

    @Test void releasingSprintRestoresTheNormalRampForAFasterRequest() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.step(new Motion(3, 0, 0), UNLIMITED, true);

        Motion actual = controller.step(new Motion(5, 0, 0), UNLIMITED, false);

        assertMotion(new Motion(3.25, 0, 0), actual);
    }

    @Test void holdingSprintWithoutMovementInputStillBrakesToAStop() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.step(new Motion(3, 0, 0), UNLIMITED, true);

        assertMotion(new Motion(2.5, 0, 0), controller.step(Motion.ZERO, UNLIMITED, true));
        for (int tick = 0; tick < 8; tick++) controller.step(Motion.ZERO, UNLIMITED, true);
        assertMotion(Motion.ZERO, controller.output());
    }

    @Test void holdingSprintDoesNotSnapDownToALowerRequestedSpeed() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(4, 0, 0));

        assertMotion(new Motion(3.5, 0, 0),
            controller.step(new Motion(1, 0, 0), UNLIMITED, true));
    }

    @Test void holdingSprintDuringAReversalBrakesAndEventuallyTravelsTheOtherWay() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(2, 0, 0));
        Motion target = new Motion(-5, 0, 0);
        double previous = 2;
        boolean reversed = false;

        for (int tick = 0; tick < 40; tick++) {
            Motion actual = controller.step(target, UNLIMITED, true);
            if (previous > 0) {
                assertTrue(actual.x() <= previous, "boost must not replenish opposing momentum");
                assertTrue(previous - actual.x() <= .5 + EPSILON,
                    "the existing heading must brake before reversing");
            }
            reversed |= actual.x() < 0;
            previous = actual.x();
        }

        assertTrue(reversed, "holding sprint must not trap the player in the old heading");
        assertMotion(target, controller.output());
    }

    @Test void sprintPreservesHeadingBeforeApplyingTheNormalSteeringBudget() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(1, 0, 0));
        Motion target = new Motion(3, 0, 4);
        Motion boostedHeading = new Motion(5, 0, 0);

        Motion actual = controller.step(target, UNLIMITED, true);

        assertEquals(.25, difference(actual, boostedHeading), EPSILON);
        assertEquals(5 - .5 / Math.sqrt(20), actual.x(), EPSILON);
        assertEquals(1 / Math.sqrt(20), actual.z(), EPSILON);
        assertTrue(actual.x() > target.x());
        assertTrue(actual.z() < target.z(), "sprint must retain smooth steering");
    }

    @Test void perpendicularInputCannotInstantlyBoostTheOldHeading() {
        AdaptiveFlightController controller = controller(.25, .5);
        Motion initial = new Motion(1, 0, 0);
        controller.accept(initial);

        Motion actual = controller.step(new Motion(0, 0, 5), UNLIMITED, true);

        assertEquals(.25, difference(initial, actual), EPSILON);
        assertTrue(actual.horizontalLength() < 1.25);
        assertTrue(actual.x() > 0 && actual.z() > 0);
    }

    @Test void aHardTerrainLimitClipsBoostAndStoresTheClippedMomentum() {
        AdaptiveFlightController controller = controller(.25, .5);

        Motion clipped = controller.step(new Motion(5, 0, 0), .1, true);

        assertMotion(new Motion(.1, 0, 0), clipped);
        assertEquals(Status.TERRAIN_LIMITED, controller.status());
        assertEquals(clipped, controller.output());
        assertMotion(new Motion(.35, 0, 0),
            controller.step(new Motion(5, 0, 0), UNLIMITED, false));
    }

    @Test void correctionPauseAndReducedCeilingStillApplyWhileSprintIsHeld() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.step(new Motion(5, 0, 0), UNLIMITED, true);
        controller.corrected(1);
        Probe probe = new Probe();
        Motion requested = new Motion(5, 0, 0);

        assertMotion(Motion.ZERO, controller.step(requested, UNLIMITED, true));
        for (int tick = 0; tick < 2; tick++) {
            controller.tick();
            var paused = AdaptiveFlightStep.move(controller, requested,
                5, 0, BODY, probe, 64, .35, true);
            assertMotion(Motion.ZERO, paused.motion());
            assertNull(paused.corridor());
            assertEquals(Status.PAUSED, controller.status());
        }
        assertEquals(0, probe.chunkChecks);
        assertTrue(probe.boxes.isEmpty());

        controller.tick();
        var resumed = AdaptiveFlightStep.move(controller, requested,
            5, 0, BODY, probe, 64, .35, true);
        assertMotion(new Motion(2.5, 0, 0), resumed.motion());
        assertEquals(Status.RECOVERING, controller.status());
        assertEquals(.5, controller.speedFactor(), EPSILON);

        controller.tick();
        var recovering = AdaptiveFlightStep.move(controller, requested,
            5, 0, BODY, probe, 64, .35, true);
        assertMotion(new Motion(3, 0, 0), recovering.motion());
        assertEquals(.6, controller.speedFactor(), EPSILON);
    }

    @Test void repeatedCorrectionsRetainTheirMinimumBackoffDuringBoost() {
        AdaptiveFlightController controller = new AdaptiveFlightController(
            new Settings(.25, .5, .5, 0, 100, 0));
        controller.corrected(20);

        Motion actual = controller.step(new Motion(100, 2, 0), UNLIMITED, true);

        assertEquals(AdaptiveFlightController.MIN_SPEED_FACTOR, controller.speedFactor(), EPSILON);
        assertMotion(new Motion(10, .2, 0), actual);
        assertEquals(Status.RECOVERING, controller.status());
    }

    @Test void continuousSprintStopsBeforeUnloadedTerrainWithABoundedProbeBudget() {
        AdaptiveFlightController controller = controller(.25, .3);
        Probe probe = new Probe((x, z) -> x == 0 && z == 0);
        AABB position = BODY;
        double finalSpeed = 0;

        for (int tick = 0; tick < 100; tick++) {
            controller.tick();
            int previousChecks = probe.chunkChecks;
            int previousCollisions = probe.boxes.size();
            var step = AdaptiveFlightStep.move(controller, new Motion(64, 0, 0),
                64, 0, position, probe, 64, .25, true);
            assertEquals(CHUNKS, step.corridor().reason());
            assertBoundedScan(step, probe, previousChecks, previousCollisions);
            position = moved(position, step.motion());
            assertTrue(position.maxX <= 15.75 + EPSILON);
            assertEquals(step.motion(), controller.output());
            finalSpeed = step.motion().length();
        }

        assertTrue(position.maxX > 15.70, "boost should retain the refined approach to the edge");
        assertTrue(finalSpeed < .01);
        assertCollisionBoxesLoaded(probe);
    }

    @Test void continuousSprintStopsBeforeAnObstacleAndRetainsTheClippedOutput() {
        AdaptiveFlightController controller = controller(.25, .3);
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(8, 64, -5, 8.5, 69, 5));
        AABB position = BODY;
        double finalSpeed = 0;

        for (int tick = 0; tick < 80; tick++) {
            controller.tick();
            int previousChecks = probe.chunkChecks;
            int previousCollisions = probe.boxes.size();
            var step = AdaptiveFlightStep.move(controller, new Motion(64, 0, 0),
                64, 0, position, probe, 64, .25, true);
            assertEquals(OBSTACLE, step.corridor().reason());
            assertBoundedScan(step, probe, previousChecks, previousCollisions);
            position = moved(position, step.motion());
            assertTrue(position.maxX <= 7.75 + EPSILON);
            assertEquals(step.motion(), controller.output());
            finalSpeed = step.motion().length();
        }

        assertTrue(position.maxX > 7.70);
        assertTrue(finalSpeed < .01);
    }

    @Test void sprintCannotExceedTheBrakingCeilingOfTheCappedLookahead() {
        AdaptiveFlightController controller = controller(.25, .3);
        Probe probe = new Probe();

        var step = AdaptiveFlightStep.move(controller, new Motion(64, 0, 0),
            64, 0, BODY, probe, 1000, .35, true);

        double clearance = FlightCorridor.MAX_DISTANCE - .35;
        double cap = Math.sqrt(.3 * .3 + 2 * .3 * clearance) - .3;
        assertEquals(CLEAR, step.corridor().reason());
        assertEquals(FlightCorridor.MAX_DISTANCE, step.corridor().clearDistance());
        assertEquals(cap, step.motion().horizontalLength(), EPSILON);
        assertEquals(step.motion(), controller.output());
        assertTrue(step.lookaheadLimited());
        assertTrue(step.terrainLimited());
        assertEquals(FlightCorridor.MAX_SEGMENTS, probe.boxes.size());
        assertBoundedScan(step, probe, 0, 0);
    }

    @Test void sprintStillRespectsTheConfiguredHorizontalAndVerticalCeilings() {
        AdaptiveFlightController controller = controller(.25, .5);
        Probe probe = new Probe();

        var step = AdaptiveFlightStep.move(controller, new Motion(8, 4, 0),
            2, .1, BODY, probe, 64, .25, true);

        assertTrue(step.motion().horizontalLength() <= 2 + EPSILON);
        assertTrue(Math.abs(step.motion().y()) <= .1 + EPSILON);
        assertEquals(step.motion(), controller.output());
    }

    private static AdaptiveFlightController controller(double acceleration, double braking) {
        return new AdaptiveFlightController(new Settings(acceleration, braking, .5, 2, 3, .1));
    }

    private static void assertMotion(Motion expected, Motion actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }

    private static double difference(Motion a, Motion b) {
        return Math.hypot(Math.hypot(a.x() - b.x(), a.y() - b.y()), a.z() - b.z());
    }

    private static AABB moved(AABB box, Motion motion) {
        return box.move(motion.x(), motion.y(), motion.z());
    }

    private static void assertBoundedScan(AdaptiveFlightStep.Result step, Probe probe,
                                          int previousChecks, int previousCollisions) {
        int maxSegments = FlightCorridor.MAX_SEGMENTS + FlightCorridor.REFINEMENT_STEPS;
        assertTrue(step.corridor().segments() <= maxSegments);
        assertTrue(probe.chunkChecks - previousChecks <= maxSegments * FlightCorridor.MAX_CHUNKS_PER_SEGMENT);
        assertTrue(probe.boxes.size() - previousCollisions <= maxSegments);
        assertEquals(step.corridor().chunkChecks(), probe.chunkChecks - previousChecks);
        assertEquals(step.corridor().collisionChecks(), probe.boxes.size() - previousCollisions);
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
        private final BiPredicate<Integer, Integer> loaded;
        private final List<AABB> boxes = new ArrayList<>();
        private final List<AABB> obstacles = new ArrayList<>();
        private int chunkChecks;

        private Probe() { this((x, z) -> true); }
        private Probe(BiPredicate<Integer, Integer> loaded) { this.loaded = loaded; }

        @Override public boolean loaded(int chunkX, int chunkZ) {
            chunkChecks++;
            return loaded.test(chunkX, chunkZ);
        }

        @Override public boolean clear(AABB swept) {
            boxes.add(swept);
            return obstacles.stream().noneMatch(swept::intersects);
        }
    }
}
