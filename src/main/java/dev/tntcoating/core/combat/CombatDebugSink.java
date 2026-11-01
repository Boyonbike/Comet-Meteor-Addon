/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The tester build's in-memory trace sink: a rolling ring of the last five minutes of JSON Lines.
 *
 * <p>No disk path exists and {@link #failure()} is always null. Lines are evicted by age on every append and
 * on every read, and the ring also has a hard line cap so an idle client cannot grow it without bound. A
 * monotonic {@link LongSupplier} is injectable so the age bound is unit-testable without sleeping.</p>
 */
public final class CombatDebugSink implements CombatTraceSink {
    public static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(5);
    public static final int MAX_LINES = 20_000;

    private record Line(long nanos, String json) { }

    private final Object lock = new Object();
    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private final LongSupplier clock;
    private final long windowNanos;
    private final int maxLines;
    private long writtenRecords;
    private long droppedRecords;
    private boolean closed;

    public CombatDebugSink() {
        this(System::nanoTime, WINDOW_NANOS, MAX_LINES);
    }

    public CombatDebugSink(LongSupplier clock) {
        this(clock, WINDOW_NANOS, MAX_LINES);
    }

    /** Test seam: caller controls both the window and the monotonic clock. */
    public CombatDebugSink(LongSupplier clock, long windowNanos, int maxLines) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (windowNanos <= 0) throw new IllegalArgumentException("windowNanos must be positive");
        if (maxLines <= 0) throw new IllegalArgumentException("maxLines must be positive");
        this.windowNanos = windowNanos;
        this.maxLines = maxLines;
    }

    @Override
    public boolean append(JsonObject line) {
        Objects.requireNonNull(line, "line");
        long now = clock.getAsLong();
        synchronized (lock) {
            if (closed) {
                droppedRecords++;
                return false;
            }
            evict(now);
            if (lines.size() >= maxLines) {
                lines.removeFirst();
                droppedRecords++;
            }
            lines.addLast(new Line(now, line.toString()));
            writtenRecords++;
            return true;
        }
    }

    /** A copy of the retained lines, oldest first. Also evicts anything older than the window. */
    public List<String> lines() {
        long now = clock.getAsLong();
        synchronized (lock) {
            evict(now);
            List<String> copy = new ArrayList<>(lines.size());
            for (Line line : lines) copy.add(line.json());
            return List.copyOf(copy);
        }
    }

    /** Number of lines currently retained after age eviction. */
    public int size() {
        long now = clock.getAsLong();
        synchronized (lock) {
            evict(now);
            return lines.size();
        }
    }

    /** True after {@link #close()}: no more lines are accepted, but retained lines stay exportable. */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    @Override
    public long writtenRecords() {
        synchronized (lock) {
            return writtenRecords;
        }
    }

    @Override
    public long droppedRecords() {
        synchronized (lock) {
            return droppedRecords;
        }
    }

    @Override
    public IOException failure() {
        return null;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
        }
    }

    private void evict(long now) {
        while (!lines.isEmpty() && now - lines.peekFirst().nanos() > windowNanos) {
            lines.removeFirst();
        }
    }
}
