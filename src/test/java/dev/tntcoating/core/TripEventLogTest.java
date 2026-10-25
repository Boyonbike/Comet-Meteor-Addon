package dev.tntcoating.core;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TripEventLogTest {
    @Test void tripEventsUseTheWebhookTitleAndColour() {
        AtomicLong clock = new AtomicLong(1_000);
        TripEventLog log = new TripEventLog(4, clock::get);

        log.record(HighwayWebhook.Event.STARTED, "Flight started");

        TripEventLog.Entry entry = log.entries().get(0);
        assertEquals("trip", entry.kind());
        assertEquals("STARTED", entry.type());
        assertEquals(HighwayWebhook.Event.STARTED.title(), entry.title());
        assertEquals(HighwayWebhook.Event.STARTED.color(), entry.color());
        assertEquals("Flight started", entry.description());
        assertEquals(1_000, entry.timeMillis());
        assertTrue(entry.sequence() > 0);
    }

    @Test void consecutiveIdenticalEventsInsideTheWindowAreCoalesced() {
        AtomicLong clock = new AtomicLong(1_000);
        TripEventLog log = new TripEventLog(8, clock::get);

        log.record(HighwayWebhook.Event.STATUS, "Cruising");
        log.record(HighwayWebhook.Event.STATUS, "Cruising");
        assertEquals(1, log.entries().size());

        clock.addAndGet(1_001);
        log.record(HighwayWebhook.Event.STATUS, "Cruising");
        assertEquals(2, log.entries().size());
    }

    @Test void differentDescriptionsAreNeverCoalesced() {
        AtomicLong clock = new AtomicLong(1_000);
        TripEventLog log = new TripEventLog(8, clock::get);

        log.record(HighwayWebhook.Event.STATUS, "Cruising");
        log.record(HighwayWebhook.Event.STATUS, "Detouring");

        assertEquals(2, log.entries().size());
    }

    @Test void capacityEvictsTheOldestEntriesFirst() {
        AtomicLong clock = new AtomicLong(1_000);
        TripEventLog log = new TripEventLog(2, clock::get);

        log.recordPhase("TAKING_OFF", "Taking off", "detail");
        log.recordPhase("CLIMBING", "Climbing", "detail");
        log.recordPhase("CRUISE", "Flying", "detail");

        assertEquals(2, log.entries().size());
        assertEquals("CLIMBING", log.entries().get(0).type());
        assertEquals("CRUISE", log.entries().get(1).type());
    }

    @Test void blankPhasesAreIgnored() {
        TripEventLog log = new TripEventLog(4, () -> 1_000);

        log.recordPhase(null, "Flying", "detail");
        log.recordPhase("  ", "Flying", "detail");

        assertTrue(log.entries().isEmpty());
    }

    @Test void clearKeepsTheSequenceMonotonic() {
        AtomicLong clock = new AtomicLong(1_000);
        TripEventLog log = new TripEventLog(4, clock::get);
        log.recordPhase("CRUISE", "Flying", "detail");
        long beforeClear = log.entries().get(0).sequence();

        log.clear();
        assertTrue(log.entries().isEmpty());

        log.recordPhase("DONE", "Done", "detail");
        assertTrue(log.entries().get(0).sequence() > beforeClear);
    }

    @Test void invalidCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TripEventLog(1, () -> 0));
        assertThrows(NullPointerException.class, () -> new TripEventLog(2, null));
    }
}
