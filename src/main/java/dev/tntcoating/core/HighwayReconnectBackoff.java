package dev.tntcoating.core;

/** Consecutive failures back off; stable connections reset the sequence. */
public final class HighwayReconnectBackoff {
    private int failures;
    public void failed() { failures = Math.min(30, failures + 1); }
    public void reset() { failures = 0; }
    public int failures() { return failures; }
    public double delay(double base, double random) {
        double first = Double.isFinite(base) ? Math.clamp(base, 1, 300) : 3.5;
        double ceiling = Math.max(first, 60);
        double scaled = Math.min(ceiling, first * Math.pow(2, Math.max(0, failures - 1)));
        double jitter = failures <= 1 ? 1 : .9 + .2 * Math.clamp(random, 0, 1);
        return Math.clamp(scaled * jitter, first, ceiling);
    }
}
