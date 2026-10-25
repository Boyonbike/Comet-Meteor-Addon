package dev.tntcoating.core.scaffold;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Monotonic interaction spacing and authoritative-latency feedback; no tick assumptions. */
public final class ScaffoldPacer {
    private final LongSupplier clock;
    private long minInterval = 50_000_000L, dispatchAt, correctionAt;
    private boolean dispatched, correcting, sampled;
    private double latencyNanos;
    public ScaffoldPacer(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public void setMinIntervalNanos(long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("interval must not be negative");
        minInterval = nanos;
    }
    public boolean ready() {
        long now = clock.getAsLong();
        return (!dispatched || now - dispatchAt >= minInterval)
            && (!correcting || now - correctionAt >= 500_000_000L);
    }
    public long nextEligibleNanos() {
        long now = clock.getAsLong(), delay = 0;
        if (dispatched) delay = Math.max(delay, minInterval - (now - dispatchAt));
        if (correcting) delay = Math.max(delay, 500_000_000L - (now - correctionAt));
        return now + delay;
    }
    public void dispatched() { dispatched = true; dispatchAt = clock.getAsLong(); }
    public void correction() { correcting = true; correctionAt = clock.getAsLong(); }
    public void confirmed(long elapsedNanos) {
        if (elapsedNanos < 0) throw new IllegalArgumentException("negative latency");
        latencyNanos = sampled ? latencyNanos * .8 + elapsedNanos * .2 : elapsedNanos;
        sampled = true;
    }
    public double latencyMillis() { return sampled ? latencyNanos / 1_000_000.0 : 0; }
    public long timeoutNanos() {
        return sampled ? (long) Math.max(1_000_000_000L, Math.min(10_000_000_000L, 3 * latencyNanos + 400_000_000L)) : 1_000_000_000L;
    }
    public static long retryDelayNanos(boolean urgent, int failures) {
        if (urgent) return failures <= 1 ? 50_000_000L : 100_000_000L;
        return Math.min(2_000_000_000L, 150_000_000L << Math.min(4, Math.max(0, failures - 1)));
    }
    public void reset() { dispatched = correcting = sampled = false; latencyNanos = 0; }
}
