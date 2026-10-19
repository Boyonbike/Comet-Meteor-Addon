package dev.tntcoating.core;

/**
 * Smoothed movement speed from wall-clock time, so Timer and client tick stalls cannot scale it. A jump larger than
 * any plausible movement is a teleport and a long gap is a pause: both restart sampling without a spike.
 */
public final class SpeedSampler {
    /** Blocks between consecutive samples treated as a teleport rather than movement. */
    public static final double TELEPORT_DISTANCE = 64;
    /** Samples further apart than this restart instead of averaging across a stall or pause. */
    public static final long MAX_GAP_NANOS = 1_000_000_000L;

    private boolean started;
    private double x, y, z;
    private long time;
    private double speed;

    /**
     * Adds a position sample and returns the smoothed speed in blocks per second.
     *
     * @param weight share of the new sample in the average, in (0, 1]; 1 shows the latest interval only
     */
    public double sample(double x, double y, double z, boolean includeVertical, long nanos, double weight) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            reset();
            return speed;
        }
        if (!started) {
            restart(x, y, z, nanos);
            return speed;
        }
        long elapsed = nanos - time;
        if (elapsed <= 0) return speed;
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        restart(x, y, z, nanos);
        if (elapsed > MAX_GAP_NANOS || dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE * TELEPORT_DISTANCE) return speed;
        double distance = includeVertical ? Math.sqrt(dx * dx + dy * dy + dz * dz) : Math.hypot(dx, dz);
        double instant = distance * 1_000_000_000.0 / elapsed;
        double share = Math.max(Double.MIN_VALUE, Math.min(1, weight));
        speed += (instant - speed) * share;
        return speed;
    }

    public double speed() { return speed; }

    /** Forgets the previous position and the displayed speed. */
    public void reset() {
        started = false;
        speed = 0;
    }

    private void restart(double x, double y, double z, long nanos) {
        started = true;
        this.x = x;
        this.y = y;
        this.z = z;
        time = nanos;
    }
}
