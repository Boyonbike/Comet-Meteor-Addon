package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import dev.tntcoating.core.AdaptiveFlightController.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFlightTransientRecoveryTest {
    private static final double EPSILON = 1e-9;

    @Test void oneLagEpisodeCountsEveryCorrectionWithoutCompoundingItsBackoff() {
        AdaptiveFlightController controller = controller(.0025);
        controller.corrected(12, true);

        assertEquals(.75, controller.speedFactor(), EPSILON);
        assertEquals(12, controller.correctionCount());
        for (int tick = 0; tick < 30; tick++) controller.tick();
        assertTrue(controller.speedFactor() > .75);
        double recoveringFactor = controller.speedFactor();

        controller.corrected(4, true);
        assertEquals(recoveringFactor, controller.speedFactor(), EPSILON);
        assertEquals(16, controller.correctionCount());
        assertEquals(0, controller.ticksSinceCorrection());
        assertEquals(5, controller.pauseTicksRemaining());
        assertEquals(Motion.ZERO, controller.output());
    }

    @Test void defaultLagRecoveryReturnsToFullSpeedWithinFortySixTicks() {
        AdaptiveFlightController controller = controller(.0025);
        controller.corrected(20, true);
        for (int tick = 0; tick < 20; tick++) controller.tick();
        assertEquals(.75, controller.speedFactor(), EPSILON);

        for (int tick = 20; tick < 46; tick++) controller.tick();
        assertEquals(1, controller.speedFactor(), EPSILON);
        assertEquals(Status.READY, controller.status());

        controller.corrected(20, true);
        assertEquals(.75, controller.speedFactor(), EPSILON,
            "a later lag episode must apply its own single backoff after full recovery");
        assertEquals(40, controller.correctionCount());
    }

    @Test void eachTransientArrivalRestartsTheConfiguredPause() {
        AdaptiveFlightController controller = controller(.0025);
        controller.corrected(1, true);
        for (int tick = 0; tick < 3; tick++) controller.tick();
        controller.corrected(1, true);

        for (int tick = 0; tick < 5; tick++) {
            controller.tick();
            assertEquals(Status.PAUSED, controller.status());
            assertEquals(Motion.ZERO, controller.step(new Motion(2, 1, 0), Double.POSITIVE_INFINITY));
        }
        controller.tick();
        assertEquals(0, controller.pauseTicksRemaining());
        assertEquals(Status.RECOVERING, controller.status());
        assertEquals(.75, controller.speedFactor(), EPSILON);
    }

    @Test void aRegularCorrectionRestoresCompoundingAndTheConfiguredRecoveryPolicy() {
        AdaptiveFlightController controller = controller(.0025);
        controller.corrected(8, true);
        controller.corrected(2);
        double regularFactor = Math.pow(.75, 3);

        assertEquals(regularFactor, controller.speedFactor(), EPSILON);
        assertEquals(10, controller.correctionCount());
        for (int tick = 0; tick < 60; tick++) controller.tick();
        assertEquals(regularFactor, controller.speedFactor(), EPSILON);
        controller.tick();
        assertEquals(regularFactor + .0025, controller.speedFactor(), EPSILON);
    }

    @Test void zeroConfiguredRecoveryStillDisablesTransientRecovery() {
        AdaptiveFlightController controller = controller(0);
        controller.corrected(20, true);
        for (int tick = 0; tick < 1_000; tick++) controller.tick();

        assertEquals(.75, controller.speedFactor(), EPSILON);
        assertEquals(Status.RECOVERING, controller.status());
        controller.corrected(20, true);
        assertEquals(.75, controller.speedFactor(), EPSILON);
    }

    @Test void resetDiscardsAnActiveTransientRecoveryEpisode() {
        AdaptiveFlightController controller = controller(.0025);
        controller.corrected(8, true);
        controller.reset();

        assertEquals(1, controller.speedFactor(), EPSILON);
        assertEquals(0, controller.correctionCount());
        assertEquals(0, controller.pauseTicksRemaining());
        assertEquals(Status.READY, controller.status());
        controller.corrected(8, true);
        assertEquals(.75, controller.speedFactor(), EPSILON);
        assertEquals(8, controller.correctionCount());
    }

    @Test void transientRecoveryRespectsShorterDelaysAndClampsTheIncreasedRate() {
        AdaptiveFlightController controller = new AdaptiveFlightController(new Settings(.15, .3, .75, 0, 2, .4));
        controller.corrected(8, true);
        controller.tick();
        controller.tick();
        assertEquals(.75, controller.speedFactor(), EPSILON);
        controller.tick();
        assertEquals(1, controller.speedFactor(), EPSILON);
    }

    @Test void transientCorrectionCountsRemainSaturatingAndRejectInvalidCounts() {
        AdaptiveFlightController controller = controller(.0025);
        controller.corrected(Integer.MAX_VALUE, true);
        controller.corrected(4, true);
        controller.corrected(0, false);

        assertEquals(Integer.MAX_VALUE, controller.correctionCount());
        assertEquals(.75, controller.speedFactor(), EPSILON);
        assertThrows(IllegalArgumentException.class, () -> controller.corrected(-1, true));
    }

    private static AdaptiveFlightController controller(double recoveryRate) {
        return new AdaptiveFlightController(new Settings(.15, .3, .75, 5, 60, recoveryRate));
    }
}
