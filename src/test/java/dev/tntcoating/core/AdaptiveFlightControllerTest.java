package dev.tntcoating.core;

import dev.tntcoating.core.AdaptiveFlightController.Motion;
import dev.tntcoating.core.AdaptiveFlightController.Settings;
import dev.tntcoating.core.AdaptiveFlightController.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFlightControllerTest {
    private static final double EPSILON = 1e-10;
    private static final double UNLIMITED = Double.POSITIVE_INFINITY;

    private static AdaptiveFlightController controller() {
        return new AdaptiveFlightController(new Settings(0.25, 0.5, 0));
    }

    @Test void limitsTheFullVectorWhenAcceleratingAndTurning() {
        AdaptiveFlightController controller = controller();
        Motion diagonal = controller.step(new Motion(3, 4, 0), UNLIMITED);
        assertMotion(new Motion(0.15, 0.2, 0), diagonal);
        assertEquals(0.25, diagonal.length(), EPSILON);

        controller.accept(new Motion(2, 0, 0));
        Motion turned = controller.step(new Motion(0, 0, 2), UNLIMITED);
        assertEquals(0.25, difference(turned, new Motion(2, 0, 0)), EPSILON);
        assertTrue(turned.x() > 1.8);
        assertTrue(turned.z() > 0 && turned.z() < 0.2);
    }

    @Test void reversingBrakesBeforeAcceleratingInTheOtherDirection() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(1, 0, 0));
        Motion reverse = new Motion(-2, 0, 0);
        assertMotion(new Motion(0.5, 0, 0), controller.step(reverse, UNLIMITED));
        assertMotion(Motion.ZERO, controller.step(reverse, UNLIMITED));
        assertMotion(new Motion(-0.25, 0, 0), controller.step(reverse, UNLIMITED));
    }

    @Test void releasingInputAndLoweringTheRequestedCeilingConvergeAtBrakingRate() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(0, 1.2, 1.6));
        Motion reduced = controller.step(new Motion(0, 0.3, 0.4), UNLIMITED);
        assertEquals(1.5, reduced.length(), EPSILON);
        for (int i = 0; i < 8; i++) controller.step(new Motion(0, 0.3, 0.4), UNLIMITED);
        assertMotion(new Motion(0, 0.3, 0.4), controller.output());

        assertMotion(Motion.ZERO, controller.step(Motion.ZERO, UNLIMITED));
        assertMotion(Motion.ZERO, controller.step(Motion.ZERO, UNLIMITED));
    }

    @Test void verticalChangesShareTheSameAccelerationBudget() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(1, 0, 0));
        Motion raised = controller.step(new Motion(1, 3, 0), UNLIMITED);
        assertMotion(new Motion(1, 0.25, 0), raised);
        assertMotion(new Motion(1, 0, 0), controller.step(new Motion(1, 0, 0), UNLIMITED));
    }

    @Test void hardTerrainLimitOverridesMomentumImmediatelyAndClearsStoredSpeed() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(3, 4, 0));
        Motion clipped = controller.step(new Motion(3, 4, 0), 0.2);
        assertMotion(new Motion(0.12, 0.16, 0), clipped);
        assertEquals(Status.TERRAIN_LIMITED, controller.status());
        assertEquals(0.45, controller.step(new Motion(3, 4, 0), UNLIMITED).length(), EPSILON);
        assertMotion(Motion.ZERO, controller.step(new Motion(3, 4, 0), 0));
        assertMotion(Motion.ZERO, controller.output());
    }

    @Test void externalGuardCanReplaceTheMomentumUsedOnTheNextStep() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(2, 0, 0));
        controller.accept(new Motion(0.1, 0, 0));
        assertEquals(Status.TERRAIN_LIMITED, controller.status());
        assertMotion(new Motion(0.35, 0, 0), controller.step(new Motion(3, 0, 0), UNLIMITED));
    }

    @Test void changingTuningKeepsMomentumAndResetClearsAllFlightState() {
        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(2, 0, 0));
        Settings updated = new Settings(0.1, 0.4, 0);
        controller.configure(updated);
        assertMotion(new Motion(2, 0, 0), controller.output());
        controller.accept(new Motion(1, 0, 0));
        assertEquals(Status.TERRAIN_LIMITED, controller.status());
        controller.reset();
        assertSame(updated, controller.settings());
        assertEquals(Status.READY, controller.status());
        assertMotion(Motion.ZERO, controller.output());
        assertMotion(new Motion(0.1, 0, 0), controller.step(new Motion(1, 0, 0), UNLIMITED));
    }

    @Test void rejectsInvalidNumericInputWithoutCorruptingState() {
        assertThrows(IllegalArgumentException.class, () -> new Motion(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Motion(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new Motion(Double.MAX_VALUE, Double.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Settings(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Settings(1, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Settings(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Settings(1, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new Settings(1, 1, 91));
        assertThrows(IllegalArgumentException.class, () -> new Settings(1, 1, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Settings(1, 1, 0, 10, Double.NaN));

        AdaptiveFlightController controller = controller();
        controller.accept(new Motion(1, 0, 0));
        for (double distance : new double[] { Double.NaN, -1, Double.NEGATIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class, () -> controller.step(Motion.ZERO, distance));
        }
        assertThrows(NullPointerException.class, () -> controller.configure(null));
        assertThrows(NullPointerException.class, () -> controller.step(null, 1));
        assertThrows(NullPointerException.class, () -> controller.accept(null));
        assertMotion(new Motion(1, 0, 0), controller.output());
    }

    @Test void largeFiniteReversalDoesNotOverflowTheDifferenceVector() {
        AdaptiveFlightController controller = new AdaptiveFlightController(
            new Settings(1e307, 1e307, 0));
        controller.accept(new Motion(1e308, 0, 0));
        Motion output = controller.step(new Motion(-1e308, 0, 0), UNLIMITED);
        assertTrue(Double.isFinite(output.x()));
        assertEquals(9e307, output.x(), 1e293);
    }

    private static void assertMotion(Motion expected, Motion actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }

    private static double difference(Motion a, Motion b) {
        return Math.hypot(Math.hypot(a.x() - b.x(), a.y() - b.y()), a.z() - b.z());
    }
}
