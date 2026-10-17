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

    @Test void sprintFromRestUsesTheConfiguredAcceleration() {
        AdaptiveFlightController controller = controller(.25, .5);
        Motion actual = controller.step(new Motion(3, 0, 4), UNLIMITED, true);

        assertMotion(new Motion(.15, 0, .2), actual);
        assertEquals(.25, actual.horizontalLength(), EPSILON);
        assertEquals(actual, controller.output());
        assertTrue(controller.isBoosting());
    }

    @Test void sprintAcceleratesExistingMomentumWithoutAnInstantJump() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(.6, .4, .8));

        Motion actual = controller.step(new Motion(3, .4, 4), UNLIMITED, true);

        assertMotion(new Motion(.75, .4, 1), actual);
        assertEquals(1.25, actual.horizontalLength(), EPSILON);
    }

    @Test void sprintKeepsVerticalMovementWithinTheFullVectorAccelerationBudget() {
        AdaptiveFlightController controller = controller(.25, .5);
        Motion raised = controller.step(new Motion(5, 2, 0), UNLIMITED, true);
        assertEquals(.25, raised.length(), EPSILON);
        assertTrue(raised.y() > 0 && raised.y() < .25);

        Motion lowered = controller.step(new Motion(5, -2, 0), UNLIMITED, true);
        assertTrue(difference(lowered, raised) <= .25 + EPSILON);
        assertTrue(lowered.y() > -2, "sprint must not snap vertical velocity");
    }

    @Test void normalAndSprintControllerEntryPointsUseTheSameAcceleration() {
        AdaptiveFlightController explicit = controller(.25, .5);
        AdaptiveFlightController original = controller(.25, .5);
        AdaptiveFlightController sprint = controller(.25, .5);
        Motion target = new Motion(4, 0, 0);

        assertMotion(new Motion(.25, 0, 0), explicit.step(target, UNLIMITED, false));
        assertEquals(explicit.output(), original.step(target, UNLIMITED));
        assertEquals(explicit.output(), sprint.step(target, UNLIMITED, true));
    }

    @Test void productionSprintRampsPastNormalCruisingToTheAdaptiveLimit() {
        AdaptiveFlightController normal = controller(.25, .3);
        AdaptiveFlightController sprint = controller(.25, .3);
        Probe probe = new Probe();
        Motion requested = new Motion(2, 0, 0);
        double previous = 0;

        for (int tick = 0; tick < 100; tick++) {
            normal.tick();
            sprint.tick();
            AdaptiveFlightStep.move(normal, requested, 2, 1, BODY, probe, 64, .35, false);
            var step = AdaptiveFlightStep.move(sprint, requested, 2, 1, BODY, probe, 64, .35, true);
            assertTrue(step.motion().x() - previous <= .25 + EPSILON);
            assertTrue(step.motion().x() >= previous - EPSILON);
            previous = step.motion().x();
        }

        double cap = Math.sqrt(.3 * .3 + 2 * .3 * (64 - .35)) - .3;
        assertMotion(requested, normal.output());
        assertMotion(new Motion(cap, 0, 0), sprint.output());
        assertTrue(sprint.output().horizontalLength() > 2 * normal.output().horizontalLength(),
            "sprint must reach the adaptive maximum rather than a fixed speed multiplier");
    }

    @Test void sprintMaximumIsIndependentOfTheNormalCruiseSetting() {
        AdaptiveFlightController low = controller(.25, .3);
        AdaptiveFlightController high = controller(.25, .3);
        Probe probe = new Probe();
        for (int tick = 0; tick < 100; tick++) {
            var first = AdaptiveFlightStep.move(low, new Motion(.5, 0, 0),
                .5, 1, BODY, probe, 64, .35, true);
            var second = AdaptiveFlightStep.move(high, new Motion(4, 0, 0),
                4, 1, BODY, probe, 64, .35, true);
            assertMotion(first.motion(), second.motion());
        }
        assertTrue(low.output().horizontalLength() > 4);
    }

    @Test void sprintFromCruisingSpeedStillUsesAcceleration() {
        AdaptiveFlightController controller = controller(.15, .3);
        controller.accept(new Motion(2, 0, 0));

        var step = AdaptiveFlightStep.move(controller, new Motion(2, 0, 0),
            2, 1, BODY, new Probe(), 64, .35, true);

        assertMotion(new Motion(2.15, 0, 0), step.motion());
    }

    @Test void releasingSprintRestoresTheNormalHorizontalCeiling() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(4, 0, 0));

        var released = AdaptiveFlightStep.move(controller, new Motion(2, 0, 0),
            2, 1, BODY, new Probe(), 64, .35, false);

        assertMotion(new Motion(2, 0, 0), released.motion());
        assertEquals(released.motion(), controller.output());
        assertFalse(controller.isBoosting());
    }

    @Test void holdingSprintWithoutHorizontalInputStillBrakesToAStop() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(3, 0, 0));
        Probe probe = new Probe();

        var first = AdaptiveFlightStep.move(controller, Motion.ZERO,
            2, 1, BODY, probe, 64, .35, true);
        assertMotion(new Motion(2.5, 0, 0), first.motion());
        for (int tick = 0; tick < 8; tick++) {
            AdaptiveFlightStep.move(controller, Motion.ZERO, 2, 1, BODY, probe, 64, .35, true);
        }
        assertMotion(Motion.ZERO, controller.output());
        assertFalse(controller.isBoosting());
    }

    @Test void zeroConfiguredHorizontalSpeedStillDisablesHorizontalMovement() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(3, 0, 0));

        var step = AdaptiveFlightStep.move(controller, Motion.ZERO,
            0, 1, BODY, new Probe(), 64, .35, true);

        assertMotion(Motion.ZERO, step.motion());
        assertNull(step.corridor());
    }

    @Test void sprintDuringAReversalBrakesBeforeAcceleratingTheOtherWay() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(2, 0, 0));
        Probe probe = new Probe();
        double previous = 2;
        for (int tick = 0; tick < 40; tick++) {
            var step = AdaptiveFlightStep.move(controller, new Motion(-2, 0, 0),
                2, 1, BODY, probe, 64, .35, true);
            double current = step.motion().x();
            assertTrue(current <= previous + EPSILON);
            assertTrue(previous - current <= (previous > 0 ? .5 : .25) + EPSILON);
            previous = current;
        }
        assertTrue(previous < -2, "sprint must accelerate beyond normal speed after reversing");
    }

    @Test void sprintTurnsWithinTheNormalSteeringBudget() {
        AdaptiveFlightController controller = controller(.25, .5);
        Motion initial = new Motion(1, 0, 0);
        controller.accept(initial);

        var step = AdaptiveFlightStep.move(controller, new Motion(0, 0, 2),
            2, 1, BODY, new Probe(), 64, .35, true);

        assertEquals(.25, difference(initial, step.motion()), EPSILON);
        assertTrue(step.motion().x() > 0 && step.motion().z() > 0);
        assertTrue(step.motion().horizontalLength() < 1.25);
    }

    @Test void aHardTerrainLimitClipsSprintAndStoresTheClippedMomentum() {
        AdaptiveFlightController controller = controller(.25, .5);
        Motion clipped = controller.step(new Motion(5, 0, 0), .1, true);

        assertMotion(new Motion(.1, 0, 0), clipped);
        assertEquals(Status.TERRAIN_LIMITED, controller.status());
        assertEquals(clipped, controller.output());
        assertMotion(new Motion(.35, 0, 0), controller.step(new Motion(5, 0, 0), UNLIMITED, true));
    }

    @Test void correctionPauseAndGradualRecoveryStillApplyWhileSprintIsHeld() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.corrected(1);
        Probe probe = new Probe();
        Motion requested = new Motion(2, 0, 0);

        assertMotion(Motion.ZERO, controller.step(requested, UNLIMITED, true));
        for (int tick = 0; tick < 2; tick++) {
            controller.tick();
            var paused = AdaptiveFlightStep.move(controller, requested,
                2, 1, BODY, probe, 64, .35, true);
            assertMotion(Motion.ZERO, paused.motion());
            assertNull(paused.corridor());
            assertEquals(Status.PAUSED, controller.status());
        }
        assertEquals(0, probe.chunkChecks);
        assertTrue(probe.boxes.isEmpty());

        controller.tick();
        var resumed = AdaptiveFlightStep.move(controller, requested,
            2, 1, BODY, probe, 64, .35, true);
        assertMotion(new Motion(.25, 0, 0), resumed.motion());
        assertEquals(Status.RECOVERING, controller.status());
        assertEquals(.5, controller.speedFactor(), EPSILON);
        controller.tick();
        var recovering = AdaptiveFlightStep.move(controller, requested,
            2, 1, BODY, probe, 64, .35, true);
        assertMotion(new Motion(.5, 0, 0), recovering.motion());
        assertEquals(.6, controller.speedFactor(), EPSILON);
    }

    @Test void sprintCannotMaskCorrectionBackoffBehindTheLookaheadLimit() {
        AdaptiveFlightController controller = new AdaptiveFlightController(
            new Settings(.25, .3, .5, 0, 1000, 0));
        controller.corrected(1);
        Probe probe = new Probe();
        for (int tick = 0; tick < 100; tick++) {
            controller.tick();
            AdaptiveFlightStep.move(controller, new Motion(2, 0, 0),
                2, 1, BODY, probe, 64, .35, true);
        }
        double cap = Math.sqrt(.3 * .3 + 2 * .3 * (64 - .35)) - .3;
        assertMotion(new Motion(cap * .5, 0, 0), controller.output());
        assertEquals(.5, controller.speedFactor(), EPSILON);
    }

    @Test void repeatedCorrectionsRetainTheirMinimumBackoffDuringSprint() {
        AdaptiveFlightController controller = new AdaptiveFlightController(
            new Settings(.25, .3, .5, 0, 1000, 0));
        controller.corrected(20);
        Probe probe = new Probe();
        for (int tick = 0; tick < 40; tick++) {
            AdaptiveFlightStep.move(controller, new Motion(2, 0, 0),
                2, 1, BODY, probe, 64, .35, true);
        }
        double cap = Math.sqrt(.3 * .3 + 2 * .3 * (64 - .35)) - .3;
        assertEquals(AdaptiveFlightController.MIN_SPEED_FACTOR, controller.speedFactor(), EPSILON);
        assertMotion(new Motion(cap * .1, 0, 0), controller.output());
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
            var step = AdaptiveFlightStep.move(controller, new Motion(2, 0, 0),
                2, 0, position, probe, 64, .25, true);
            assertEquals(CHUNKS, step.corridor().reason());
            assertBoundedScan(step, probe, previousChecks, previousCollisions);
            position = moved(position, step.motion());
            assertTrue(position.maxX <= 15.75 + EPSILON);
            assertEquals(step.motion(), controller.output());
            finalSpeed = step.motion().length();
        }
        assertTrue(position.maxX > 15.70);
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
            var step = AdaptiveFlightStep.move(controller, new Motion(2, 0, 0),
                2, 0, position, probe, 64, .25, true);
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

    @Test void sprintMaximumRespondsToTheAvailableLookaheadAndBraking() {
        double previous = 0;
        for (double[] tuning : new double[][] {{8, .3}, {64, .3}, {64, .6}}) {
            AdaptiveFlightController controller = controller(.25, tuning[1]);
            Probe probe = new Probe();
            AdaptiveFlightStep.Result step = null;
            for (int tick = 0; tick < 100; tick++) {
                step = AdaptiveFlightStep.move(controller, new Motion(2, 0, 0),
                    2, 1, BODY, probe, tuning[0], .35, true);
            }
            double cap = Math.sqrt(tuning[1] * tuning[1] + 2 * tuning[1] * (tuning[0] - .35)) - tuning[1];
            assertEquals(cap, step.motion().horizontalLength(), EPSILON);
            assertTrue(step.motion().horizontalLength() > previous);
            assertTrue(step.lookaheadLimited());
            previous = step.motion().horizontalLength();
        }
    }

    @Test void sprintCannotExceedTheBrakingCeilingOfTheCappedLookahead() {
        AdaptiveFlightController controller = controller(.25, .3);
        controller.accept(new Motion(64, 0, 0));
        Probe probe = new Probe();
        var step = AdaptiveFlightStep.move(controller, new Motion(2, 0, 0),
            2, 0, BODY, probe, 1000, .35, true);

        double cap = Math.sqrt(.3 * .3 + 2 * .3 * (FlightCorridor.MAX_DISTANCE - .35)) - .3;
        assertEquals(CLEAR, step.corridor().reason());
        assertEquals(FlightCorridor.MAX_DISTANCE, step.corridor().clearDistance());
        assertEquals(cap, step.motion().horizontalLength(), EPSILON);
        assertEquals(step.motion(), controller.output());
        assertTrue(step.lookaheadLimited());
        assertTrue(step.terrainLimited());
        assertEquals(FlightCorridor.MAX_SEGMENTS, probe.boxes.size());
        assertBoundedScan(step, probe, 0, 0);
    }

    @Test void sprintRaisesTheHorizontalCeilingButRetainsTheVerticalCeiling() {
        AdaptiveFlightController controller = controller(.25, .5);
        controller.accept(new Motion(4, 2, 0));
        var step = AdaptiveFlightStep.move(controller, new Motion(2, 4, 0),
            2, .1, BODY, new Probe(), 64, .25, true);

        assertTrue(step.motion().horizontalLength() > 4);
        assertTrue(step.motion().horizontalLength() <= 4.25 + EPSILON);
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
