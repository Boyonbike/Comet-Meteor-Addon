package dev.tntcoating.core;

/** Session damage and forward progress tracking for highway travel. */
public final class HighwayTravelSafety {
    private double previousHealth;
    private double accumulatedDamage;
    private double acceptedProgress;
    private int stalledTicks;

    /** Starts damage tracking for a new session using the player's current health. */
    public void reset(double health) {
        requireFinite(health);
        previousHealth = health;
        accumulatedDamage = 0;
    }

    /** A new connection can report a different initial health without taking damage on this trip. */
    public void rebaseHealth(double health) {
        requireFinite(health);
        previousHealth = health;
    }

    /** Counts actual health drops; healing does not refund damage already sustained. */
    public boolean damageLimitReached(double health, double maxDamage) {
        requireFinite(health);
        requireFinite(maxDamage);
        accumulatedDamage += Math.max(0, previousHealth - health);
        previousHealth = health;
        return maxDamage > 0 && accumulatedDamage >= maxDamage;
    }

    /** Total health lost this session; healing never refunds damage already counted. */
    public double damageTaken() {
        return accumulatedDamage;
    }

    /** Starts a fresh timeout at a caller-defined progress value that increases forwards. */
    public void resetProgress(double courseProgress) {
        requireFinite(courseProgress);
        acceptedProgress = courseProgress;
        stalledTicks = 0;
    }

    /** Samples one travel tick, accepting only a new best at least one block farther forwards. */
    public boolean noProgress(double courseProgress, int limitTicks) {
        requireFinite(courseProgress);
        if (limitTicks <= 0) throw new IllegalArgumentException("Progress timeout must be positive.");
        if (courseProgress - acceptedProgress >= 1) {
            resetProgress(courseProgress);
            return false;
        }
        if (stalledTicks < Integer.MAX_VALUE) stalledTicks++;
        return stalledTicks >= limitTicks;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Safety inputs must be finite.");
    }
}
