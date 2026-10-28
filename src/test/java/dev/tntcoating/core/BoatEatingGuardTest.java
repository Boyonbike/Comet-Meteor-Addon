package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatEatingGuardTest {
    private final BoatEatingGuard guard = new BoatEatingGuard();
    private String tick(long seconds, boolean accessible, int count, double health, int hunger) {
        return guard.evaluate(seconds * 1_000_000_000L, true, accessible, count, health, hunger, 15);
    }
    @Test void inaccessibleFoodDoesNotCountAsAFKProtection() {
        assertNull(tick(0, false, 64, 20, 16));
        assertNull(tick(14, false, 64, 20, 16));
        assertNotNull(tick(15, false, 64, 20, 16));
    }
    @Test void accessibleFoodAndAnEatingAnimationCannotHideRejectedConsumption() {
        assertNull(tick(0, true, 64, 16, 19));
        assertNotNull(tick(15, true, 64, 16, 19));
    }
    @Test void consumptionAndRecoveredVitalsResetTheFailureTimer() {
        tick(0, true, 64, 12, 16);
        assertNull(tick(14, true, 63, 12, 16));
        assertNull(tick(28, true, 63, 14, 16));
        assertNull(tick(42, true, 63, 14, 20));
        assertNotNull(tick(57, true, 63, 14, 20));
    }
    @Test void unneededFoodAndDisabledTimeoutDoNotLogout() {
        tick(0, false, 64, 20, 19);
        assertNull(guard.evaluate(100_000_000_000L, false, false, 64, 20, 20, 15));
        assertNull(tick(200, false, 64, 20, 19));
        assertNull(guard.evaluate(1_000_000_000_000L, true, false, 64, 20, 19, 0));
    }
}
