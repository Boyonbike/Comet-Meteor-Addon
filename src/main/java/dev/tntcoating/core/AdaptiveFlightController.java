package dev.tntcoating.core;

import java.util.Objects;

/**
 * Tick-based flight momentum and correction recovery, independent of Minecraft.
 * Distances and acceleration are measured in blocks per client tick.
 */
public final class AdaptiveFlightController {
    public static final double MIN_SPEED_FACTOR = 0.1;

    public enum Status { READY, RECOVERING, PAUSED, TERRAIN_LIMITED }

    public record Motion(double x, double y, double z) {
        public static final Motion ZERO = new Motion(0, 0, 0);

        public Motion {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(Math.hypot(Math.hypot(x, y), z))) {
                throw new IllegalArgumentException("Motion must have finite components and length.");
            }
        }

        public double length() {
            return Math.hypot(Math.hypot(x, y), z);
        }

        public double horizontalLength() {
            return Math.hypot(x, z);
        }

        private Motion scaled(double factor) {
            return factor == 0 ? ZERO : new Motion(x * factor, y * factor, z * factor);
        }
    }

    public record Settings(double acceleration, double braking, double correctionMultiplier,
                           int correctionPauseTicks, int recoveryDelayTicks, double recoveryPerTick) {
        public Settings {
            if (!Double.isFinite(acceleration) || acceleration <= 0) {
                throw new IllegalArgumentException("Acceleration must be finite and positive.");
            }
            if (!Double.isFinite(braking) || braking <= 0) {
                throw new IllegalArgumentException("Braking must be finite and positive.");
            }
            if (!Double.isFinite(correctionMultiplier)
                || correctionMultiplier <= 0 || correctionMultiplier > 1) {
                throw new IllegalArgumentException("Correction multiplier must be in (0, 1].");
            }
            if (correctionPauseTicks < 0 || recoveryDelayTicks < 0) {
                throw new IllegalArgumentException("Recovery and pause ticks cannot be negative.");
            }
            if (!Double.isFinite(recoveryPerTick) || recoveryPerTick < 0 || recoveryPerTick > 1) {
                throw new IllegalArgumentException("Recovery per tick must be in [0, 1].");
            }
        }
    }

    private Settings settings;
    private Motion output = Motion.ZERO;
    private double speedFactor = 1;
    private int correctionCount;
    private int ticksSinceCorrection = Integer.MAX_VALUE;
    private int futurePauseTicks;
    private boolean pausedThisTick;
    private boolean pauseStarted;
    private boolean terrainLimited;
    private boolean boosting;

    public AdaptiveFlightController(Settings settings) {
        configure(settings);
    }

    /** Changes tuning without discarding momentum or an active correction backoff. */
    public void configure(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void reset() {
        output = Motion.ZERO;
        speedFactor = 1;
        correctionCount = 0;
        ticksSinceCorrection = Integer.MAX_VALUE;
        futurePauseTicks = 0;
        pausedThisTick = false;
        pauseStarted = false;
        terrainLimited = false;
        boosting = false;
    }

    /**
     * Starts a client tick. A correction halts the rest of its current tick and
     * the next configured number of complete ticks. Recovery never advances
     * more than once per tick, regardless of how many movement events occur.
     */
    public void tick() {
        boosting = false;
        pausedThisTick = futurePauseTicks > 0;
        if (pausedThisTick) futurePauseTicks--;
        pauseStarted = true;
        terrainLimited = false;
        if (ticksSinceCorrection < Integer.MAX_VALUE) ticksSinceCorrection++;
        if (!pausedThisTick && correctionCount > 0 && ticksSinceCorrection > settings.recoveryDelayTicks()) {
            speedFactor = Math.min(1, speedFactor + settings.recoveryPerTick());
        }
    }

    /** Records one or more server corrections without overflowing counters. */
    public void corrected(int count) {
        if (count < 0) throw new IllegalArgumentException("Correction count cannot be negative.");
        if (count == 0) return;
        correctionCount = (int) Math.min(Integer.MAX_VALUE, (long) correctionCount + count);
        speedFactor = Math.max(MIN_SPEED_FACTOR,
            speedFactor * Math.pow(settings.correctionMultiplier(), count));
        ticksSinceCorrection = 0;
        futurePauseTicks = settings.correctionPauseTicks();
        pausedThisTick = futurePauseTicks > 0;
        pauseStarted = false;
        output = Motion.ZERO;
        terrainLimited = false;
        boosting = false;
    }

    /**
     * Approaches the complete requested velocity vector, including vertical
     * movement. Releasing input, reducing speed, and reversing use the braking
     * rate. The terrain displacement limit always takes effect immediately.
     * Positive infinity means there is no terrain limit.
     */
    public Motion step(Motion requested, double safeDistance) {
        return step(requested, safeDistance, false);
    }

    /** Sprint skips the horizontal speed ramp while preserving steering, vertical smoothing and correction limits. */
    public Motion step(Motion requested, double safeDistance, boolean sprintBoost) {
        Objects.requireNonNull(requested, "requested");
        if (Double.isNaN(safeDistance) || safeDistance < 0) {
            throw new IllegalArgumentException("Safe distance must be nonnegative or positive infinity.");
        }
        terrainLimited = false;
        boosting = false;
        if (pausedThisTick) {
            output = Motion.ZERO;
            return output;
        }

        Motion target = requested.scaled(speedFactor);
        if (sprintBoost) output = boostHorizontal(output, target);
        boolean braking = target.length() < output.length() || oppositeDirection(output, target);
        output = approach(output, target, braking ? settings.braking() : settings.acceleration());
        double length = output.length();
        if (length > safeDistance) {
            output = output.scaled(safeDistance / length);
            terrainLimited = true;
        }
        return output;
    }

    /** Remembers the actual motion after an external swept collision/chunk guard. */
    public void accept(Motion actual) {
        Objects.requireNonNull(actual, "actual");
        if (pausedThisTick) {
            output = Motion.ZERO;
            boosting = false;
            return;
        }
        if (actual.length() < output.length()) terrainLimited = true;
        output = actual;
    }

    public Settings settings() { return settings; }
    public Motion output() { return output; }
    public double speedFactor() { return speedFactor; }
    public int correctionCount() { return correctionCount; }
    public int ticksSinceCorrection() { return ticksSinceCorrection; }
    public boolean isBoosting() { return boosting; }

    private Motion boostHorizontal(Motion current, Motion target) {
        double currentHorizontal = current.horizontalLength(), targetHorizontal = target.horizontalLength();
        if (targetHorizontal == 0 || targetHorizontal < currentHorizontal) return current;
        if (currentHorizontal > 0) {
            double direction = (current.x() / currentHorizontal) * (target.x() / targetHorizontal)
                + (current.z() / currentHorizontal) * (target.z() / targetHorizontal);
            // Re-expanding the old direction during a reversal would prevent braking through zero.
            if (direction <= 0) return current;
        }
        if (!Double.isFinite(Math.hypot(targetHorizontal, current.y()))) return current;
        boosting = true;
        if (currentHorizontal == 0) return new Motion(target.x(), current.y(), target.z());
        return new Motion((current.x() / currentHorizontal) * targetHorizontal, current.y(),
            (current.z() / currentHorizontal) * targetHorizontal);
    }

    /** Includes the current tick while it is paused. */
    public int pauseTicksRemaining() {
        return futurePauseTicks + (pausedThisTick && pauseStarted ? 1 : 0);
    }

    public Status status() {
        if (pausedThisTick) return Status.PAUSED;
        if (terrainLimited) return Status.TERRAIN_LIMITED;
        return speedFactor < 1 ? Status.RECOVERING : Status.READY;
    }

    private static boolean oppositeDirection(Motion current, Motion target) {
        double currentScale = maxComponent(current);
        double targetScale = maxComponent(target);
        if (currentScale == 0 || targetScale == 0) return false;
        return (current.x() / currentScale) * (target.x() / targetScale)
            + (current.y() / currentScale) * (target.y() / targetScale)
            + (current.z() / currentScale) * (target.z() / targetScale) < 0;
    }

    private static Motion approach(Motion current, Motion target, double maxDelta) {
        // Work in scaled coordinates so even a reversal between large finite
        // velocities cannot overflow when calculating the difference vector.
        double scale = Math.max(maxComponent(current), maxComponent(target));
        if (scale == 0) return Motion.ZERO;
        double dx = target.x() / scale - current.x() / scale;
        double dy = target.y() / scale - current.y() / scale;
        double dz = target.z() / scale - current.z() / scale;
        double distance = Math.hypot(Math.hypot(dx, dy), dz);
        if (distance == 0 || distance <= maxDelta / scale) return target;
        double fraction = (maxDelta / scale) / distance;
        double remaining = 1 - fraction;
        return new Motion(
            scale * (current.x() / scale * remaining + target.x() / scale * fraction),
            scale * (current.y() / scale * remaining + target.y() / scale * fraction),
            scale * (current.z() / scale * remaining + target.z() / scale * fraction)
        );
    }

    private static double maxComponent(Motion motion) {
        return Math.max(Math.abs(motion.x()), Math.max(Math.abs(motion.y()), Math.abs(motion.z())));
    }
}
