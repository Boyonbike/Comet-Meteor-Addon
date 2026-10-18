package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static dev.tntcoating.core.FlightCorridor.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveSpeedStepTest {
    private static final double EPSILON = 1e-9;
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);

    @Test void percentagesUseTheAttainableMaximumAndCorrectionIsAppliedOnce() {
        AdaptiveFlightController controller = controller(100, .5);
        controller.corrected(1);
        Probe probe = new Probe();
        var result = move(controller, new Motion(1, 0, 0), 6, .35, probe, false);
        assertEquals(3, result.adaptiveMaximum(), EPSILON);
        assertEquals(3 * .35, result.motion().x(), EPSILON);
        assertEquals(result.motion(), controller.output());
    }

    @Test void sprintReleaseBrakesSmoothlyTowardTheSelectedCruisePercentage() {
        AdaptiveFlightController controller = controller(.5, .4);
        Probe probe = new Probe();
        for (int tick = 0; tick < 20; tick++) {
            move(controller, new Motion(1, 0, 0), 6, 1, probe, false);
        }
        assertEquals(6, controller.output().x(), EPSILON);
        double previous = 6;
        for (int tick = 0; tick < 20; tick++) {
            var result = move(controller, new Motion(1, 0, 0), 6, .35, probe, false);
            assertTrue(previous - result.motion().x() <= .4 + EPSILON);
            assertTrue(result.motion().x() <= previous + EPSILON);
            assertTrue(result.motion().x() >= 6 * .35 - EPSILON);
            previous = result.motion().x();
        }
        assertEquals(6 * .35, previous, EPSILON);
    }

    @Test void diagonalAndSmallInputsHaveTheSameNormalizedSpeed() {
        Probe probe = new Probe();
        var cardinal = move(controller(100, .5), new Motion(.01, 5, 0), 4, 1, probe, false);
        var diagonal = move(controller(100, .5), new Motion(1e300, 1e300, 1e300), 4, 1, probe, false);
        assertEquals(4, cardinal.motion().horizontalLength(), EPSILON);
        assertEquals(4, diagonal.motion().horizontalLength(), EPSILON);
        assertEquals(diagonal.motion().x(), diagonal.motion().z(), EPSILON);
        assertEquals(0, diagonal.motion().y());
    }

    @Test void releaseAndDedicatedBrakeUseTheBrakingRateAndBrakeIgnoresMovementInput() {
        Probe probe = new Probe();
        AdaptiveFlightController released = controller(.2, .5);
        AdaptiveFlightController braking = controller(.2, .5);
        released.accept(new Motion(2, 0, 0));
        braking.accept(new Motion(2, 0, 0));
        for (double expected : new double[] {1.5, 1, .5, 0}) {
            var release = move(released, Motion.ZERO, 6, 1, probe, false);
            var brake = AdaptiveSpeedStep.move(braking, new Motion(0, 0, 1), 6, 1,
                true, true, BODY, probe, probe.chunksOnly(), 64, .1, false);
            assertEquals(expected, release.motion().x(), EPSILON);
            assertEquals(release.motion(), brake.motion());
            assertFalse(braking.isBoosting());
        }
    }

    @Test void reversingShedsForwardMomentumBeforeAcceleratingBackwards() {
        AdaptiveFlightController controller = controller(.2, .5);
        controller.accept(new Motion(2, 0, 0));
        Probe probe = new Probe();
        assertEquals(1.5, move(controller, new Motion(-1, 0, 0), 6, 1, probe, false).motion().x(), EPSILON);
        assertEquals(1, move(controller, new Motion(-1, 0, 0), 6, 1, probe, false).motion().x(), EPSILON);
        assertEquals(.5, move(controller, new Motion(-1, 0, 0), 6, 1, probe, false).motion().x(), EPSILON);
        assertEquals(0, move(controller, new Motion(-1, 0, 0), 6, 1, probe, false).motion().x(), EPSILON);
        assertEquals(-.2, move(controller, new Motion(-1, 0, 0), 6, 1, probe, false).motion().x(), EPSILON);
    }

    @Test void zeroCapAndZeroFractionStopImmediatelyWithoutTerrainQueries() {
        for (boolean zeroCap : new boolean[] {true, false}) {
            AdaptiveFlightController controller = controller(.1, .1);
            controller.accept(new Motion(4, 2, 0));
            Probe probe = new Probe();
            var result = move(controller, new Motion(1, 0, 0), zeroCap ? 0 : 6,
                zeroCap ? 1 : 0, probe, false);
            assertEquals(Motion.ZERO, result.motion());
            assertEquals(Motion.ZERO, controller.output());
            assertNull(result.corridor());
            assertEquals(0, probe.loadedChecks);
        }
    }

    @Test void hardCapAndBoundedBrakingHorizonAreImmediatePhysicalLimits() {
        AdaptiveFlightController controller = controller(100, .3);
        controller.accept(new Motion(30, 0, 0));
        Probe probe = new Probe();
        var result = AdaptiveSpeedStep.move(controller, new Motion(1, 0, 0), 64, 1,
            false, false, BODY, probe, probe.chunksOnly(), 1e6, .35, false);
        double expected = Math.sqrt(.3 * .3 + 2 * .3 * (64 - .35)) - .3;
        assertEquals(expected, result.adaptiveMaximum(), EPSILON);
        assertEquals(expected, result.motion().horizontalLength(), EPSILON);
        assertEquals(FlightCorridor.MAX_DISTANCE, result.corridor().clearDistance());
        assertEquals(FlightCorridor.MAX_SEGMENTS, probe.boxes.size());
        var capped = move(controller, new Motion(1, 0, 0), .2, 1, probe, false);
        assertTrue(capped.motion().horizontalLength() <= .2);
    }

    @Test void cruisePercentageDoesNotScaleThePhysicalStoppingLimit() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(10, 64, -5, 11, 69, 5));

        var cruise = move(controller(100, .2), new Motion(1, 0, 0), 2, .35, probe, false);
        var sprint = move(controller(100, .2), new Motion(1, 0, 0), 2, 1, probe, false);

        assertEquals(OBSTACLE, cruise.corridor().reason());
        assertTrue(cruise.adaptiveMaximum() > .7,
            "the wall is outside the selected cruise speed's stopping distance");
        assertEquals(.7, cruise.motion().x(), EPSILON);
        assertEquals(sprint.adaptiveMaximum(), sprint.motion().x(), EPSILON,
            "the same wall must still limit the full adaptive speed");
    }

    @Test void cornerScanFollowsTheActualBlendedTrajectory() {
        AdaptiveFlightController controller = controller(.5, .5);
        controller.accept(new Motion(2, 0, 0));
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(3.5, 65, 1.25, 3.6, 67, 1.35));
        var result = move(controller, new Motion(0, 0, 1), 2, 1, probe, false);
        assertEquals(OBSTACLE, result.corridor().reason());
        double expectedX = 2 - .5 / Math.sqrt(2);
        double expectedZ = .5 / Math.sqrt(2);
        assertEquals(expectedZ / expectedX, result.motion().z() / result.motion().x(), EPSILON);
        AABB swept = probe.boxes.getFirst();
        assertEquals(expectedZ / expectedX, (swept.maxZ - BODY.maxZ) / (swept.maxX - BODY.maxX), EPSILON);
        assertTrue(result.terrainLimited());
        assertEquals(result.corridor().collisionChecks(), probe.boxes.size());
    }

    @Test void loadedChunkEdgeBrakesAcrossTicksWithoutEverCrossing() {
        AdaptiveFlightController controller = controller(.5, .3);
        Probe probe = new Probe((x, z) -> x == 0);
        AABB body = BODY;
        double peak = 0;
        double previous = 0;
        boolean decelerated = false;
        for (int tick = 0; tick < 100; tick++) {
            var result = AdaptiveSpeedStep.move(controller, new Motion(1, 0, 0), 6, 1,
                false, false, body, probe, probe.chunksOnly(), 64, .25, true);
            double speed = result.motion().horizontalLength();
            peak = Math.max(peak, speed);
            decelerated |= speed < previous - EPSILON;
            previous = speed;
            body = body.move(result.motion().x(), 0, result.motion().z());
            assertTrue(body.maxX <= 15.75 + EPSILON);
            if (result.corridor() != null) assertTrue(result.corridor().segments()
                <= 2 * (FlightCorridor.MAX_SEGMENTS + FlightCorridor.REFINEMENT_STEPS));
        }
        assertTrue(peak > 1);
        assertTrue(decelerated);
        assertTrue(previous < .01);
        assertTrue(body.maxX > 15.70);
    }

    @Test void lowSpeedContactIsDelegatedToVanillaButKeepsChunkProtection() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(.8, 65, .2, 2, 67, .8));
        var result = move(controller(.1, .5), new Motion(1, 0, 0), .25, 1, probe, true);
        assertEquals(.1, result.motion().x(), EPSILON);
        assertTrue(result.safeManeuvering());
        assertTrue(probe.boxes.isEmpty());
        assertTrue(probe.loadedChecks > 0);

        Probe missing = new Probe((x, z) -> false);
        var blocked = move(controller(.1, .5), new Motion(1, 0, 0), .25, 1, missing, true);
        assertEquals(CHUNKS, blocked.corridor().reason());
        assertEquals(Motion.ZERO, blocked.motion());
    }

    @Test void crawlReactivatesAfterAnAccelerationStepHitsAWallWithoutPercentageOscillation() {
        AdaptiveFlightController controller = controller(1, .5);
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(.8, 65, .2, 2, 67, .8));
        for (int tick = 0; tick < 4; tick++) {
            var result = move(controller, new Motion(1, 0, 0), 6, .5, probe, true);
            assertTrue(result.safeManeuvering());
            assertEquals(.3, result.adaptiveMaximum(), EPSILON);
            assertEquals(.15, result.motion().x(), EPSILON);
            assertTrue(result.corridor().segments()
                <= 2 * (FlightCorridor.MAX_SEGMENTS + FlightCorridor.REFINEMENT_STEPS));
        }
    }

    @Test void fastWallContactMustStopBeforeSafeCrawlCanReactivate() {
        AdaptiveFlightController controller = controller(1, .5);
        controller.accept(new Motion(1, 0, 0));
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(.8, 65, .2, 2, 67, .8));
        var stopped = move(controller, new Motion(1, 0, 0), 6, 1, probe, true);
        assertFalse(stopped.safeManeuvering());
        assertEquals(Motion.ZERO, stopped.motion());
        var crawl = move(controller, new Motion(1, 0, 0), 6, 1, probe, true);
        assertTrue(crawl.safeManeuvering());
        assertEquals(.3, crawl.motion().x(), EPSILON);
    }

    @Test void disablingSafeManeuveringOrOmittingItsProbeRetainsWallCollisions() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(.8, 65, .2, 2, 67, .8));
        var disabled = move(controller(.1, .5), new Motion(1, 0, 0), .25, 1, probe, false);
        var missingProbe = AdaptiveSpeedStep.move(controller(.1, .5), new Motion(1, 0, 0), .25, 1,
            false, false, BODY, probe, null, 64, .1, true);
        assertEquals(Motion.ZERO, disabled.motion());
        assertEquals(Motion.ZERO, missingProbe.motion());
        assertEquals(OBSTACLE, missingProbe.corridor().reason());
    }

    @Test void correctionPauseDoesNotProbeOrPreserveMomentum() {
        AdaptiveFlightController controller = new AdaptiveFlightController(new Settings(.5, .5, .5, 2, 3, .1));
        controller.accept(new Motion(2, 0, 0));
        controller.corrected(1);
        Probe probe = new Probe();
        for (int tick = 0; tick < 2; tick++) {
            controller.tick();
            var result = move(controller, new Motion(1, 0, 0), 6, 1, probe, false);
            assertEquals(Motion.ZERO, result.motion());
            assertEquals(0, result.adaptiveMaximum());
            assertNull(result.corridor());
        }
        assertEquals(0, probe.loadedChecks);
    }

    @Test void invalidTerrainFailsClosedWithoutQueries() {
        AdaptiveFlightController controller = controller(.5, .5);
        controller.accept(new Motion(2, 0, 0));
        Probe probe = new Probe();
        var result = AdaptiveSpeedStep.move(controller, new Motion(1, 0, 0), 6, 1,
            false, false, null, probe, probe.chunksOnly(), 64, .1, false);
        assertEquals(INVALID, result.corridor().reason());
        assertEquals(Motion.ZERO, result.motion());
        assertEquals(Motion.ZERO, controller.output());
        assertEquals(0, probe.loadedChecks);
    }

    @Test void invalidSettingsAreRejectedBeforeChangingMomentum() {
        AdaptiveFlightController controller = controller(.5, .5);
        Motion previous = new Motion(2, 0, 0);
        controller.accept(previous);
        Probe probe = new Probe();
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                () -> move(controller, new Motion(1, 0, 0), invalid, 1, probe, false));
            assertThrows(IllegalArgumentException.class,
                () -> move(controller, new Motion(1, 0, 0), 6, invalid, probe, false));
            assertThrows(IllegalArgumentException.class, () -> AdaptiveSpeedStep.move(controller,
                Motion.ZERO, 6, 1, false, false, BODY, probe, probe.chunksOnly(), invalid, .1, false));
            assertThrows(IllegalArgumentException.class, () -> AdaptiveSpeedStep.move(controller,
                Motion.ZERO, 6, 1, false, false, BODY, probe, probe.chunksOnly(), 64, invalid, false));
        }
        assertThrows(IllegalArgumentException.class,
            () -> move(controller, Motion.ZERO, 6, 1.01, probe, false));
        assertEquals(previous, controller.output());
        assertEquals(0, probe.loadedChecks);
    }

    private static AdaptiveFlightController controller(double acceleration, double braking) {
        return new AdaptiveFlightController(new Settings(acceleration, braking, .5, 0, 100, 0));
    }

    private static AdaptiveSpeedStep.Result move(AdaptiveFlightController controller, Motion direction,
                                                 double maximum, double fraction, Probe probe, boolean safe) {
        return AdaptiveSpeedStep.move(controller, direction, maximum, fraction, false, false,
            BODY, probe, probe.chunksOnly(), 64, .1, safe);
    }

    private static final class Probe implements FlightCorridor.Probe {
        final BiPredicate<Integer, Integer> loaded;
        final List<AABB> boxes = new ArrayList<>();
        final List<AABB> obstacles = new ArrayList<>();
        int loadedChecks;

        Probe() { this((x, z) -> true); }
        Probe(BiPredicate<Integer, Integer> loaded) { this.loaded = loaded; }

        @Override public boolean loaded(int x, int z) {
            loadedChecks++;
            return loaded.test(x, z);
        }

        @Override public boolean clear(AABB swept) {
            boxes.add(swept);
            return obstacles.stream().noneMatch(swept::intersects);
        }

        FlightCorridor.Probe chunksOnly() {
            return new FlightCorridor.Probe() {
                @Override public boolean loaded(int x, int z) { return Probe.this.loaded(x, z); }
                @Override public boolean clear(AABB swept) { return true; }
            };
        }
    }
}
