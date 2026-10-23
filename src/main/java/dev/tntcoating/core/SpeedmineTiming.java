package dev.tntcoating.core;

/**
 * Speedmine's server-tick pacing and start budget. Independent of the Meteor module, so the
 * TPS-synchronised mining rate can be tested without a client.
 */
public final class SpeedmineTiming {
    private SpeedmineTiming() {}

    /** Server ticks elapsed over a wall-clock interval, clamped to the 0-20 TPS range. */
    public static double serverTicks(long elapsedMillis, float tps) {
        return Math.max(0, elapsedMillis) / 1000.0 * Math.clamp(Float.isFinite(tps) ? tps : 20, 0, 20);
    }

    /**
     * Earns starts at the server's tick rate. Fractional overflow is retained, so client-tick
     * jitter does not discard credit, and the budget never banks more than two starts.
     */
    public static double replenishStartCredit(double credit, long elapsedMillis, float tps) {
        return Math.min(2, credit + serverTicks(elapsedMillis, tps));
    }

    /** At full TPS, or with sync off, a start never waits for credit. */
    public static boolean canStart(boolean sync, float tps, double credit) {
        return !sync || !Float.isFinite(tps) || tps >= 20 || credit >= 1;
    }
}
