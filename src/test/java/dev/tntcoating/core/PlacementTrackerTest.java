package dev.tntcoating.core;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlacementTrackerTest {
    private final List<String> events = new ArrayList<>();
    private final PlacementTracker<Integer, String> tracker = new PlacementTracker<>(new PlacementTracker.Listener<>() {
        @Override public void accepted(Integer pos, int latency) { events.add("accepted " + pos + " " + latency); }
        @Override public void retryScheduled(Integer pos, boolean failure) { events.add("retry " + pos + " " + failure); }
        @Override public void retryReady(Integer pos) { events.add("ready " + pos); }
    });

    private PlacementTracker.Attempt<String> send(int pos, int tick) {
        var attempt = tracker.reserve(pos, tick, "tnt");
        tracker.markSent(pos, attempt, tick);
        return attempt;
    }

    private void reject(int pos, int tick) { tracker.update(pos, false, true, send(pos, tick), tick + 1, 3); }

    @Test void confirmationReportsLatencyAndResolves() {
        var attempt = send(1, 2);
        tracker.update(1, true, false, attempt, 6, 3);
        assertEquals(List.of("accepted 1 4"), events);
        assertFalse(tracker.isPending(1));
        assertFalse(tracker.unconfirmed(1));
    }
    @Test void repeatedRejectionsBackOffUpToTheCap() {
        int[] expected = {3, 6, 12, 24, 40, 40};
        for (int i = 0; i < expected.length; i++) {
            int tick = i * 100;
            reject(1, tick);
            assertTrue(tracker.retryBlocked(1, tick + expected[i]));
            assertFalse(tracker.retryBlocked(1, tick + 1 + expected[i]));
        }
        assertTrue(events.stream().allMatch(e -> e.equals("retry 1 true")));
    }
    @Test void placementsLostToASetbackRetryWithoutGrowingTheBackoff() {
        reject(1, 0);
        reject(1, 100);
        var attempt = send(1, 200);
        tracker.setback();
        tracker.update(1, false, true, attempt, 201, 3);
        assertEquals("retry 1 false", events.getLast());
        assertTrue(tracker.retryBlocked(1, 206));
        assertFalse(tracker.retryBlocked(1, 207));
    }
    @Test void setbackDiscardsUnsentReservationsButKeepsSentOnes() {
        tracker.reserve(1, 0, "tnt");
        send(2, 0);
        tracker.setback();
        assertFalse(tracker.isPending(1));
        assertTrue(tracker.isPending(2));
    }
    @Test void sentAttemptTimesOutIntoARetry() {
        send(1, 0);
        tracker.expire(19, 20, 3, pos -> true);
        assertTrue(tracker.isPending(1));
        tracker.expire(20, 20, 3, pos -> true);
        assertFalse(tracker.isPending(1));
        assertEquals(List.of("retry 1 true"), events);
    }
    @Test void staleReservationIsDroppedWithoutARetry() {
        tracker.reserve(1, 0, "tnt");
        tracker.expire(2, 20, 3, pos -> true);
        assertTrue(tracker.isPending(1));
        tracker.expire(3, 20, 3, pos -> true);
        assertFalse(tracker.isPending(1));
        assertTrue(events.isEmpty());
    }
    @Test void retryBecomesReadyWhenItsDelayEnds() {
        reject(1, 10);
        tracker.expire(13, 20, 3, pos -> true);
        assertFalse(events.contains("ready 1"));
        tracker.expire(14, 20, 3, pos -> true);
        assertEquals("ready 1", events.getLast());
    }
    @Test void laterDisappearanceOfAConfirmedBlockRetriesWhileWatched() {
        var attempt = send(1, 0);
        tracker.update(1, true, false, attempt, 2, 3);
        assertTrue(tracker.concerns(1, null));
        tracker.update(1, false, true, null, 50, 3);
        assertEquals("retry 1 true", events.getLast());
    }
    @Test void watchEndsAfterItsWindowOrOutOfRange() {
        var attempt = send(1, 0);
        tracker.update(1, true, false, attempt, 2, 3);
        tracker.expire(PlacementTracker.WATCH_TICKS, 20, 3, pos -> true);
        assertTrue(tracker.concerns(1, null));
        tracker.expire(PlacementTracker.WATCH_TICKS + 1, 20, 3, pos -> true);
        assertFalse(tracker.concerns(1, null));
        var other = send(2, 0);
        tracker.update(2, true, false, other, 2, 3);
        tracker.expire(3, 20, 3, pos -> pos != 2);
        assertFalse(tracker.concerns(2, null));
    }
    @Test void anotherBlockTakingThePlaceClearsTracking() {
        reject(1, 0);
        tracker.update(1, false, false, null, 2, 3);
        assertFalse(tracker.retryBlocked(1, 2));
        assertFalse(tracker.concerns(1, null));
    }
    @Test void updatesBeforeSendingAreIgnored() {
        var attempt = tracker.reserve(1, 0, "tnt");
        assertFalse(tracker.concerns(1, attempt));
        tracker.markSent(1, attempt, 1);
        assertTrue(tracker.concerns(1, attempt));
    }
    @Test void cancellingAnOutdatedReservationKeepsTheCurrentOne() {
        var old = tracker.reserve(1, 0, "tnt");
        var current = tracker.reserve(1, 1, "tnt");
        tracker.cancel(1, old);
        assertTrue(tracker.current(1, current));
    }
}
