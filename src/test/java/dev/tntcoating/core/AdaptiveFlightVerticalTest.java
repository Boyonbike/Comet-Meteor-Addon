package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import dev.tntcoating.core.AdaptiveFlightMemory.Limits;
import dev.tntcoating.core.AdaptiveFlightStep.Controls;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFlightVerticalTest {
    private static final double EPSILON = 1e-9;
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);
    private static final FlightCorridor.Probe CLEAR = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return true; }
        @Override public boolean clear(AABB swept) { return true; }
    };

    @Test void learnedVerticalAccelerationStillSharesTheWholeVectorBudget() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightController controller = controller();
            Limits limits = new Limits(3, .75, .75, .05);

            Motion motion = move(controller, new Motion(2, direction * .1, 0), 2, .1,
                TestControls.of(true, 1, direction, false, null, limits)).motion();

            assertTrue(direction * motion.y() > 0);
            assertTrue(Math.abs(motion.y()) <= .05 + EPSILON);
            assertTrue(motion.length() <= .15 + EPSILON,
                "vertical smoothing must not add acceleration on top of the normal vector budget");
            assertEquals(motion, controller.output());
        }
    }

    @Test void ascentAndDescentUseTheirOwnAbsoluteLearnedCeilings() {
        Limits limits = new Limits(3, .4, .6, .05);
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightController controller = controller();
            Motion motion = Motion.ZERO;
            for (int tick = 0; tick < 100; tick++) {
                controller.tick();
                motion = move(controller, new Motion(0, direction * .1, 0), 0, .1,
                    TestControls.of(true, 1, direction, false, null, limits)).motion();
            }

            assertEquals(direction > 0 ? .4 : -.6, motion.y(), EPSILON);
            assertEquals(0, motion.horizontalLength(), EPSILON);
        }
    }

    @Test void releasingSprintBrakesVerticalMomentumWithoutSnappingToTheStockCeiling() {
        Limits limits = new Limits(3, 1.2, 1.2, .05);
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightController controller = controller();
            controller.accept(new Motion(0, direction * .8, 0));

            Motion motion = move(controller, Motion.ZERO, 0, .1,
                TestControls.of(false, 1, 0, false, null, limits)).motion();

            assertTrue(direction * motion.y() >= .5 - EPSILON,
                "releasing Sprint must keep the configured .3 braking budget");
            assertTrue(direction * motion.y() < .8);
            assertEquals(motion, controller.output());
        }
    }

    @Test void changingVerticalDirectionBrakesThroughExistingMomentum() {
        AdaptiveFlightController controller = controller();
        Limits limits = new Limits(3, .4, .6, .05);
        controller.accept(new Motion(0, .4, 0));

        Motion motion = move(controller, new Motion(0, -.1, 0), 0, .1,
            TestControls.of(true, 1, -1, false, null, limits)).motion();

        assertTrue(motion.y() >= .1 - EPSILON);
        assertTrue(motion.y() < .4);
        assertTrue(.4 - motion.y() <= .3 + EPSILON);
    }

    @Test void diagonalFlightAndPercentagesRespectIndependentAbsoluteAxisLimits() {
        Limits limits = new Limits(1.2, .4, .6, .05);
        for (double fraction : new double[] {.25, 1}) {
            AdaptiveFlightController controller = controller();
            Motion motion = Motion.ZERO;
            for (int tick = 0; tick < 150; tick++) {
                controller.tick();
                motion = move(controller, new Motion(2, .1, 0), 2, .1,
                    TestControls.of(true, fraction, 1, false, null, limits)).motion();
            }

            assertEquals(1.2 * fraction, motion.horizontalLength(), EPSILON);
            assertEquals(.4 * fraction, motion.y(), EPSILON);
            assertTrue(motion.length() <= AdaptiveFlightStep.maximumSpeed(.3, 64, .35) + EPSILON);
        }
    }

    @Test void horizontalAndVerticalCruisePercentagesAreIndependent() {
        Limits limits = new Limits(2, .8, 1.2, .05);
        AdaptiveFlightController controller = controller();
        Motion motion = Motion.ZERO;
        for (int tick = 0; tick < 150; tick++) {
            controller.tick();
            motion = move(controller, new Motion(2, .1, 0), 2, .1,
                TestControls.of(false, .25, .5, 1, false, null, limits)).motion();
        }

        assertEquals(.5, motion.horizontalLength(), EPSILON);
        assertEquals(.4, motion.y(), EPSILON);
    }

    @Test void verticalSprintUsesItsOwnPercentageWithoutChangingHorizontalCruise() {
        Limits limits = new Limits(2, .8, 1.2, .05);
        AdaptiveFlightController controller = controller();
        Motion motion = Motion.ZERO;
        for (int tick = 0; tick < 150; tick++) {
            controller.tick();
            motion = move(controller, new Motion(2, .1, 0), 2, .1,
                TestControls.of(true, .35, 1, 1, false, null, limits)).motion();
        }

        assertEquals(.7, motion.horizontalLength(), EPSILON);
        assertEquals(.8, motion.y(), EPSILON);
    }

    @Test void releasingSprintBrakesSmoothlyToTheVerticalCruisePercentage() {
        Limits limits = new Limits(2, .8, .8, .05);
        AdaptiveFlightController controller = controller();
        for (int tick = 0; tick < 100; tick++) {
            move(controller, new Motion(0, .1, 0), 0, .1,
                TestControls.of(true, 1, 1, 1, false, null, limits));
        }
        assertEquals(.8, controller.output().y(), EPSILON);

        double previous = .8;
        for (int tick = 0; tick < 20; tick++) {
            Motion motion = move(controller, new Motion(0, .1, 0), 0, .1,
                TestControls.of(false, 1, .35, 1, false, null, limits)).motion();
            assertTrue(motion.y() <= previous + EPSILON);
            assertTrue(previous - motion.y() <= .1 + EPSILON);
            assertTrue(motion.y() >= .8 * .35 - EPSILON);
            previous = motion.y();
        }
        assertEquals(.8 * .35, previous, EPSILON);
    }

    @Test void verticalLearningLimitsPreserveTheSweptObstacleGuard() {
        AdaptiveFlightController controller = controller();
        Limits limits = new Limits(3, .8, .8, .05);
        AABB wall = new AABB(8, -1_000, -10, 9, 1_000, 10);
        FlightCorridor.Probe probe = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return true; }
            @Override public boolean clear(AABB swept) { return !swept.intersects(wall); }
        };
        AABB body = BODY;

        for (int tick = 0; tick < 80; tick++) {
            controller.tick();
            var step = AdaptiveFlightStep.move(controller, new Motion(2, .1, 0), 2, .1,
                body, probe, 64, .35, TestControls.of(true, 1, 1, false, null, limits));
            body = body.move(step.motion().x(), step.motion().y(), step.motion().z());
            assertFalse(body.intersects(wall));
            assertTrue(body.maxX < 8);
            assertEquals(step.motion(), controller.output());
        }
    }

    private static AdaptiveFlightController controller() {
        return new AdaptiveFlightController(new Settings(.15, .3, .75, 0, 60, .0025, 15));
    }

    private static AdaptiveFlightStep.Result move(AdaptiveFlightController controller, Motion requested,
                                                  double horizontal, double vertical, Controls controls) {
        return AdaptiveFlightStep.move(controller, requested, horizontal, vertical,
            BODY, CLEAR, 64, .35, controls);
    }
}
