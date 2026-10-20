package dev.tntcoating.core;

import java.util.Objects;

/**
 * VolytraFly's unlimited-boost speed ramp for one axis, in blocks per client tick. Speed starts at
 * {@code start}, gains {@code step·(maximum − speed)/maximum} per tick and survives a setback by rewinding a
 * few ticks of that curve rather than restarting, then holding that speed for {@code holdTicks} before
 * accelerating again (VolytraFly's acceleration delay: without it a server can pin you with setbacks).
 */
public final class BoostRamp {
    public record Settings(double start, double maximum, double step, int rewindTicks, int holdTicks) {
        public Settings {
            if (!Double.isFinite(start) || start < 0 || !Double.isFinite(maximum) || maximum < 0) {
                throw new IllegalArgumentException("Boost speeds must be finite and nonnegative.");
            }
            if (!Double.isFinite(step) || step <= 0) {
                throw new IllegalArgumentException("Boost ramp step must be finite and positive.");
            }
            if (rewindTicks < 0) throw new IllegalArgumentException("Rewind ticks cannot be negative.");
            if (holdTicks < 0) throw new IllegalArgumentException("Hold ticks cannot be negative.");
        }
    }

    private Settings settings;
    private boolean engaged;
    private double speed;
    private int hold;

    public BoostRamp(Settings settings) {
        configure(settings);
    }

    /** Changes tuning without discarding the ramp. */
    public void configure(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (engaged) speed = Math.min(speed, ceiling());
    }

    /** Starts boosting from the current speed on this axis, never below the start speed. */
    public void engage(double currentSpeed) {
        engaged = true;
        hold = 0;
        speed = Math.min(Math.max(floor(), Finite.clamp(currentSpeed, 0, Double.MAX_VALUE, 0)), ceiling());
    }

    /** One tick along the curve, unless a recent setback is still holding the speed. */
    public void advance() {
        if (!engaged) return;
        if (hold > 0) {
            hold--;
            return;
        }
        speed = next(speed, settings.step(), settings.maximum(), ceiling());
    }

    /** After a real collision cost speed, continue the ramp from what was kept. */
    public void limitTo(double actual) {
        if (!engaged || !(actual >= 0)) return;
        speed = Math.max(floor(), Math.min(speed, actual));
    }

    public void disengage() { engaged = false; }

    /**
     * A server setback while boosting: rewind {@code rewindTicks} of the curve instead of restarting it, then
     * hold that speed for {@code holdTicks} before accelerating.
     */
    public void corrected() {
        if (!engaged) return;
        speed = rewind(speed, settings.step(), settings.maximum(), floor(), settings.rewindTicks());
        hold = settings.holdTicks();
    }

    public void reset() {
        engaged = false;
        speed = 0;
        hold = 0;
    }

    public boolean engaged() { return engaged; }
    /** The current ramp speed, or zero while not boosting. */
    public double speed() { return engaged ? speed : 0; }
    public double ceiling() { return settings.maximum(); }

    private double floor() { return Math.min(settings.start(), ceiling()); }

    /** The plateau-curve gain at {@code speed}: steep when slow, tapering to zero at {@code plateau}. */
    public static double gain(double speed, double step, double plateau) {
        if (!(plateau > 0)) return 0;
        return step * Math.max(0, plateau - speed) / plateau;
    }

    /** One tick along the curve, never above {@code ceiling}. */
    public static double next(double speed, double step, double plateau, double ceiling) {
        return Math.min(speed + gain(speed, step, plateau), ceiling);
    }

    /** {@code ticks} steps back down the curve, each losing what that step would have gained, never below {@code floor}. */
    public static double rewind(double speed, double step, double plateau, double floor, int ticks) {
        for (int tick = 0; tick < ticks && speed > floor; tick++) {
            speed = Math.max(floor, speed - gain(speed, step, plateau));
        }
        return speed;
    }
}
