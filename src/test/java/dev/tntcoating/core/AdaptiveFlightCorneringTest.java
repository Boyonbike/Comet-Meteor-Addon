package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFlightCorneringTest {
    private static final double EPSILON = 1e-10;
    private static final double UNLIMITED = Double.POSITIVE_INFINITY;

    @Test void highSpeedRightAngleCornersAlignInSixTicksWithoutLosingSpeed() {
        for (double speed : new double[] {1, 3, 6, 12}) {
            AdaptiveFlightController controller = controller(15);
            controller.accept(new Motion(speed, 0, 0));
            for (int tick = 1; tick <= 6; tick++) {
                Motion output = controller.step(new Motion(0, 0, speed), UNLIMITED);
                assertEquals(speed, output.horizontalLength(), EPSILON);
                assertEquals(Math.toRadians(15 * tick), heading(output), EPSILON);
            }
            assertMotion(new Motion(0, 0, speed), controller.output());
        }
    }

    @Test void harmlessSpeedsKeepOrdinaryPreciseSteering() {
        for (double speed : new double[] {.05, .1, .2, .3}) {
            AdaptiveFlightController assisted = controller(15);
            AdaptiveFlightController ordinary = controller(0);
            assisted.accept(new Motion(speed, .02, 0));
            ordinary.accept(new Motion(speed, .02, 0));
            Motion target = new Motion(0, .02, speed);
            assertEquals(ordinary.step(target, UNLIMITED), assisted.step(target, UNLIMITED));
        }
    }

    @Test void assistanceScalesSmoothlyFromActualMomentum() {
        // Default acceleration: ordinary control through .3, full assistance by .9.
        for (double speed : new double[] {.3, .45, .6, .75, .9}) {
            AdaptiveFlightController controller = controller(15);
            AdaptiveFlightController ordinary = controller(0);
            Motion initial = new Motion(speed, 0, 0);
            Motion target = new Motion(0, 0, speed);
            controller.accept(initial);
            ordinary.accept(initial);
            Motion legacy = ordinary.step(target, UNLIMITED);
            double weight = Math.min(1, Math.max(0, (speed - .3) / .6));
            weight = weight * weight * (3 - 2 * weight);
            Motion assisted = fromHeading(15, speed, 0);
            Motion expected = new Motion(legacy.x() * (1 - weight) + assisted.x() * weight,
                0, legacy.z() * (1 - weight) + assisted.z() * weight);
            assertMotion(expected, controller.step(target, UNLIMITED));
            assertTrue(controller.output().length() <= speed + EPSILON);
        }
    }

    @Test void fasterAccelerationRetainsOrdinaryControlUntilDriftNeedsAssistance() {
        AdaptiveFlightController assisted = new AdaptiveFlightController(new Settings(.5, .6, .5, 2, 3, .1, 15));
        AdaptiveFlightController ordinary = new AdaptiveFlightController(new Settings(.5, .6, .5, 2, 3, .1));
        assisted.accept(new Motion(.9, 0, 0));
        ordinary.accept(new Motion(.9, 0, 0));
        assertEquals(ordinary.step(new Motion(0, 0, .9), UNLIMITED),
            assisted.step(new Motion(0, 0, .9), UNLIMITED));

        assisted.accept(new Motion(4, 0, 0));
        assertEquals(Math.toRadians(15), heading(assisted.step(new Motion(0, 0, 4), UNLIMITED)), EPSILON);
    }

    @Test void highSpeedTurnRateIsBoundedAndUsesTheShortestHeading() {
        for (double sign : new double[] {-1, 1}) {
            AdaptiveFlightController controller = controller(10);
            controller.accept(new Motion(6, 0, 0));
            Motion first = controller.step(new Motion(0, 0, sign * 6), UNLIMITED);
            assertEquals(Math.toRadians(sign * 10), heading(first), EPSILON);
            assertEquals(6, first.length(), EPSILON);
        }
        AdaptiveFlightController controller = controller(15);
        controller.accept(fromHeading(170, 6, 0));
        Motion first = controller.step(fromHeading(-170, 6, 0), UNLIMITED);
        assertEquals(Math.toRadians(-175), heading(first), EPSILON);
        assertMotion(fromHeading(-170, 6, 0), controller.step(fromHeading(-170, 6, 0), UNLIMITED));
    }

    @Test void maximumTurnRateCanFinishARightAngleInOneTickWithoutBoostingSpeed() {
        AdaptiveFlightController controller = controller(90);
        controller.accept(new Motion(6, .5, 0));
        assertMotion(new Motion(0, .5, 6), controller.step(new Motion(0, .5, 6), UNLIMITED));
    }

    @Test void turningAndVerticalAccelerationStillShareTheOrdinarySpeedBudget() {
        AdaptiveFlightController controller = controller(15);
        controller.accept(new Motion(6, 0, 0));
        Motion steered = fromHeading(15, 6, 0);
        Motion output = controller.step(new Motion(0, 4, 8), UNLIMITED);
        assertEquals(Math.toRadians(15), heading(output), EPSILON);
        assertEquals(.15, difference(steered, output), EPSILON);
        assertTrue(output.y() > 0 && output.y() <= .15);
        assertTrue(output.length() <= 6 + .15 + EPSILON);
    }

    @Test void reducingRequestedSpeedStillBrakesDuringAssistedCorners() {
        AdaptiveFlightController controller = controller(15);
        controller.accept(new Motion(6, 0, 0));
        Motion output = controller.step(new Motion(0, 0, 2), UNLIMITED);
        assertEquals(5.7, output.length(), EPSILON);
        assertEquals(Math.toRadians(15), heading(output), EPSILON);
    }

    @Test void releasesAndReversalsContinueToBrakeInsteadOfMakingFreeUTurns() {
        AdaptiveFlightController controller = controller(15);
        controller.accept(new Motion(6, 0, 0));
        assertMotion(new Motion(5.7, 0, 0), controller.step(Motion.ZERO, UNLIMITED));
        controller.accept(new Motion(6, 0, 0));
        assertMotion(new Motion(5.7, 0, 0), controller.step(new Motion(-6, 0, 0), UNLIMITED));
        assertMotion(new Motion(5.4, 0, 0), controller.step(new Motion(-6, 0, 0), UNLIMITED));
    }

    @Test void zeroTurnRateAndLegacySettingsRetainMomentumBasedSteering() {
        AdaptiveFlightController controller = controller(0);
        controller.accept(new Motion(6, 0, 0));
        Motion output = controller.step(new Motion(0, 0, 6), UNLIMITED);
        assertEquals(.15, difference(new Motion(6, 0, 0), output), EPSILON);
        assertTrue(output.x() > 5.8);
        assertTrue(output.z() < .15);
        assertEquals(0, new Settings(.15, .3, .5, 2, 3, .1).turnRate());
    }

    @Test void correctionPauseAndReducedAdaptiveOutputRemainAuthoritative() {
        AdaptiveFlightController controller = controller(15);
        controller.accept(new Motion(6, 0, 0));
        controller.corrected(1);
        assertEquals(Motion.ZERO, controller.step(new Motion(0, 0, 6), UNLIMITED));
        for (int i = 0; i < 2; i++) {
            controller.tick();
            assertEquals(Motion.ZERO, controller.step(new Motion(0, 0, 6), UNLIMITED));
        }
        controller.tick();
        controller.accept(new Motion(3, 0, 0));
        Motion recovered = controller.step(new Motion(0, 0, 6), UNLIMITED);
        assertEquals(3, recovered.length(), EPSILON);
        assertEquals(Math.toRadians(15), heading(recovered), EPSILON);
    }

    @Test void terrainClippingAndAcceptedSlowMotionImmediatelyReduceSteeringAssistance() {
        AdaptiveFlightController controller = controller(15);
        controller.accept(new Motion(6, 0, 0));
        Motion clipped = controller.step(new Motion(0, 0, 6), .2);
        assertEquals(.2, clipped.length(), EPSILON);
        assertEquals(Math.toRadians(15), heading(clipped), EPSILON);

        AdaptiveFlightController ordinary = controller(0);
        ordinary.accept(clipped);
        Motion target = new Motion(.2, 0, 0);
        assertEquals(ordinary.step(target, UNLIMITED), controller.step(target, UNLIMITED));
    }

    @Test void actualAssistedTurnPathIsSweptBeforeItCanCrossAnObstacle() {
        AdaptiveFlightController controller = controller(15);
        controller.accept(new Motion(6, 0, 0));
        AABB body = new AABB(.2, 65, .2, .8, 66.8, .8);
        AABB obstacle = new AABB(4, 65, 2.4, 4.1, 67, 2.5);
        List<AABB> scans = new ArrayList<>();
        FlightCorridor.Probe probe = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return true; }
            @Override public boolean clear(AABB swept) {
                scans.add(swept);
                return !swept.intersects(obstacle);
            }
        };

        // Six blocks per tick is past the engine maximum here, so the assisted rate has doubled.
        var step = AdaptiveFlightStep.move(controller, new Motion(0, 0, 6),
            6, 0, body, probe, 64, .1);
        assertEquals(FlightCorridor.Reason.OBSTACLE, step.corridor().reason());
        assertTrue(step.terrainLimited());
        assertTrue(step.motion().length() < 6);
        assertEquals(Math.toRadians(30), heading(step.motion()), EPSILON);
        AABB firstScan = scans.getFirst();
        assertEquals(Math.tan(Math.toRadians(30)),
            (firstScan.maxZ - body.maxZ) / (firstScan.maxX - body.maxX), EPSILON);
        assertFalse(body.move(step.motion().x(), step.motion().y(), step.motion().z()).intersects(obstacle));
        assertEquals(step.motion(), controller.output());
        assertEquals(step.corridor().collisionChecks(), scans.size());
        assertTrue(scans.size() <= FlightCorridor.MAX_SEGMENTS + FlightCorridor.REFINEMENT_STEPS);
    }

    @Test void turnRateRisesWithSpeedToDoubleAtTheEngineMaximum() {
        double maximum = 6;
        for (double speed : new double[] {1, 2, 3, 4.5, 6, 9}) {
            AdaptiveFlightController controller = controller(15);
            controller.accept(new Motion(speed, 0, 0));
            Motion output = controller.step(new Motion(0, 0, speed), UNLIMITED, false, maximum);
            double expected = 15 * (1 + Math.min(1, speed / maximum));
            assertEquals(Math.toRadians(expected), heading(output), EPSILON, "at " + speed);
            assertEquals(speed, output.horizontalLength(), EPSILON, "assisted corners keep their speed");
        }
    }

    @Test void adaptiveCorneringBoundsSidewaysDriftAcrossCruiseAndSprintSpeeds() {
        double maximum = AdaptiveFlightStep.maximumSpeed(.3, 64, .35);
        double[] previousDrift = {0};
        for (double fraction : new double[] {.35, .6, 1}) {
            double speed = maximum * fraction;
            AdaptiveFlightController controller = controller(15);
            controller.accept(new Motion(speed, 0, 0));
            double drift = 0;
            int ticks = 0;
            Motion output = controller.output();
            while (heading(output) < Math.toRadians(90) - EPSILON) {
                output = controller.step(new Motion(0, 0, speed), UNLIMITED, false, maximum);
                drift += output.x();
                ticks++;
            }
            assertEquals(speed, output.horizontalLength(), EPSILON);
            assertTrue(ticks <= 5, ticks + " ticks at " + speed);
            assertTrue(drift < 8.5, drift + " blocks of drift at " + speed);
            assertTrue(drift > previousDrift[0], "faster flight still turns wider");
            previousDrift[0] = drift;
        }
        // The previous momentum steering needed 56 ticks and 160 blocks for this sprint turn.
    }

    @Test void legacyStepsAndAnUnlimitedReferenceKeepTheConfiguredRate() {
        AdaptiveFlightController legacy = controller(15);
        AdaptiveFlightController unlimited = controller(15);
        legacy.accept(new Motion(6, 0, 0));
        unlimited.accept(new Motion(6, 0, 0));
        assertEquals(legacy.step(new Motion(0, 0, 6), UNLIMITED),
            unlimited.step(new Motion(0, 0, 6), UNLIMITED, false, UNLIMITED));
        assertEquals(Math.toRadians(15), heading(legacy.output()), EPSILON);
    }

    @Test void effectiveTurnRateNeverExceedsARightAngleAndLowSpeedsStayUnassisted() {
        AdaptiveFlightController controller = controller(90);
        controller.accept(new Motion(6, 0, 0));
        assertMotion(new Motion(0, 0, 6), controller.step(new Motion(0, 0, 6), UNLIMITED, false, 1));

        AdaptiveFlightController assisted = controller(15);
        AdaptiveFlightController ordinary = controller(0);
        assisted.accept(new Motion(.25, 0, 0));
        ordinary.accept(new Motion(.25, 0, 0));
        assertEquals(ordinary.step(new Motion(0, 0, .25), UNLIMITED),
            assisted.step(new Motion(0, 0, .25), UNLIMITED, false, .3));
    }

    @Test void rejectsInvalidTurnReferences() {
        AdaptiveFlightController controller = controller(15);
        for (double reference : new double[] {-1, Double.NaN}) {
            assertThrows(IllegalArgumentException.class,
                () -> controller.step(new Motion(1, 0, 0), UNLIMITED, false, reference));
        }
    }

    @Test void rejectsInvalidTurnRates() {
        for (double turnRate : new double[] {-1, 90.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                () -> new Settings(.15, .3, .5, 2, 3, .1, turnRate));
        }
    }

    private static AdaptiveFlightController controller(double turnRate) {
        return new AdaptiveFlightController(new Settings(.15, .3, .5, 2, 3, .1, turnRate));
    }

    private static Motion fromHeading(double degrees, double horizontal, double vertical) {
        double angle = Math.toRadians(degrees);
        return new Motion(Math.cos(angle) * horizontal, vertical, Math.sin(angle) * horizontal);
    }

    private static double heading(Motion motion) {
        return Math.atan2(motion.z(), motion.x());
    }

    private static double difference(Motion left, Motion right) {
        return Math.hypot(Math.hypot(left.x() - right.x(), left.y() - right.y()), left.z() - right.z());
    }

    private static void assertMotion(Motion expected, Motion actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }
}
