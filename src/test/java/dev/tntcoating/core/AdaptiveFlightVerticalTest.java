package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import dev.tntcoating.core.AdaptiveFlightStep.Blend;
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

    @Test void verticalAccelerationIsItsOwnBudgetAlongsideHorizontalAcceleration() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightController controller = controller();
            Motion motion = move(controller, new Motion(2, direction * .1, 0), 2, .1,
                TestControls.of(true, 1, 1, direction, false, null, blend(3))).motion();

            assertEquals(direction * .05, motion.y(), EPSILON, "climbs and dives start at vertical-acceleration");
            assertEquals(.15, motion.horizontalLength(), EPSILON,
                "the controller's whole acceleration goes to horizontal flight, not shared with the climb");
            assertEquals(motion, controller.output());
        }
    }

    @Test void climbAndDescentSettleAtTheVerticalMaximum() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightController controller = controller();
            Motion motion = Motion.ZERO;
            for (int tick = 0; tick < 100; tick++) {
                controller.tick();
                motion = move(controller, new Motion(0, direction * .1, 0), 0, .1,
                    TestControls.of(true, 1, 1, direction, false, null, blend(.5))).motion();
            }

            assertEquals(direction * .5, motion.y(), EPSILON);
            assertEquals(0, motion.horizontalLength(), EPSILON);
        }
    }

    @Test void releasingSprintBrakesVerticalMomentumWithoutSnappingToTheStockCeiling() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightController controller = controller();
            controller.accept(new Motion(0, direction * .8, 0));

            Motion motion = move(controller, Motion.ZERO, 0, .1,
                TestControls.of(false, 1, 1, 0, false, null, blend(3))).motion();

            assertEquals(direction * .7, motion.y(), EPSILON,
                "vertical braking is twice vertical-acceleration, not a snap to Meteor's .1 stock speed");
            assertEquals(motion, controller.output());
        }
    }

    @Test void theOppositeVerticalKeyTurnsAroundAtOnceFromAStandstill() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightController controller = controller();
            controller.accept(new Motion(0, -direction * .4, 0));

            Motion motion = move(controller, new Motion(0, direction * .1, 0), 0, .1,
                TestControls.of(true, 1, 1, direction, false, null, blend(.6))).motion();

            assertEquals(direction * .05, motion.y(), EPSILON,
                "the old direction's momentum is dropped and the new one starts at vertical-acceleration");
        }
    }

    @Test void theOppositeVerticalKeyTurnsABoostedClimbAroundAtTheBoostSpeed() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(0, 3, 0));

        Motion motion = move(controller, new Motion(0, -.1, 0), 0, .1,
            new Controls(true, 1, 1, -1, false, null, AdaptiveFlightStep.SAFE_MANEUVER_SPEED,
                new Blend(3, .05, .5), new AdaptiveFlightStep.Boost(0, 1.5))).motion();

        assertEquals(-1.5, motion.y(), EPSILON, "a 60 b/s climb becomes a 30 b/s boosted descent in one tick");
    }

    @Test void releasingTheVerticalKeyStillBrakesThroughTheSink() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(0, .4, 0));

        Motion motion = move(controller, new Motion(0, -.02, 0), 0, .1,
            TestControls.of(false, 1, 1, 0, false, null, blend(.6))).motion();

        assertEquals(.3, motion.y(), EPSILON, "only a deliberate reversal skips braking, not the passive sink");
    }

    @Test void diagonalFlightRespectsBothPercentagesWithinTheEngineMaximum() {
        double engine = AdaptiveFlightStep.maximumSpeed(.3, 64, .35);
        for (double fraction : new double[] {.25, 1}) {
            AdaptiveFlightController controller = controller();
            Motion motion = Motion.ZERO;
            for (int tick = 0; tick < 150; tick++) {
                controller.tick();
                motion = move(controller, new Motion(2, .1, 0), 2, .1,
                    TestControls.of(true, fraction, fraction, 1, false, null, blend(.4))).motion();
            }

            assertTrue(motion.length() <= engine + EPSILON);
            if (fraction < 1) {
                assertEquals(engine * fraction, motion.horizontalLength(), EPSILON);
                assertEquals(.4 * fraction, motion.y(), EPSILON);
            }
        }
    }

    @Test void horizontalAndVerticalCruisePercentagesAreIndependent() {
        AdaptiveFlightController controller = controller();
        Motion motion = Motion.ZERO;
        for (int tick = 0; tick < 150; tick++) {
            controller.tick();
            motion = move(controller, new Motion(2, .1, 0), 2, .1,
                TestControls.of(false, .25, .5, 1, false, null, blend(.8))).motion();
        }

        assertEquals(AdaptiveFlightStep.maximumSpeed(.3, 64, .35) * .25, motion.horizontalLength(), EPSILON);
        assertEquals(.4, motion.y(), EPSILON);
    }

    @Test void verticalSprintUsesItsOwnPercentageWithoutChangingHorizontalCruise() {
        AdaptiveFlightController controller = controller();
        Motion motion = Motion.ZERO;
        for (int tick = 0; tick < 150; tick++) {
            controller.tick();
            motion = move(controller, new Motion(2, .1, 0), 2, .1,
                TestControls.of(true, .35, 1, 1, false, null, blend(.8))).motion();
        }

        assertEquals(AdaptiveFlightStep.maximumSpeed(.3, 64, .35) * .35, motion.horizontalLength(), EPSILON);
        assertEquals(.8, motion.y(), EPSILON);
    }

    @Test void releasingSprintBrakesSmoothlyToTheVerticalCruisePercentage() {
        AdaptiveFlightController controller = controller();
        for (int tick = 0; tick < 100; tick++) {
            move(controller, new Motion(0, .1, 0), 0, .1,
                TestControls.of(true, 1, 1, 1, false, null, blend(.8)));
        }
        assertEquals(.8, controller.output().y(), EPSILON);

        double previous = .8;
        for (int tick = 0; tick < 20; tick++) {
            Motion motion = move(controller, new Motion(0, .1, 0), 0, .1,
                TestControls.of(false, 1, .35, 1, false, null, blend(.8))).motion();
            assertTrue(motion.y() <= previous + EPSILON);
            assertTrue(previous - motion.y() <= .1 + EPSILON);
            assertTrue(motion.y() >= .8 * .35 - EPSILON);
            previous = motion.y();
        }
        assertEquals(.8 * .35, previous, EPSILON);
    }

    @Test void verticalFlightPreservesTheSweptObstacleGuard() {
        AdaptiveFlightController controller = controller();
        AABB wall = new AABB(8, -1_000, -10, 9, 1_000, 10);
        FlightCorridor.Probe probe = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return true; }
            @Override public boolean clear(AABB swept) { return !swept.intersects(wall); }
        };
        AABB body = BODY;

        for (int tick = 0; tick < 80; tick++) {
            controller.tick();
            var step = AdaptiveFlightStep.move(controller, new Motion(2, .1, 0), 2, .1,
                body, probe, 64, .35, TestControls.of(true, 1, 1, 1, false, null, blend(.8)));
            body = body.move(step.motion().x(), step.motion().y(), step.motion().z());
            assertFalse(body.intersects(wall));
            assertTrue(body.maxX < 8);
            assertEquals(step.motion(), controller.output());
        }
    }

    /** A vertical maximum with the default .05 vertical acceleration and no boost curve. */
    private static Blend blend(double verticalMaximum) {
        return new Blend(verticalMaximum, .05, 0);
    }

    private static AdaptiveFlightController controller() {
        return new AdaptiveFlightController(new Settings(.15, .3, 15));
    }

    private static AdaptiveFlightStep.Result move(AdaptiveFlightController controller, Motion requested,
                                                  double horizontal, double vertical, Controls controls) {
        return AdaptiveFlightStep.move(controller, requested, horizontal, vertical,
            BODY, CLEAR, 64, .35, controls);
    }
}
