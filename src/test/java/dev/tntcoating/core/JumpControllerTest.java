package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.tntcoating.core.JumpController.Action.*;

class JumpControllerTest {
    private final JumpController controller = new JumpController();

    private JumpController.Action tick(boolean start, boolean rising, boolean ground, boolean stable,
                                       boolean clear, boolean resolved) {
        return controller.tick(start, rising, ground, stable, clear, resolved, true, 3);
    }

    @Test void coatsNormallyUntilARealJump() {
        assertEquals(COAT, tick(false, false, true, true, false, true));
        assertEquals(PAUSE, tick(true, true, false, false, false, true));
        assertEquals(JumpController.State.WAITING_FOR_CLEARANCE, controller.state());
    }

    @Test void waitsForClearanceThenAttemptsExactlyOnce() {
        assertEquals(PAUSE, tick(true, true, false, false, false, true));
        assertEquals(PAUSE, tick(false, true, false, false, false, true));
        assertEquals(ATTEMPT_UNDERFOOT, tick(false, true, false, false, true, true));
        for (int i = 0; i < 20; i++) assertEquals(PAUSE, tick(false, true, false, false, true, false));
    }

    @Test void requiresConfirmationLandingAndConsecutiveStableTicks() {
        tick(true, true, false, false, true, true);
        for (int i = 0; i < 5; i++) assertEquals(PAUSE, tick(false, false, true, true, true, false));
        assertEquals(PAUSE, tick(false, false, false, true, true, true));
        assertEquals(PAUSE, tick(false, false, true, true, true, true));
        assertEquals(PAUSE, tick(false, false, true, false, true, true));
        assertEquals(PAUSE, tick(false, false, true, true, true, true));
        assertEquals(PAUSE, tick(false, false, true, true, true, true));
        assertEquals(RESUMED, tick(false, false, true, true, true, true));
        assertEquals(COAT, tick(false, false, true, true, true, true));
    }

    @Test void anInvalidOrMissedJumpCanResolveWithoutAnAttempt() {
        tick(true, true, false, false, false, true);
        assertEquals(PAUSE, tick(false, false, false, false, false, true));
        assertEquals(PAUSE, tick(false, false, true, true, false, true));
        assertEquals(PAUSE, tick(false, false, true, true, false, true));
        assertEquals(RESUMED, tick(false, false, true, true, false, true));
    }

    @Test void correctionInterruptsJumpAndRestartsFullCooldown() {
        tick(true, true, false, false, false, true);
        controller.corrected(2);
        assertEquals(PAUSE, tick(false, true, false, false, true, true));
        controller.corrected(2);
        for (int i = 0; i < 2; i++) assertEquals(PAUSE, tick(false, false, true, true, true, true));
        assertEquals(PAUSE, tick(false, false, true, true, true, true));
        assertEquals(PAUSE, tick(false, false, true, true, true, true));
        assertEquals(RESUMED, tick(false, false, true, true, true, true));
    }

    @Test void repeatedJumpsEachHaveOneAttempt() {
        for (int j = 0; j < 3; j++) {
            assertEquals(ATTEMPT_UNDERFOOT, tick(true, true, false, false, true, true));
            tick(false, false, true, true, false, true);
            tick(false, false, true, true, false, true);
            assertEquals(RESUMED, tick(false, false, true, true, false, true));
        }
    }

    @Test void canDisableGroundRequirementButStillNeedsStability() {
        tick(true, true, false, false, true, true);
        assertEquals(PAUSE, controller.tick(false, false, false, false, false, true, false, 1));
        assertEquals(RESUMED, controller.tick(false, false, false, true, false, true, false, 1));
    }

    @Test void resetClearsCorrectionState() {
        controller.corrected(10);
        controller.reset();
        assertEquals(COAT, tick(false, false, true, true, false, true));
    }

    @Test void aJumpThatNeverClearsTheTargetHasABoundedWindow() {
        tick(true, true, false, false, false, true);
        for (int i = 0; i < 20; i++) assertEquals(PAUSE, tick(false, true, false, false, false, true));
        assertEquals(JumpController.State.STABILISING, controller.state());
        assertEquals(PAUSE, tick(false, false, true, true, false, true));
        assertEquals(PAUSE, tick(false, false, true, true, false, true));
        assertEquals(RESUMED, tick(false, false, true, true, false, true));
    }

    @Test void vanillaJumpHasOneAttemptOnlyAfterOneBlockOfClearance() {
        double height = 0;
        double velocity = 0.42;
        int attempts = 0;
        for (int i = 0; i < 12; i++) {
            height += velocity;
            var action = tick(i == 0, velocity > 0.05, false, false, height >= 1.02, attempts == 0);
            if (action == ATTEMPT_UNDERFOOT) {
                attempts++;
                assertTrue(height >= 1.02);
            }
            velocity = (velocity - 0.08) * 0.98;
        }
        assertEquals(1, attempts);
        assertEquals(JumpController.State.STABILISING, controller.state());
    }

    @Test void correctionAfterSendingDoesNotResumeBeforeConfirmationResolves() {
        assertEquals(ATTEMPT_UNDERFOOT, tick(true, true, false, false, true, true));
        controller.corrected(2);
        for (int i = 0; i < 10; i++) assertEquals(PAUSE, tick(false, false, true, true, true, false));
        assertEquals(PAUSE, tick(false, false, true, true, true, true));
        assertEquals(PAUSE, tick(false, false, true, true, true, true));
        assertEquals(RESUMED, tick(false, false, true, true, true, true));
    }
}
