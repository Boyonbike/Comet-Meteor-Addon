package dev.tntcoating.core.scaffold;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldPacketWindowTest {
    @Test void exactRollingWindowExpiresAtOneSecondIndependentOfClientTicks() {
        AtomicLong now = new AtomicLong(-2_000_000_000L);
        var window = new ScaffoldPacketWindow(now::get);
        window.observe(false); now.addAndGet(100_000_000); window.observe(false);
        assertEquals(2, window.actual()); now.addAndGet(899_999_999);
        assertEquals(2, window.actual()); now.incrementAndGet(); assertEquals(1, window.actual());
        now.addAndGet(100_000_000); assertEquals(0, window.actual());
    }
    @Test void reservationsReconcileAtomicallyAndExternalTrafficCanInvalidateAdmission() {
        AtomicLong now = new AtomicLong(); var window = new ScaffoldPacketWindow(now::get);
        window.setCap(3); assertTrue(window.reserve(3));
        window.observe(true); assertEquals(1, window.actual()); assertEquals(2, window.reserved());
        assertTrue(window.withinCap()); window.observe(false); assertFalse(window.withinCap());
        window.release(2); assertEquals(1, window.remaining());
        assertThrows(IllegalArgumentException.class, () -> window.release(1));
    }
    @Test void overflowWithholdsOneSecondAfterLastDroppedPacketAndStorageStaysBounded() {
        AtomicLong now = new AtomicLong(); var window = new ScaffoldPacketWindow(now::get);
        window.setCap(10000);
        for (int i = 0; i < 4097; i++) window.observe(false);
        assertEquals(4096, window.actual()); assertEquals(0, window.remaining()); assertTrue(window.overflowed());
        now.set(500_000_000); window.observe(false);
        now.set(1_000_000_000); assertFalse(window.reserve(1));
        now.set(1_499_999_999); assertFalse(window.reserve(1));
        now.incrementAndGet(); assertTrue(window.reserve(1)); assertFalse(window.overflowed());
    }
}
