package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.tntcoating.core.ElytraSwapRecovery.Action.*;

class ElytraSwapRecoveryTest {
    @Test void temporaryBlockageCanClearWithoutDisconnecting() {
        var recovery = new ElytraSwapRecovery();
        for (int i = 0; i < 19; i++) assertEquals(WAIT, recovery.tick(10, 10, true, false, true, false));
        assertEquals(REPLACE, recovery.tick(10, 10, true, true, true, false));
    }
    @Test void persistentBlockageAndDangerouslyLowWingsStillDisconnect() {
        var recovery = new ElytraSwapRecovery();
        for (int i = 0; i < 20; i++) assertEquals(WAIT, recovery.tick(10, 10, true, false, true, false));
        assertEquals(LOGOUT, recovery.tick(10, 10, true, false, true, false));
        recovery.reset();
        assertEquals(LOGOUT, recovery.tick(2, 10, true, false, true, false));
        assertEquals(LOGOUT, recovery.tick(-1, 10, true, false, true, false));
    }
    @Test void localPredictionDoesNotImmediatelyConfirmAndRollbackWaitsBeforeRetry() {
        var recovery = new ElytraSwapRecovery();
        recovery.requested();
        for (int i = 0; i < 4; i++) assertEquals(WAIT, recovery.tick(350, 10, true, true, true, false));
        for (int i = 0; i < 5; i++) assertEquals(WAIT, recovery.tick(10, 10, true, true, true, false));
        assertEquals(REPLACE, recovery.tick(10, 10, true, true, true, false));
    }
    @Test void stableWingsConfirmOnlyOnceAfterTenObservations() {
        var recovery = new ElytraSwapRecovery();
        recovery.requested();
        for (int i = 0; i < 9; i++) assertEquals(WAIT, recovery.tick(350, 10, true, true, true, false));
        assertEquals(CONFIRMED, recovery.tick(349, 10, true, true, true, false));
        assertEquals(CONTINUE, recovery.tick(349, 10, true, true, true, false));
    }
    @Test void rejectedSwapsAreBoundedAndNeverRetriedEveryTick() {
        var recovery = new ElytraSwapRecovery();
        for (int attempt = 0; attempt < 3; attempt++) {
            recovery.requested();
            for (int i = 0; i < 9; i++) assertEquals(WAIT, recovery.tick(10, 10, true, true, true, false));
            assertEquals(attempt == 2 ? LOGOUT : REPLACE, recovery.tick(10, 10, true, true, true, false));
        }
    }
    @Test void groundCanWaitWithoutWingsButAirborneCannotWaitForMissingWings() {
        var recovery = new ElytraSwapRecovery();
        assertEquals(WAIT, recovery.tick(-1, 10, true, false, true, true));
        recovery.requested();
        assertEquals(WAIT, recovery.tick(-1, 10, true, true, true, true));
        assertEquals(LOGOUT, recovery.tick(-1, 10, true, true, true, false));
    }
    @Test void walkingAndResetDiscardPendingRecovery() {
        var recovery = new ElytraSwapRecovery();
        recovery.requested();
        assertEquals(CONTINUE, recovery.tick(-1, 10, true, false, false, true));
        assertFalse(recovery.pending());
        recovery.requested();
        recovery.reset();
        assertFalse(recovery.pending());
        assertEquals(REPLACE, recovery.tick(10, 10, true, true, true, false));
        assertEquals(LOGOUT, recovery.tick(10, 10, false, true, true, false));
    }
    @Test void alternatingPredictionsCannotKeepRecoveryAliveForever() {
        var recovery = new ElytraSwapRecovery();
        recovery.requested();
        for (int i = 0; i < 60; i++) assertEquals(WAIT, recovery.tick(350, 10, true, i % 2 == 0, true, false));
        assertEquals(LOGOUT, recovery.tick(350, 10, true, true, true, false));
    }
    @Test void lateRollbacksAfterConfirmationStillExhaustTheRetryBudget() {
        var recovery = new ElytraSwapRecovery();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(REPLACE, recovery.tick(10, 10, true, true, true, false));
            recovery.requested();
            for (int tick = 0; tick < 9; tick++) assertEquals(WAIT, recovery.tick(350, 10, true, true, true, false));
            assertEquals(CONFIRMED, recovery.tick(350, 10, true, true, true, false));
        }
        assertEquals(LOGOUT, recovery.tick(10, 10, true, true, true, false));
    }
    @Test void sustainedHealthyFlightAllowsAFutureIndependentReplacement() {
        var recovery = new ElytraSwapRecovery();
        recovery.requested();
        for (int i = 0; i < 10; i++) recovery.tick(350, 10, true, true, true, false);
        for (int i = 0; i < 100; i++) assertEquals(CONTINUE, recovery.tick(350, 10, true, true, true, false));
        assertEquals(REPLACE, recovery.tick(10, 10, true, true, true, false));
    }
}
