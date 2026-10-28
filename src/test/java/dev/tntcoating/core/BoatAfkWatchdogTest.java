package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatAfkWatchdogTest {
    private final BoatAfkWatchdog guard = new BoatAfkWatchdog();
    private String tick(long seconds, boolean world, boolean recovering) {
        return guard.evaluate(seconds * 1_000_000_000L, world, recovering, world, 30, 300);
    }
    @Test void unexpectedWorldCannotWaitForever() {
        assertNull(tick(0, false, false));
        assertNull(tick(29, false, false));
        assertNotNull(tick(30, false, false));
    }
    @Test void recoveryStagesAndReconnectAttemptsDoNotResetTheDeadline() {
        assertNull(tick(0, false, false));
        assertNull(tick(20, false, true));
        assertNull(tick(100, true, true));
        assertNull(tick(299, false, true));
        assertNotNull(tick(300, false, true));
    }
    @Test void successfulReturnAndExplicitResetAllowANewWait() {
        tick(0, false, true);
        assertNull(tick(200, true, false));
        guard.confirmTravel(); assertNull(tick(205, true, false));
        assertNull(tick(400, false, true));
        guard.reset();
        assertNull(tick(1000, false, true));
    }
    @Test void zeroDisablesOnlyThatTimeout() {
        assertNull(guard.evaluate(0, false, false, false, 0, 300));
        assertNull(guard.evaluate(1_000_000_000_000L, false, false, false, 0, 300));
        assertNotNull(guard.evaluate(1_000_000_000_000L, false, true, false, 0, 300));
    }
    @Test void brieflyReachingTheTravelWorldDoesNotRenewAnUnstableRecovery() {
        assertNull(tick(0, false, true));
        assertNull(tick(100, true, false));
        assertNull(tick(101, false, true));
        assertNull(tick(299, true, false));
        assertNotNull(tick(300, false, true));
    }
    @Test void aStableButMotionlessRejoinDoesNotRenewRecovery() {
        tick(0, false, true);
        assertNull(tick(200, true, false));
        assertNotNull(tick(300, true, false));
    }
    @Test void anOrdinaryDismountCannotTriggerTheWorldWaitTimeout() {
        assertNull(guard.evaluate(0, true, false, false, 30, 300));
        assertNull(guard.evaluate(1_000_000_000_000L, true, false, false, 30, 300));
        assertNull(guard.evaluate(1_001_000_000_000L, true, false, true, 30, 300));
    }
}
