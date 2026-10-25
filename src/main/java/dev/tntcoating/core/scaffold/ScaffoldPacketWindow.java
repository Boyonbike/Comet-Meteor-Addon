package dev.tntcoating.core.scaffold;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Connection-wide exact rolling one-second packet counter, independent of client ticks.
 * An overflow fails closed for a full second after the most recent dropped observation.
 * The owner must serialize access with its lifecycle/reservation operations.
 */
public final class ScaffoldPacketWindow {
    private static final long SECOND = 1_000_000_000L;
    private final LongSupplier clock;
    private final long[] times = new long[4096];
    private int head, size, reserved, cap = 150;
    private boolean overflow;
    private long lastOverflow;

    public ScaffoldPacketWindow(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public synchronized void setCap(int cap) {
        if (cap < 1) throw new IllegalArgumentException("packet cap must be positive");
        this.cap = cap;
    }
    public synchronized int cap() { return cap; }
    public synchronized int actual() { expire(); return size; }
    public synchronized int reserved() { return reserved; }
    public synchronized boolean overflowed() { expire(); return overflow; }
    public synchronized int remaining() {
        expire(); return overflow ? 0 : (int) Math.max(0, (long) cap - size - reserved);
    }
    public synchronized boolean withinCap() { expire(); return !overflow && (long) size + reserved <= cap; }
    public synchronized boolean reserve(int count) {
        if (count < 1) throw new IllegalArgumentException("reservation must be positive");
        if (remaining() < count) return false;
        reserved += count; return true;
    }
    public synchronized void release(int count) {
        if (count < 0 || count > reserved) throw new IllegalArgumentException("invalid reservation refund");
        reserved -= count;
    }
    /** Only actual Sent observations belong here. An attributed packet consumes one remaining credit. */
    public synchronized void observe(boolean consumesReservedCredit) {
        expire();
        if (consumesReservedCredit && reserved > 0) reserved--;
        long now = clock.getAsLong();
        if (size == times.length) { overflow = true; lastOverflow = now; return; }
        times[(head + size) % times.length] = now;
        size++;
    }
    private void expire() {
        long now = clock.getAsLong();
        while (size > 0 && now - times[head] >= SECOND) {
            head = (head + 1) % times.length; size--;
        }
        if (overflow && now - lastOverflow >= SECOND) overflow = false;
    }
}
