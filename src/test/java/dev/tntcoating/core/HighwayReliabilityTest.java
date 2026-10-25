package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HighwayReliabilityTest {
    @Test void lowWingsAreReplacedDuringAnyFlightPhaseIncludingDescent() {
        assertEquals(HighwayFlightSafety.Action.REPLACE, HighwayFlightSafety.action(10, 10, true, true));
        assertEquals(HighwayFlightSafety.Action.CONTINUE, HighwayFlightSafety.action(11, 10, true, true));
    }
    @Test void blockedInventoryAndDisabledSwappingStillProtectThePlayer() {
        assertEquals(HighwayFlightSafety.Action.LOGOUT, HighwayFlightSafety.action(10, 10, true, false));
        assertEquals(HighwayFlightSafety.Action.LOGOUT, HighwayFlightSafety.action(10, 10, false, true));
        assertEquals(HighwayFlightSafety.Action.LOGOUT, HighwayFlightSafety.action(-1, 10, true, false));
        assertEquals(HighwayFlightSafety.Action.REPLACE, HighwayFlightSafety.action(2, 0, true, true));
    }
    @Test void prolongedOutageBacksOffWithoutRetryingTooFast() {
        HighwayReconnectBackoff backoff = new HighwayReconnectBackoff();
        backoff.failed(); assertEquals(3.5, backoff.delay(3.5, .5), 1e-8);
        backoff.failed(); assertEquals(7, backoff.delay(3.5, .5), 1e-8);
        backoff.failed(); assertEquals(14, backoff.delay(3.5, .5), 1e-8);
        for (int i=0;i<100;i++) backoff.failed();
        assertTrue(backoff.delay(3.5, 0) >= 54);
        assertTrue(backoff.delay(3.5, 1) <= 60);
        backoff.reset(); assertEquals(3.5, backoff.delay(3.5, .5), 1e-8);
        assertEquals(1, backoff.delay(0, .5), 1e-8);
    }
}
