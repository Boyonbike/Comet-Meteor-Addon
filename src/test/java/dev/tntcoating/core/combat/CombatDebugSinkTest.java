/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatDebugSinkTest {
    private static JsonObject line(int number) {
        return JsonParser.parseString("{\"line\":" + number + "}").getAsJsonObject();
    }

    @Test
    void linesAreEvictedAfterTheFiveMinuteWindow() {
        AtomicLong clock = new AtomicLong();
        CombatDebugSink sink = new CombatDebugSink(clock::get);

        assertTrue(sink.append(line(1)));
        clock.set(TimeUnit.MINUTES.toNanos(4));
        assertTrue(sink.append(line(2)));
        assertEquals(2, sink.size());

        // Exactly at the window boundary the oldest line is still inside it.
        clock.set(CombatDebugSink.WINDOW_NANOS);
        assertEquals(List.of("{\"line\":1}", "{\"line\":2}"), sink.lines());

        // One nanosecond later it ages out.
        clock.set(CombatDebugSink.WINDOW_NANOS + 1);
        assertEquals(List.of("{\"line\":2}"), sink.lines());
    }

    @Test
    void readingEvictsAgedLinesEvenWithoutNewAppends() {
        AtomicLong clock = new AtomicLong();
        CombatDebugSink sink = new CombatDebugSink(clock::get);
        sink.append(line(1));
        clock.set(CombatDebugSink.WINDOW_NANOS + 1);
        assertEquals(List.of(), sink.lines());
        assertEquals(0, sink.size());
        assertEquals(1, sink.writtenRecords());
    }

    @Test
    void ringHasAHardLineCap() {
        AtomicLong clock = new AtomicLong();
        CombatDebugSink sink = new CombatDebugSink(clock::get, CombatDebugSink.WINDOW_NANOS, 2);
        sink.append(line(1));
        sink.append(line(2));
        sink.append(line(3));
        assertEquals(List.of("{\"line\":2}", "{\"line\":3}"), sink.lines());
        assertEquals(3, sink.writtenRecords());
        assertEquals(1, sink.droppedRecords());
    }

    @Test
    void closedSinkRejectsNewLinesButKeepsTheRetainedOnes() {
        CombatDebugSink sink = new CombatDebugSink();
        sink.append(line(1));
        sink.close();
        assertTrue(sink.isClosed());
        assertFalse(sink.append(line(2)));
        assertEquals(1, sink.writtenRecords());
        assertEquals(1, sink.droppedRecords());
        assertEquals(List.of("{\"line\":1}"), sink.lines());
        assertFalse(sink.lines().isEmpty());
    }

    @Test
    void sinkIsFilelessAndCannotFail() {
        CombatDebugSink sink = new CombatDebugSink();
        assertEquals(null, sink.path());
        assertEquals(null, sink.failure());
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CombatDebugSink(System::nanoTime, 0L, 10));
        assertThrows(IllegalArgumentException.class, () -> new CombatDebugSink(System::nanoTime, 1L, 0));
        assertThrows(NullPointerException.class, () -> new CombatDebugSink(null));
        CombatDebugSink sink = new CombatDebugSink();
        assertThrows(NullPointerException.class, () -> sink.append(null));
    }
}
