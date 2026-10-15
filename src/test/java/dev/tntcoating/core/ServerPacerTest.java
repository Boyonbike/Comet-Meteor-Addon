package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ServerPacerTest {
    @Test void startsSlowAndCapsOutstanding() {
        ServerPacer p = new ServerPacer();
        assertEquals(1, p.budget(1, 2, 0, 6));
        assertEquals(0, p.budget(1, 2, 6, 6));
        p.sent(1);
        assertEquals(0, p.budget(1, 2, 0, 6));
        assertEquals(1, p.budget(2, 2, 0, 6));
    }
    @Test void confirmationsRampWithinCap() {
        ServerPacer p = new ServerPacer();
        for (int i=0; i<100; i++) p.accepted(3, 2);
        assertEquals(2, p.budget(1, 2, 0, 6));
        assertEquals(1, p.budget(1, 1, 0, 6));
        assertEquals(1, p.budget(1, 2, 5, 6));
    }
    @Test void failurePausesAndReducesRate() {
        ServerPacer p = new ServerPacer();
        for (int i=0; i<12; i++) p.accepted(3, 2);
        p.failed(10, 3);
        assertEquals(0, p.budget(12, 2, 0, 6));
        assertEquals(1, p.budget(13, 2, 0, 6));
    }
    @Test void latencyExtendsTimeoutWithinBounds() {
        ServerPacer p = new ServerPacer();
        for (int i=0; i<20; i++) p.accepted(30, 2);
        assertTrue(p.timeout(20) > 80);
        for (int i=0; i<20; i++) p.accepted(1000, 2);
        assertEquals(200, p.timeout(20));
        p.reset();
        assertEquals(20, p.timeout(20));
    }
    @Test void noCatchupBurstAfterLongPause() {
        ServerPacer p = new ServerPacer();
        assertEquals(1, p.budget(10000, 8, 0, 16));
        p.sent(10000);
        assertEquals(0, p.budget(10000, 8, 0, 16));
    }
}
