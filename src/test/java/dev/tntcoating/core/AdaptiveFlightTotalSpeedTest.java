package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import dev.tntcoating.core.AdaptiveFlightMemory.Limits;
import dev.tntcoating.core.AdaptiveFlightStep.Controls;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFlightTotalSpeedTest {
    private static final double EPSILON = 1e-9;
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);
    private static final FlightCorridor.Probe CLEAR = new FlightCorridor.Probe() {
        @Override public boolean loaded(int x, int z) { return true; }
        @Override public boolean clear(AABB swept) { return true; }
    };
    private static final Limits LIMITS = new Limits(6, 4, 5, .05, 3);

    @Test void pureAndMixedFlightShareTheLearnedTotalDisplacementBudget() {
        for (int vertical : new int[] {-1, 0, 1}) {
            for (boolean horizontal : new boolean[] {false, true}) {
                if (!horizontal && vertical == 0) continue;
                AdaptiveFlightController controller = controller();
                Motion requested = new Motion(horizontal ? 2 : 0, vertical * .1, 0);
                Motion actual = settle(controller, requested, vertical, 1, 1);

                assertEquals(3, actual.length(), EPSILON);
                assertTrue(actual.horizontalLength() <= LIMITS.horizontal() + EPSILON);
                assertTrue(actual.y() <= LIMITS.ascent() + EPSILON);
                assertTrue(-actual.y() <= LIMITS.descent() + EPSILON);
                assertEquals(actual, controller.output());
            }
        }
    }

    @Test void equalPercentagesScaleTheAttainableSharedMaximumForPureAndMixedFlight() {
        for (double fraction : new double[] {.25, .5}) {
            for (Motion requested : new Motion[] {new Motion(2, 0, 0), new Motion(0, .1, 0),
                new Motion(2, .1, 0)}) {
                AdaptiveFlightController controller = controller();
                Motion actual = settle(controller, requested, requested.y() == 0 ? 0 : 1, fraction, fraction);

                assertEquals(3 * fraction, actual.length(), EPSILON);
            }
        }
    }

    @Test void separatePercentagesRespectBothComponentRequestsAndTheSharedBudget() {
        AdaptiveFlightController controller = controller();
        Motion actual = settle(controller, new Motion(2, .1, 0), 1, .25, .5);

        assertEquals(1.5, actual.length(), EPSILON);
        assertTrue(actual.horizontalLength() <= 3 * .25 + EPSILON);
        assertTrue(actual.y() <= 3 * .5 + EPSILON);
        assertEquals(2, actual.y() / actual.horizontalLength(), EPSILON);
    }

    @Test void correctionRecoveryFactorAppliesToTheSharedBudgetOnlyOnce() {
        for (double fraction : new double[] {.5, 1}) {
            AdaptiveFlightController controller = controller();
            controller.corrected(1);
            Motion actual = settle(controller, new Motion(2, .1, 0), 1, fraction, fraction);

            assertEquals(.75, controller.speedFactor(), EPSILON);
            assertEquals(3 * .75 * fraction, actual.length(), EPSILON);
        }
    }

    @Test void loweringThePercentageBrakesExistingMomentumWithinTheSharedHardCeiling() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(3, 0, 0));
        Motion actual = move(controller, new Motion(2, 0, 0), 0, .25, .25).motion();

        assertEquals(2.7, actual.horizontalLength(), EPSILON);
        assertTrue(actual.length() > 3 * .25);
        assertTrue(actual.length() <= LIMITS.total());
    }

    @Test void loweringTheSharedCeilingImmediatelyConstrainsExistingMixedMomentum() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(4, 4, 0));
        Motion actual = move(controller, new Motion(2, .1, 0), 1, 1, 1).motion();

        assertEquals(3, actual.length(), EPSILON);
        assertEquals(actual, controller.output());
    }

    private static Motion settle(AdaptiveFlightController controller, Motion requested, int vertical,
                                 double horizontalFraction, double verticalFraction) {
        Motion actual = Motion.ZERO;
        for (int tick = 0; tick < 250; tick++) {
            controller.tick();
            actual = move(controller, requested, vertical, horizontalFraction, verticalFraction).motion();
            assertTrue(actual.length() <= LIMITS.total() * controller.speedFactor() + EPSILON);
        }
        return actual;
    }

    private static AdaptiveFlightStep.Result move(AdaptiveFlightController controller, Motion requested,
                                                   int vertical, double horizontalFraction, double verticalFraction) {
        return AdaptiveFlightStep.move(controller, requested, requested.horizontalLength() > 0 ? 2 : 0, .1,
            BODY, CLEAR, 64, .35,
            TestControls.of(true, horizontalFraction, verticalFraction, vertical, false, null, LIMITS));
    }

    private static AdaptiveFlightController controller() {
        return new AdaptiveFlightController(new Settings(.15, .3, .75, 0, 60, 0, 15));
    }
}
