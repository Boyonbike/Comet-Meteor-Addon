package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatAfkSafetyTest {
    private final BoatAfkSafety safety = new BoatAfkSafety();
    private final BoatAfkSafety.Limits limits = new BoatAfkSafety.Limits(true, 0, 8, true, 30, 60);
    private String tick(long seconds, boolean recovering, boolean driver, int egaps, double health, boolean lava, double x) {
        return safety.evaluate(seconds * 1_000_000_000L, recovering, driver, egaps, health, lava, x, 0, -90, limits);
    }
    @Test void logsOutAtSupplyReserveIncludingAtTripStart() {
        assertNotNull(tick(0, false, true, 0, 20, false, 0));
        assertNull(tick(0, false, true, 1, 20, false, 0));
    }
    @Test void healthUsesRealHealthAndLavaIsImmediate() {
        assertNotNull(tick(0, false, true, 64, 8, false, 0));
        assertNotNull(tick(0, false, true, 64, 20, true, 0));
    }
    @Test void missingDriverTimesOutAndReboardingResetsIt() {
        assertNull(tick(0, false, false, 64, 20, false, 0));
        assertNull(tick(29, false, false, 64, 20, false, 0));
        assertNotNull(tick(30, false, false, 64, 20, false, 0));
        assertNull(tick(31, false, true, 64, 20, false, 0));
        assertNull(tick(32, false, false, 64, 20, false, 0));
    }
    @Test void tinyJitterDoesNotHideAStallButRealProgressResetsTimer() {
        assertNull(tick(0, false, true, 64, 20, false, 0));
        assertNull(tick(59, false, true, 64, 20, false, .1));
        assertNotNull(tick(60, false, true, 64, 20, false, .2));
        assertNull(tick(61, false, true, 64, 20, false, 1.1));
        assertNull(tick(120, false, true, 64, 20, false, 1.1));
    }
    @Test void recoverySuspendsInventoryChecksAndResetsTimeouts() {
        tick(0, false, true, 64, 20, false, 0);
        assertNull(tick(200, true, false, 0, 20, false, 0));
        assertNull(tick(300, false, true, 64, 20, false, 0));
        assertNull(tick(359, false, true, 64, 20, false, 0));
    }
    @Test void disabledChecksAndExplicitResetWork() {
        var off = new BoatAfkSafety.Limits(false, 0, 0, false, 0, 0);
        assertNull(safety.evaluate(0, false, false, 0, 1, true, 0, 0, -90, off));
        assertNull(safety.evaluate(Long.MAX_VALUE, false, false, 0, 1, true, 0, 0, -90, off));
        safety.reset();
        assertNull(tick(1000, false, true, 64, 20, false, 0));
    }
    @Test void nonzeroReserveAndRecoveryHealthProtectionRemainEffective() {
        var reserve = new BoatAfkSafety.Limits(true, 16, 8, true, 30, 60);
        assertNotNull(safety.evaluate(0, false, true, 16, 20, false, 0, 0, -90, reserve));
        assertNull(safety.evaluate(0, false, true, 17, 20, false, 0, 0, -90, reserve));
        assertNotNull(tick(0, true, true, 0, 8, false, 0));
        assertNotNull(tick(0, true, true, 0, 20, true, 0));
    }
    @Test void oscillatingBackToAnOldPositionDoesNotCountAsNewForwardProgress() {
        assertNull(tick(0, false, true, 64, 20, false, 0));
        assertNull(tick(1, false, true, 64, 20, false, 2));
        assertNull(tick(30, false, true, 64, 20, false, 0));
        assertNull(tick(59, false, true, 64, 20, false, 2));
        assertNotNull(tick(61, false, true, 64, 20, false, 0));
    }
    @Test void sidewaysAndBackwardMotionDoNotResetForwardTimeout() {
        safety.evaluate(0, false, true, 64, 20, false, 0, 0, -90, limits);
        assertNotNull(safety.evaluate(60_000_000_000L, false, true, 64, 20, false, -20, 20, -90, limits));
    }
    @Test void southAndNorthUseZProjectionAndHeadingChangesStartAFreshTimer() {
        assertNull(safety.evaluate(0, false, true, 64, 20, false, 0, 0, 0, limits));
        assertNull(safety.evaluate(59_000_000_000L, false, true, 64, 20, false, 0, 2, 0, limits));
        assertNull(safety.evaluate(100_000_000_000L, false, true, 64, 20, false, 0, -2, 180, limits));
        assertNotNull(safety.evaluate(160_000_000_000L, false, true, 64, 20, false, 0, 2, 180, limits));
    }
}
