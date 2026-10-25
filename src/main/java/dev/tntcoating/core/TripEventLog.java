/*
 * Comet highway dashboard event feed.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A bounded, thread-safe history of Highway Fly trip events and phase changes for the local dashboard. The game
 * thread records entries; dashboard HTTP threads only read immutable copies.
 */
public final class TripEventLog {
    /** The shared feed used by Highway Fly and the dashboard module. */
    public static final TripEventLog INSTANCE = new TripEventLog(100, System::currentTimeMillis);
    private static final long DUPLICATE_WINDOW_MILLIS = 1000;

    /** One entry in the feed. {@code kind} is {@code "trip"} for webhook events and {@code "phase"} for transitions. */
    public record Entry(long sequence, long timeMillis, String kind, String type, String title, int color,
                        String description) {}

    private final int capacity;
    private final LongSupplier clock;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long sequence;
    private Entry last;

    public TripEventLog(int capacity, LongSupplier clock) {
        if (capacity < 2) throw new IllegalArgumentException("Capacity must be at least two.");
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records a trip event with the same title and colour the Discord webhook uses. */
    public synchronized void record(HighwayWebhook.Event event, String description) {
        Objects.requireNonNull(event, "event");
        add(new Entry(0, clock.getAsLong(), "trip", event.name(), event.title(), event.color(), description));
    }

    /** Records a phase or state change, ignoring blank keys. Repeated identical phases are coalesced. */
    public synchronized void recordPhase(String key, String title, String description) {
        if (key == null || key.isBlank()) return;
        add(new Entry(0, clock.getAsLong(), "phase", key, title == null ? key : title, 0, description));
    }

    private void add(Entry candidate) {
        if (last != null && last.kind().equals(candidate.kind()) && last.type().equals(candidate.type())
            && Objects.equals(last.description(), candidate.description())
            && candidate.timeMillis() - last.timeMillis() < DUPLICATE_WINDOW_MILLIS) {
            return;
        }
        Entry entry = new Entry(++sequence, candidate.timeMillis(), candidate.kind(), candidate.type(),
            candidate.title(), candidate.color(), candidate.description());
        entries.addLast(entry);
        while (entries.size() > capacity) entries.removeFirst();
        last = entry;
    }

    /** Oldest to newest copy of the current feed. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Empties the feed without resetting the sequence, so readers never mistake new entries for old ones. */
    public synchronized void clear() {
        entries.clear();
        last = null;
    }
}
