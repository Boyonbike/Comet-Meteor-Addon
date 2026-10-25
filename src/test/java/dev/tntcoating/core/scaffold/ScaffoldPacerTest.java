package dev.tntcoating.core.scaffold;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldPacerTest {
    @Test void latencyTimeoutClampsAndEmaWeightsRecentSamplesByPointTwo() {
        AtomicLong now = new AtomicLong(); var p = new ScaffoldPacer(now::get);
        assertEquals(1_000_000_000L, p.timeoutNanos());
        p.confirmed(400_000_000); assertEquals(1_600_000_000L, p.timeoutNanos());
        p.confirmed(100_000_000); assertEquals(1_420_000_000L, p.timeoutNanos());
        p.confirmed(100_000_000_000L); assertEquals(10_000_000_000L, p.timeoutNanos());
    }
    @Test void dispatchSpacingAndCorrectionPauseAreAbsoluteMonotonicDurations() {
        AtomicLong now = new AtomicLong(-1_000_000_000); var p = new ScaffoldPacer(now::get);
        assertTrue(p.ready()); p.dispatched(); assertFalse(p.ready());
        now.addAndGet(50_000_000); assertTrue(p.ready());
        p.correction(); now.addAndGet(499_999_999); assertFalse(p.ready());
        now.incrementAndGet(); assertTrue(p.ready());
    }
    @Test void ordinaryBackoffCapsAtTwoSecondsUrgentAt100ms() {
        assertEquals(150_000_000L, ScaffoldPacer.retryDelayNanos(false, 1));
        assertEquals(300_000_000L, ScaffoldPacer.retryDelayNanos(false, 2));
        assertEquals(2_000_000_000L, ScaffoldPacer.retryDelayNanos(false, 100));
        assertEquals(50_000_000L, ScaffoldPacer.retryDelayNanos(true, 1));
        assertEquals(100_000_000L, ScaffoldPacer.retryDelayNanos(true, 2));
    }
}
