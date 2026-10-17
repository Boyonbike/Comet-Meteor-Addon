package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import dev.tntcoating.core.AdaptiveFlightStep.Controls;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static dev.tntcoating.core.FlightCorridor.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFlightControlsTest {
    private static final double EPSILON = 1e-9;
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);

    @Test void percentagesScaleTheAttainableMaximumAtDifferentHorizonsBrakingAndBackoffs() {
        for (double[] tuning : new double[][] {{8, .15, .1}, {64, .3, .35}, {1000, 2, .5}}) {
            for (int corrections : new int[] {0, 1, 2}) {
                for (double fraction : new double[] {.1, .25, .5, 1}) {
                    AdaptiveFlightController controller = controller(100, tuning[1]);
                    controller.corrected(corrections);
                    double maximum = maximum(tuning[1], tuning[0], tuning[2]) * controller.speedFactor();
                    AdaptiveFlightStep.Result result = null;

                    for (int tick = 0; tick < 3; tick++) {
                        controller.tick();
                        result = AdaptiveFlightStep.move(controller, new Motion(.01, 0, 0),
                            .01, 0, BODY, new Probe(), tuning[0], tuning[2],
                            new Controls(false, fraction, 0, false));
                    }

                    assertEquals(maximum, result.adaptiveMaximum(), EPSILON,
                        "the reported maximum includes correction backoff once, before the percentage");
                    assertEquals(maximum * fraction, result.motion().x(), EPSILON,
                        "even small percentages must reduce the attainable cap, not the raw 64-block target");
                    assertEquals(0, result.motion().y(), EPSILON);
                    assertEquals(result.motion(), controller.output());
                    assertEquals(CLEAR, result.corridor().reason());
                }
            }
        }
    }

    @Test void percentageAlsoScalesTheMaximumBeforeANearbyWall() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(8, 64, -5, 9, 69, 5));
        AdaptiveFlightStep.Result full = AdaptiveFlightStep.move(controller(100, .3),
            new Motion(1, 0, 0), 1, 0, BODY, probe, 64, .25,
            new Controls(false, 1, 0, false));
        AdaptiveFlightStep.Result half = AdaptiveFlightStep.move(controller(100, .3),
            new Motion(1, 0, 0), 1, 0, BODY, probe, 64, .25,
            new Controls(false, .5, 0, false));

        assertEquals(OBSTACLE, full.corridor().reason());
        assertEquals(full.adaptiveMaximum(), half.adaptiveMaximum(), EPSILON);
        assertEquals(full.motion().x() * .5, half.motion().x(), EPSILON);
        assertTrue(full.adaptiveMaximum() < maximum(.3, 64, .25));
        assertTrue(BODY.maxX + full.motion().x() < 8 - .25 + EPSILON);
    }

    @Test void horizontalDirectionUsesTheEngineScaleIndependentlyOfTheStockSpeed() {
        var low = AdaptiveFlightStep.move(controller(100, .3), new Motion(.003, 0, .004),
            .005, 0, BODY, new Probe(), 64, .35, new Controls(false, .5, 0, false));
        var high = AdaptiveFlightStep.move(controller(100, .3), new Motion(30, 0, 40),
            50, 0, BODY, new Probe(), 64, .35, new Controls(false, .5, 0, false));

        assertMotion(low.motion(), high.motion());
        assertEquals(.75, low.motion().x() / low.motion().z(), EPSILON);
        assertEquals(low.adaptiveMaximum() * .5, low.motion().horizontalLength(), EPSILON);
    }

    @Test void normalVerticalInputKeepsItsConfiguredSpeed() {
        var up = AdaptiveFlightStep.move(controller(100, .3), new Motion(0, .1, 0),
            1, .1, BODY, new Probe(), 64, .35, new Controls(false, 1, 1, false));
        var down = AdaptiveFlightStep.move(controller(100, .3), new Motion(0, -.1, 0),
            1, .1, BODY, new Probe(), 64, .35, new Controls(false, 1, -1, false));

        assertMotion(new Motion(0, .1, 0), up.motion());
        assertMotion(new Motion(0, -.1, 0), down.motion());
    }

    @Test void sprintBoostsIntentionalClimbAndDescentIncludingPureVerticalFlight() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightController controller = controller(100, .3);
            var vertical = AdaptiveFlightStep.move(controller, new Motion(0, direction * .1, 0),
                0, .1, BODY, new Probe(), 64, .35,
                new Controls(true, 1, direction, false));
            assertMotion(new Motion(0, direction * vertical.adaptiveMaximum(), 0), vertical.motion());
            assertTrue(Math.abs(vertical.motion().y()) > .1);
            assertTrue(controller.isBoosting(), "pure vertical Sprint is an active boost");

            var diagonal = AdaptiveFlightStep.move(controller(100, .3), new Motion(.1, direction * .1, 0),
                .1, .1, BODY, new Probe(), 64, .35,
                new Controls(true, 1, direction, false));
            assertEquals(diagonal.adaptiveMaximum(), diagonal.motion().length(), EPSILON);
            assertEquals(diagonal.motion().x(), direction * diagonal.motion().y(), EPSILON);
            assertTrue(Math.abs(diagonal.motion().y()) > .1);
        }
    }

    @Test void sprintVerticalMovementAcceleratesAndReversesWithinTheVectorBudget() {
        AdaptiveFlightController controller = controller(.25, .5);
        Probe probe = new Probe();
        Motion previous = Motion.ZERO;
        for (double expected : new double[] {.25, .5, .75}) {
            var step = AdaptiveFlightStep.move(controller, new Motion(0, .1, 0),
                0, .1, BODY, probe, 64, .35, new Controls(true, 1, 1, false));
            assertEquals(expected, step.motion().y(), EPSILON);
            assertTrue(difference(previous, step.motion()) <= .25 + EPSILON);
            previous = step.motion();
        }

        var reversed = AdaptiveFlightStep.move(controller, new Motion(0, -.1, 0),
            0, .1, BODY, probe, 64, .35, new Controls(true, 1, -1, false));
        assertEquals(.25, reversed.motion().y(), EPSILON);
        assertEquals(.5, difference(previous, reversed.motion()), EPSILON);

        var released = AdaptiveFlightStep.move(controller, Motion.ZERO,
            0, .1, BODY, probe, 64, .35, new Controls(true, 1, 0, false));
        assertMotion(Motion.ZERO, released.motion());
        assertFalse(controller.isBoosting());
    }

    @Test void sprintDoesNotBoostAutomaticVerticalDriftWithoutAnIntentionalVerticalKey() {
        var step = AdaptiveFlightStep.move(controller(100, .3), new Motion(.1, -.02, 0),
            .1, .1, BODY, new Probe(), 64, .35, new Controls(true, 1, 0, false));

        assertTrue(step.motion().horizontalLength() > 1);
        assertTrue(step.motion().y() < 0);
        assertTrue(Math.abs(step.motion().y()) <= .02 + EPSILON);
    }

    @Test void zeroConfiguredAxesAndZeroPercentageStopExistingMomentumImmediately() {
        AdaptiveFlightController horizontalDisabled = controller(.25, .5);
        horizontalDisabled.accept(new Motion(2, 1, 0));
        var verticalOnly = AdaptiveFlightStep.move(horizontalDisabled, new Motion(2, .1, 0),
            0, .1, BODY, new Probe(), 64, .35, new Controls(true, 1, 1, false));
        assertEquals(0, verticalOnly.motion().horizontalLength(), EPSILON);
        assertTrue(verticalOnly.motion().y() > 0);

        AdaptiveFlightController verticalDisabled = controller(.25, .5);
        verticalDisabled.accept(new Motion(2, 1, 0));
        var horizontalOnly = AdaptiveFlightStep.move(verticalDisabled, new Motion(2, .1, 0),
            2, 0, BODY, new Probe(), 64, .35, new Controls(true, 1, 1, false));
        assertEquals(0, horizontalOnly.motion().y(), EPSILON);
        assertTrue(horizontalOnly.motion().horizontalLength() > 0);

        for (boolean zeroPercentage : new boolean[] {false, true}) {
            AdaptiveFlightController controller = controller(.25, .5);
            controller.accept(new Motion(2, 1, 0));
            Probe probe = new Probe();
            var stopped = AdaptiveFlightStep.move(controller, new Motion(2, .1, 0),
                zeroPercentage ? 2 : 0, zeroPercentage ? .1 : 0, BODY, probe, 64, .35,
                new Controls(true, zeroPercentage ? 0 : 1, 1, true));
            assertMotion(Motion.ZERO, stopped.motion());
            assertEquals(Motion.ZERO, controller.output());
            assertNull(stopped.corridor());
            assertEquals(0, probe.chunkChecks);
            assertTrue(probe.boxes.isEmpty());
        }
    }

    @Test void harmlessMotionBypassesBlockContactButStillChecksLoadedChunks() {
        Probe probe = touchingWall();
        var step = AdaptiveFlightStep.move(controller(.1, .5), new Motion(1, 0, 1),
            1, 0, BODY, probe, 64, .35, new Controls(false, 1, 0, true));

        assertTrue(step.safeManeuvering());
        assertTrue(step.motion().x() > 0 && step.motion().z() > 0,
            "Minecraft receives harmless contact motion and resolves the wall itself");
        assertTrue(step.motion().length() <= .3);
        assertTrue(probe.boxes.isEmpty(), "a harmless contact must not read collision shapes");
        assertTrue(probe.chunkChecks > 0);

        Probe guardedProbe = touchingWall();
        var guarded = AdaptiveFlightStep.move(controller(.1, .5), new Motion(1, 0, 1),
            1, 0, BODY, guardedProbe, 64, .35, new Controls(false, 1, 0, false));
        assertFalse(guarded.safeManeuvering());
        assertMotion(Motion.ZERO, guarded.motion());
        assertFalse(guardedProbe.boxes.isEmpty());
    }

    @Test void priorFastMotionMustBeStoppedBeforeTheWallCanReleaseToASafeCrawl() {
        AdaptiveFlightController controller = controller(1, .5);
        controller.accept(new Motion(2, 0, 0));
        Probe probe = touchingWall();
        Controls controls = new Controls(false, 1, 0, true);
        Motion requested = new Motion(1, 0, 1);

        var stopped = AdaptiveFlightStep.move(controller, requested,
            1, 0, BODY, probe, 64, .35, controls);
        assertMotion(Motion.ZERO, stopped.motion());
        assertFalse(stopped.safeManeuvering());
        assertEquals(OBSTACLE, stopped.corridor().reason());

        // Keep the body at wall contact: Minecraft clips X movement against this solid wall.
        // Advancing the complete requested vector here would incorrectly place it inside a block.
        int previousChecks = probe.chunkChecks;
        int previousCollisions = probe.boxes.size();
        var crawl = AdaptiveFlightStep.move(controller, requested,
            1, 0, BODY, probe, 64, .35, controls);
        assertTrue(crawl.safeManeuvering());
        assertTrue(crawl.motion().x() > 0 && crawl.motion().z() > 0);
        assertTrue(crawl.motion().length() <= .3);
        assertEquals(crawl.motion(), controller.output());
        assertBoundedScan(probe, previousChecks, previousCollisions, true);

        probe.obstacles.clear();
        previousChecks = probe.chunkChecks;
        previousCollisions = probe.boxes.size();
        var accelerated = AdaptiveFlightStep.move(controller, requested,
            1, 0, BODY, probe, 64, .35, controls);
        assertTrue(accelerated.motion().length() > .3);
        assertFalse(accelerated.safeManeuvering(), "safe contact does not latch the guard off");
        assertTrue(probe.boxes.size() > previousCollisions);
        assertBoundedScan(probe, previousChecks, previousCollisions, false);
    }

    @Test void fullThreeDimensionalPreviousMomentumPreventsPrematureCollisionRelease() {
        AdaptiveFlightController controller = controller(.1, .1);
        controller.accept(new Motion(.22, .22, 0));
        Probe probe = new Probe();
        var step = AdaptiveFlightStep.move(controller, new Motion(.01, .05, 0),
            1, .05, BODY, probe, 64, .35, new Controls(false, .01, 0, true));

        assertTrue(step.motion().length() < .3);
        assertFalse(step.safeManeuvering(), "the previous combined speed exceeds the harmless threshold");
        assertFalse(probe.boxes.isEmpty());
    }

    @Test void harmlessFlightStillStopsBeforeAnUnloadedChunk() {
        AdaptiveFlightController controller = controller(.2, .5);
        Probe probe = new Probe((x, z) -> x == 0 && z == 0);
        AABB position = new AABB(15.3, 65, .2, 15.9, 66.8, .8);

        for (int tick = 0; tick < 8; tick++) {
            int previousChecks = probe.chunkChecks;
            var step = AdaptiveFlightStep.move(controller, new Motion(1, 0, 0),
                1, 0, position, probe, 64, .05, new Controls(false, 1, 0, true));
            assertTrue(step.motion().length() <= .3);
            assertTrue(step.safeManeuvering());
            assertEquals(CHUNKS, step.corridor().reason());
            assertTrue(probe.chunkChecks > previousChecks);
            assertTrue(probe.boxes.isEmpty());
            position = position.move(step.motion().x(), step.motion().y(), step.motion().z());
            assertTrue(position.maxX <= 15.95 + EPSILON,
                "disabling harmless block avoidance must keep unloaded-terrain protection");
        }
    }

    @Test void harmlessCollisionReleaseStillFailsClosedWithoutATerrainProbe() {
        AdaptiveFlightController controller = controller(.1, .5);
        var step = AdaptiveFlightStep.move(controller, new Motion(1, 0, 0),
            1, 0, BODY, null, 64, .35, new Controls(false, 1, 0, true));

        assertEquals(INVALID, step.corridor().reason());
        assertMotion(Motion.ZERO, step.motion());
        assertEquals(Motion.ZERO, controller.output());
    }

    @Test void maximumRequestsAndSafeFallbackKeepTheTerrainProbeBudgetBounded() {
        for (Probe probe : List.of(new Probe(), touchingWall(), new Probe((x, z) -> x == 0))) {
            AdaptiveFlightController controller = controller(100, .3);
            for (int tick = 0; tick < 10; tick++) {
                int previousChecks = probe.chunkChecks;
                int previousCollisions = probe.boxes.size();
                var step = AdaptiveFlightStep.move(controller, new Motion(1, 1, 1),
                    1, 1, BODY, probe, 1000, .35, new Controls(true, 1, 1, true));
                assertBoundedScan(probe, previousChecks, previousCollisions, step.safeManeuvering());
            }
        }
    }

    private static AdaptiveFlightController controller(double acceleration, double braking) {
        return new AdaptiveFlightController(new Settings(acceleration, braking, .5, 0, 1000, 0));
    }

    private static double maximum(double braking, double lookahead, double margin) {
        double clearance = Math.max(0, Math.min(lookahead, FlightCorridor.MAX_DISTANCE) - margin);
        return Math.min(clearance, Math.sqrt(braking * braking + 2 * braking * clearance) - braking);
    }

    private static Probe touchingWall() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(.8, 64, -100, 1.8, 69, 100));
        return probe;
    }

    private static void assertMotion(Motion expected, Motion actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }

    private static double difference(Motion first, Motion second) {
        return Math.hypot(Math.hypot(first.x() - second.x(), first.y() - second.y()), first.z() - second.z());
    }

    private static void assertBoundedScan(Probe probe, int previousChecks, int previousCollisions,
                                          boolean possibleSafeFallback) {
        int fullScan = FlightCorridor.MAX_SEGMENTS + FlightCorridor.REFINEMENT_STEPS;
        int shortRecheck = possibleSafeFallback ? 1 + FlightCorridor.REFINEMENT_STEPS : 0;
        assertTrue(probe.boxes.size() - previousCollisions <= fullScan,
            "safe fallback adds chunk checks, not a second collision scan");
        assertTrue(probe.chunkChecks - previousChecks
            <= (fullScan + shortRecheck) * FlightCorridor.MAX_CHUNKS_PER_SEGMENT);
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
