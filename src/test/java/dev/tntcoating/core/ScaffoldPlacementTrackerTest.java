package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScaffoldPlacementTrackerTest {
    @Test void newBridgeCellsNeverWaitForOtherCellsToConfirm() {
        var tracker = new ScaffoldPlacementTracker();
        tracker.attempted(1, 10);
        assertFalse(tracker.canAttempt(1, 10, false));
        assertFalse(tracker.canAttempt(1, 11, false));
        assertTrue(tracker.canAttempt(1, 12, false));
        assertTrue(tracker.canAttempt(2, 10, false));
        assertTrue(tracker.canAttempt(2, 10, true));
    }

    @Test void repeatedRejectionsBackOffAndDoNotResetOnAirUpdates() {
        var tracker = new ScaffoldPlacementTracker();
        tracker.attempted(1, 0);
        tracker.serverUpdate(1, true, true);
        assertFalse(tracker.canAttempt(1, 1, false));
        assertTrue(tracker.canAttempt(1, 2, false));
        tracker.attempted(1, 2);
        assertFalse(tracker.canAttempt(1, 5, false));
        assertTrue(tracker.canAttempt(1, 6, false));
        tracker.attempted(1, 6);
        assertFalse(tracker.canAttempt(1, 13, false));
        assertTrue(tracker.canAttempt(1, 14, false));
        tracker.attempted(1, 14);
        assertFalse(tracker.canAttempt(1, 21, false));
        assertTrue(tracker.canAttempt(1, 22, false));
    }

    @Test void underfootRetriesStayFastEvenAfterRepeatedRejections() {
        var tracker = new ScaffoldPlacementTracker();
        tracker.attempted(1, 0);
        assertTrue(tracker.canAttempt(1, 1, true));
        tracker.attempted(1, 1);
        assertFalse(tracker.canAttempt(1, 2, true));
        assertTrue(tracker.canAttempt(1, 3, true));
        for (long tick = 3; tick < 100; tick += 2) {
            tracker.attempted(1, tick);
            assertFalse(tracker.canAttempt(1, tick + 1, true));
            assertTrue(tracker.canAttempt(1, tick + 2, true));
        }
    }

    @Test void localPredictionAloneCannotConfirmAServerRejectedPlacement() {
        var tracker = new ScaffoldPlacementTracker();
        tracker.attempted(1, 0);
        // Client predicts a block, but server's update says air.
        tracker.serverUpdate(1, true, false);
        assertEquals(1, tracker.size());
        assertFalse(tracker.canAttempt(1, 1, false));
        // Do not count a packet for a state not present in the loaded world as confirmation either.
        tracker.serverUpdate(1, false, true);
        assertEquals(1, tracker.size());
        tracker.serverUpdate(1, false, false);
        assertEquals(0, tracker.size());
        assertTrue(tracker.canAttempt(1, 1, false));
    }

    @Test void expiryBoundsMemoryAndAllowsFreshAttemptsAfterMovingAway() {
        var tracker = new ScaffoldPlacementTracker();
        tracker.attempted(1, 0);
        tracker.expire(159);
        assertEquals(1, tracker.size());
        tracker.expire(160);
        assertEquals(0, tracker.size());
        for (int i = 0; i < 1000; i++) tracker.attempted(i, i);
        assertEquals(256, tracker.size());
        assertTrue(tracker.canAttempt(0, 1000, false));
        tracker.clear();
        assertEquals(0, tracker.size());
        assertTrue(tracker.canAttempt(999, 1000, false));
    }
}
