package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import dev.tntcoating.core.AdaptiveFlightMemory.Limits;
import dev.tntcoating.core.AdaptiveFlightStep.Blend;
import dev.tntcoating.core.AdaptiveFlightStep.Boost;
import dev.tntcoating.core.AdaptiveFlightStep.Controls;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Speed-scaled braking, unlimited boost and the blended vertical system. */
class AdaptiveFlightBoostTest {
    private static final double EPSILON = 1e-9;
    private static final double UNLIMITED = Double.POSITIVE_INFINITY;
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);
    private static final Limits OPEN = new Limits(64, 3, 3, .05);
    private static final Blend BLEND = new Blend(3, .5, 1);
    private static final double HORIZON = 64 - .35;
    private static final Braking MODEL = new Braking(.3, HORIZON, 15);
    private static final FlightCorridor.Probe CLEAR = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return true; }
        @Override public boolean clear(AABB swept) { return true; }
    };

    // Unified braking through the controller and step

    @Test void controllerBrakingIsGentleAtCruiseAndFirmerAtBoostSpeed() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(15, 0, 0));
        assertEquals(15 - MODEL.capacity(15), controller.step(Motion.ZERO, UNLIMITED).length(), EPSILON);
        assertTrue(MODEL.capacity(15) > 2);

        controller.accept(new Motion(.5, 0, 0));
        assertEquals(.5 - MODEL.capacity(.5), controller.step(Motion.ZERO, UNLIMITED).length(), EPSILON);
        assertEquals(.3, MODEL.capacity(.5), .003, "near cruise speed, braking is the configured comfort rate");
        assertEquals(.3, new Settings(.15, .3, .75, 0, 60, .0025, 15).brakingAt(15), EPSILON,
            "without a horizon, braking stays constant");
    }

    @Test void boostApproachingAWallBrakesSmoothlyAndStopsBeforeIt() {
        AABB wall = new AABB(70, 60, -5, 71, 72, 5);
        FlightCorridor.Probe probe = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return true; }
            @Override public boolean clear(AABB swept) { return !swept.intersects(wall); }
        };
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(15, 0, 0));
        AABB body = BODY;
        double previous = 15, hardest = 0;
        for (int tick = 0; tick < 100; tick++) {
            controller.tick();
            var step = AdaptiveFlightStep.move(controller, new Motion(1, 0, 0), 1, .5, body, probe, 64, .35,
                boost(OPEN, new Boost(15, 0), 0));
            body = body.move(step.motion().x(), step.motion().y(), step.motion().z());
            assertFalse(body.intersects(wall), "tick " + tick);
            hardest = Math.max(hardest, previous - step.motion().length());
            previous = step.motion().length();
        }
        assertTrue(hardest < 2.5, "a wall seen at the lookahead needs only gentle braking from 300 b/s: " + hardest);
        assertTrue(body.maxX > 69, "the stop ends close to the wall: " + body.maxX);
    }

    // Unlimited boost: controller

    @Test void aRampedHorizontalTargetIsReachedAtOnceButOrdinaryAccelerationIsNot() {
        AdaptiveFlightController ramped = controller();
        AdaptiveFlightController ordinary = controller();
        ramped.accept(new Motion(3, 0, 0));
        ordinary.accept(new Motion(3, 0, 0));

        assertEquals(10, ramped.step(new Motion(10, 0, 0), UNLIMITED, true, 6, true).length(), EPSILON);
        assertEquals(3.15, ordinary.step(new Motion(10, 0, 0), UNLIMITED, true, 6, false).length(), EPSILON);
    }

    @Test void boostedRightAngleCornersKeepSpeedAndDriftFewerBlocksThanAtTheEngineMaximum() {
        double maximum = AdaptiveFlightStep.maximumSpeed(.3, 64, .35);
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(15, 0, 0));
        double drift = 0;
        int ticks = 0;
        Motion output = controller.output();
        while (heading(output) < Math.toRadians(90) - EPSILON && ticks < 20) {
            output = controller.step(new Motion(0, 0, 15), UNLIMITED, true, maximum, true);
            drift += output.x();
            ticks++;
            assertEquals(15, output.horizontalLength(), EPSILON, "boosted corners keep their speed");
        }
        assertTrue(ticks <= 2, "a 90-degree boost corner takes " + ticks + " ticks");
        assertTrue(drift <= 10, "sideways drift " + drift);
    }

    @Test void reversingABoostStillBrakesBeforeTurningAround() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(15, 0, 0));
        Motion first = controller.step(new Motion(-15, 0, 0), UNLIMITED, true, 6, true);
        assertEquals(15 - MODEL.capacity(15), first.x(), EPSILON);
        Motion output = first;
        for (int tick = 0; tick < 30 && output.x() > 0; tick++) {
            output = controller.step(new Motion(-15, 0, 0), UNLIMITED, true, 6, true);
        }
        output = controller.step(new Motion(-15, 0, 0), UNLIMITED, true, 6, true);
        assertEquals(-15, output.x(), EPSILON, "once turned around, boost resumes the ramp speed");
    }

    @Test void boostCorrectionsAreCountedWithoutBackoffOrPause() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(15, 0, 0));
        controller.recordCorrection(2);
        assertEquals(2, controller.correctionCount());
        assertEquals(1, controller.speedFactor(), EPSILON);
        assertEquals(0, controller.pauseTicksRemaining());
        assertEquals(new Motion(15, 0, 0), controller.output());
    }

    // Unlimited boost: flight step

    @Test void boostIgnoresLearnedLimitsAndTheCorrectionCeiling() {
        AdaptiveFlightController controller = controller();
        controller.corrected(1);
        controller.tick();
        assertTrue(controller.speedFactor() < 1);
        Limits strict = new Limits(1, .5, .5, .05, 1);

        var step = move(controller, new Motion(1, 0, 0), 1, .5, boost(strict, new Boost(15, 0), 0));

        assertEquals(15, step.motion().horizontalLength(), EPSILON);
        assertFalse(step.terrainLimited());
    }

    @Test void boostIsStillGuardedByWallsAllTheWayIn() {
        AABB wall = new AABB(20, 60, -5, 21, 72, 5);
        FlightCorridor.Probe probe = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return true; }
            @Override public boolean clear(AABB swept) { return !swept.intersects(wall); }
        };
        AdaptiveFlightController controller = controller();
        AABB body = BODY;
        var first = AdaptiveFlightStep.move(controller, new Motion(1, 0, 0), 1, .5, body, probe, 64, .35,
            boost(OPEN, new Boost(15, 0), 0));
        assertEquals(FlightCorridor.Reason.OBSTACLE, first.corridor().reason());
        assertTrue(first.terrainLimited());
        assertTrue(first.motion().length() < 15);
        for (int tick = 0; tick < 80; tick++) {
            controller.tick();
            var step = AdaptiveFlightStep.move(controller, new Motion(1, 0, 0), 1, .5, body, probe, 64, .35,
                boost(OPEN, new Boost(15, 0), 0));
            body = body.move(step.motion().x(), step.motion().y(), step.motion().z());
            assertFalse(body.intersects(wall), "tick " + tick);
        }
        assertTrue(body.maxX > 19, "boost closes in on the wall instead of stopping far away: " + body.maxX);
    }

    @Test void boostStopsAtUnloadedChunks() {
        FlightCorridor.Probe probe = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return x <= 1; }
            @Override public boolean clear(AABB swept) { return true; }
        };
        AdaptiveFlightController controller = controller();
        var step = AdaptiveFlightStep.move(controller, new Motion(1, 0, 0), 1, .5, BODY, probe, 64, .35,
            boost(OPEN, new Boost(15, 0), 0));
        assertEquals(FlightCorridor.Reason.CHUNKS, step.corridor().reason());
        assertTrue(step.motion().length() < 15);
    }

    @Test void boostingRightAfterASlowStartInOpenAirIsNotCloseObjectControl() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(.15, 0, 0)); // the first cruise tick after pressing forward from a hover
        var step = move(controller, new Motion(1, 0, 0), 1, .5, releasing(new Boost(3, 0)));
        assertFalse(step.safeManeuvering());
        assertEquals(3, step.motion().horizontalLength(), EPSILON);
    }

    @Test void boostBlockedByAnObstacleCrawlsThenResumesOnceClearWhileStillHeld() {
        AABB block = new AABB(1.2, 60, -5, 2, 72, 5);
        boolean[] present = {true};
        FlightCorridor.Probe probe = new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return true; }
            @Override public boolean clear(AABB swept) { return !present[0] || !swept.intersects(block); }
        };
        AdaptiveFlightController controller = controller();
        var blocked = AdaptiveFlightStep.move(controller, new Motion(1, 0, 0), 1, .5, BODY, probe, 64, .35,
            releasing(new Boost(3, 0)));
        assertTrue(blocked.safeManeuvering(), "a blocked boost falls back to the close-object crawl");
        assertEquals(FlightCorridor.Reason.OBSTACLE, blocked.corridor().reason());
        assertTrue(blocked.motion().length() <= .6 + EPSILON);

        present[0] = false;
        controller.tick();
        var clear = AdaptiveFlightStep.move(controller, new Motion(1, 0, 0), 1, .5, BODY, probe, 64, .35,
            releasing(new Boost(3, 0)));
        assertFalse(clear.safeManeuvering());
        assertEquals(3, clear.motion().horizontalLength(), EPSILON, "boost resumes without re-pressing Sprint");
    }

    @Test void boostNeedsMeteorsHorizontalAxisEnabled() {
        AdaptiveFlightController controller = controller();
        var step = move(controller, new Motion(1, 0, 0), 0, .5, boost(OPEN, new Boost(15, 0), 0));
        assertEquals(0, step.motion().horizontalLength(), EPSILON);
    }

    @Test void releasingBoostBrakesDownInsteadOfSnappingToTheAdaptiveMaximum() {
        AdaptiveFlightController blended = controller();
        blended.accept(new Motion(15, 0, 0));
        var step = move(blended, new Motion(1, 0, 0), 1, .5, cruise(OPEN, BLEND, 0));
        assertEquals(15 - MODEL.capacity(15), step.motion().horizontalLength(), EPSILON);

        AdaptiveFlightController legacy = controller();
        legacy.accept(new Motion(15, 0, 0));
        var snapped = move(legacy, new Motion(1, 0, 0), 1, .5, cruise(OPEN, null, 0));
        assertEquals(AdaptiveFlightStep.maximumSpeed(.3, 64, .35), snapped.motion().horizontalLength(), 1e-6);
    }

    // Blended vertical

    @Test void cruiseClimbReachesItsPercentageOfTheVerticalMaximumWithinThreeTicks() {
        AdaptiveFlightController controller = controller();
        Motion motion = Motion.ZERO;
        for (int tick = 0; tick < 3; tick++) {
            controller.tick();
            motion = move(controller, Motion.ZERO, 0, .5, cruise(OPEN, BLEND, 1)).motion();
        }
        assertEquals(3 * .35, motion.y(), EPSILON);
    }

    @Test void setbackReducedVerticalAccelerationSlowsTheVerticalRamp() {
        AdaptiveFlightController full = controller();
        AdaptiveFlightController reduced = controller();
        double fast = move(full, Motion.ZERO, 0, .5, cruise(OPEN, BLEND, 1)).motion().y();
        double slow = move(reduced, Motion.ZERO, 0, .5, cruise(OPEN, new Blend(3, .5, .5), 1)).motion().y();
        assertEquals(.5, fast, EPSILON);
        assertEquals(.25, slow, EPSILON);
    }

    @Test void learnedVerticalLimitsStillLowerCruiseClimb() {
        AdaptiveFlightController controller = controller();
        Limits corrected = new Limits(64, .6, 3, .05);
        Motion motion = Motion.ZERO;
        for (int tick = 0; tick < 10; tick++) {
            controller.tick();
            motion = move(controller, Motion.ZERO, 0, .5, cruise(corrected, BLEND, 1)).motion();
        }
        assertEquals(.6 * .35, motion.y(), EPSILON);
    }

    @Test void verticalBoostTakesItsRampSpeedAtOnceIgnoringLearnedLimits() {
        AdaptiveFlightController controller = controller();
        Limits strict = new Limits(64, .5, .5, .05);
        var step = move(controller, Motion.ZERO, 0, .5, boost(strict, new Boost(0, 3), 1));
        assertEquals(3, step.motion().y(), EPSILON);
    }

    @Test void releasingVerticalBoostBrakesDownToALowerLearnedCeiling() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(0, 3, 0));
        Limits corrected = new Limits(64, 1, 1, .05);
        var step = move(controller, Motion.ZERO, 0, .5, cruise(corrected, BLEND, 1));
        assertEquals(3 - .5, step.motion().y(), EPSILON, "slowing mirrors the vertical ramp: .5 per tick at 3");
    }

    @Test void descentsSlowAsTheFloorApproachesAndTouchDownSoftly() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(0, -3, 0));
        double floor = 20, previous = 3;
        Motion motion = Motion.ZERO;
        for (int tick = 0; tick < 200 && floor > 0; tick++) {
            controller.tick();
            motion = move(controller, Motion.ZERO, 0, .5,
                boost(OPEN, new Blend(3, .5, 1, floor), new Boost(0, 3), -1)).motion();
            assertTrue(-motion.y() <= floor + AdaptiveFlightStep.LANDING_SPEED + EPSILON, "tick " + tick);
            assertTrue(previous + motion.y() < 3, "braking is gradual, not a sudden stop");
            previous = -motion.y();
            floor = Math.max(0, floor + motion.y());
        }
        assertEquals(0, floor, EPSILON);
        assertTrue(-motion.y() <= AdaptiveFlightStep.LANDING_SPEED + EPSILON, "touchdown at " + -motion.y());
    }

    // Learning

    @Test void boostCapSharesTheFullThreeDimensionalSpeedIncludingClimbsAndDives() {
        for (int direction : new int[] {-1, 1}) {
            AdaptiveFlightController controller = controller();
            for (int tick = 0; tick < 100; tick++) {
                controller.tick();
                Motion actual = move(controller, new Motion(.6, direction * .1, .8), 1, .5,
                    boost(OPEN, new Boost(14, 3, 13.3), direction)).motion();
                assertEquals(13.3, actual.length(), EPSILON);
                assertTrue(actual.horizontalLength() < 13.3,
                    "vertical movement must reduce the horizontal budget");
                assertEquals(direction, Math.signum(actual.y()), EPSILON);
                assertEquals(actual, controller.output());
            }
        }
    }

    @Test void aBoostCapAppliesBelowTheRampStartAndToMomentumOnAReleasedAxis() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(3, -2, 4));
        Motion actual = move(controller, new Motion(.6, 0, .8), 1, .5,
            boost(OPEN, new Boost(3, 0, 2), 0)).motion();

        assertEquals(2, actual.length(), EPSILON);
        assertTrue(actual.y() < 0, "released vertical momentum still spends the boost budget");
        assertTrue(actual.horizontalLength() < 2);
    }

    @Test void aLoweredBoostCapImmediatelyConstrainsExistingMomentumAndTurns() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(14, 3, 0));
        for (int tick = 0; tick < 20; tick++) {
            controller.tick();
            Motion actual = move(controller, new Motion(0, .1, 1), 1, .5,
                boost(OPEN, new Boost(14, 3, 12), 1)).motion();
            assertEquals(12, actual.length(), EPSILON);
        }
    }

    @Test void recoverySpeedDoesNotReplaceTheBoundaryOrEscapeItWhenTheHoldEnds() {
        BoostSpeedLimit limit = new BoostSpeedLimit();
        limit.observe(new Motion(14, 0, 0), true);
        assertTrue(limit.corrected());
        for (double recovery : new double[] {4, 2.4, 3.7}) {
            limit.tick();
            limit.observe(new Motion(recovery, 0, 0), true);
            assertFalse(limit.corrected());
        }
        assertEquals(13.3, limit.ceiling(), EPSILON);

        AdaptiveFlightController controller = controller();
        BoostRamp ramp = new BoostRamp(new BoostRamp.Settings(2.999, 14.999, .3, 15, false));
        ramp.engage(0);
        for (int tick = 0; tick < 600; tick++) {
            limit.tick();
            controller.tick();
            ramp.advance();
            Motion actual = move(controller, new Motion(1, 0, 0), 1, .5,
                boost(OPEN, new Boost(ramp.speed(), 0, limit.ceiling()), 0)).motion();
            assertTrue(actual.length() <= limit.ceiling() + EPSILON);
            assertTrue(actual.length() < 14, "the end of the hold cannot resume the rejected 280 b/s");
            limit.observe(actual, true);
        }
        assertTrue(limit.ceiling() > 13.3 && limit.ceiling() <= 13.4 + EPSILON,
            "clean capped movement must probe gradually instead of removing the boundary");
    }

    @Test void invalidBoostTotalCeilingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Boost(3, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new Boost(3, 1, Double.NaN));
        assertEquals(UNLIMITED, new Boost(3, 1).totalCeiling());
    }

    @Test void neverCorrectedVerticalLimitsStartAtTheConfiguredMaximum() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.configure(3, .05);
        Limits remembered = new Limits(64, .9, .8, .05);

        learning.restore(remembered, Limits.UNKNOWN);
        assertEquals(3, learning.limits().ascent(), EPSILON);
        assertEquals(3, learning.limits().descent(), EPSILON);
        assertEquals(remembered, learning.remembered(), "an unproven start is not saved as evidence");

        learning.restore(remembered, new Limits(64, .9, 64, .05));
        assertEquals(.9, learning.limits().ascent(), EPSILON, "a corrected direction keeps its learned limit");
        assertEquals(3, learning.limits().descent(), EPSILON);
    }

    @Test void discardingBoostEvidenceTeachesNothing() {
        AdaptiveFlightLearning learning = new AdaptiveFlightLearning();
        learning.observe(new Motion(15, 0, 0), 0, true, true, 1, 1);
        Limits before = learning.limits();
        learning.resetHistory();
        assertFalse(learning.corrected(.75));
        assertEquals(before, learning.limits());
        assertEquals(Limits.UNKNOWN, learning.ceilings());
    }

    private static AdaptiveFlightController controller() {
        return new AdaptiveFlightController(new Settings(.15, .3, .75, 0, 60, .0025, 15, HORIZON, 15));
    }

    private static Controls boost(Limits limits, Boost boost, int verticalInput) {
        return boost(limits, BLEND, boost, verticalInput);
    }

    private static Controls boost(Limits limits, Blend blend, Boost boost, int verticalInput) {
        return new Controls(true, 1, 1, verticalInput, false, null, limits,
            AdaptiveFlightStep.SAFE_MANEUVER_SPEED, blend, boost);
    }

    /** Boost as the module sends it from a slow start: safe maneuvering on, at the default close-object speed. */
    private static Controls releasing(Boost boost) {
        return new Controls(true, 1, 1, 0, true, null, OPEN, .6, BLEND, boost);
    }

    private static Controls cruise(Limits limits, Blend blend, int verticalInput) {
        return new Controls(false, .35, .35, verticalInput, false, null, limits,
            AdaptiveFlightStep.SAFE_MANEUVER_SPEED, blend, null);
    }

    private static AdaptiveFlightStep.Result move(AdaptiveFlightController controller, Motion requested,
                                                  double horizontal, double vertical, Controls controls) {
        return AdaptiveFlightStep.move(controller, requested, horizontal, vertical, BODY, CLEAR, 64, .35, controls);
    }

    private static double heading(Motion motion) {
        return Math.atan2(motion.z(), motion.x());
    }
}
