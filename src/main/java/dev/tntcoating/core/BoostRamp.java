package dev.tntcoating.core;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * VolytraFly's unlimited-boost speed ramp for one axis, in blocks per client tick. Speed starts at
 * {@code start}, gains {@code step·(maximum − speed)/maximum} per tick and survives a setback by rewinding a
 * few ticks of that curve rather than restarting. Repeated setbacks close together cap it just below the
 * slowest of them for a while. Ticks are client ticks.
 */
public final class BoostRamp {
    public static final int CAP_TRIGGER_COUNT = 3;
    public static final int CAP_WINDOW_TICKS = 60;
    public static final int CAP_DURATION_TICKS = 200;
    public static final double CAP_MARGIN = .95;

    public record Settings(double start, double maximum, double step, int rewindTicks, boolean autoCap) {
        public Settings {
            if (!Double.isFinite(start) || start < 0 || !Double.isFinite(maximum) || maximum < 0) {
                throw new IllegalArgumentException("Boost speeds must be finite and nonnegative.");
            }
            if (!Double.isFinite(step) || step <= 0) {
                throw new IllegalArgumentException("Boost ramp step must be finite and positive.");
            }
            if (rewindTicks < 0) throw new IllegalArgumentException("Rewind ticks cannot be negative.");
        }
    }

    private Settings settings;
    private boolean engaged;
    private double speed, lastSpeed;
    private double cap = Double.POSITIVE_INFINITY;
    private int capTicks;
    private final ArrayDeque<Long> setbackTicks = new ArrayDeque<>();
    private final ArrayDeque<Double> setbackSpeeds = new ArrayDeque<>();

    public BoostRamp(Settings settings) {
        configure(settings);
    }

    /** Changes tuning without discarding the ramp or an active cap. */
    public void configure(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (engaged) speed = Math.min(speed, ceiling());
    }

    /** Starts boosting from the current speed on this axis, never below the start speed. */
    public void engage(double currentSpeed) {
        engaged = true;
        speed = Math.min(Math.max(floor(), Finite.clamp(currentSpeed, 0, Double.MAX_VALUE, 0)), ceiling());
        lastSpeed = speed;
    }

    /** One tick along the curve. */
    public void advance() {
        if (!engaged) return;
        speed = next(speed, settings.step(), settings.maximum(), ceiling());
        lastSpeed = speed;
    }

    /** After a real collision cost speed, continue the ramp from what was kept. */
    public void limitTo(double actual) {
        if (!engaged || !(actual >= 0)) return;
        speed = Math.max(floor(), Math.min(speed, actual));
        lastSpeed = speed;
    }

    public void disengage() { engaged = false; }

    /**
     * A server setback while boosting at {@code speedAtCorrection}: rewind the ramp and, after
     * {@link #CAP_TRIGGER_COUNT} setbacks within {@link #CAP_WINDOW_TICKS}, cap it below the slowest of them.
     */
    public void corrected(long tick, double speedAtCorrection) {
        if (engaged) {
            speed = rewind(speed, settings.step(), settings.maximum(), floor(), settings.rewindTicks());
            lastSpeed = speed;
        }
        if (!settings.autoCap() || !(speedAtCorrection > 0) || !Double.isFinite(speedAtCorrection)) return;
        setbackTicks.addLast(tick);
        setbackSpeeds.addLast(speedAtCorrection);
        while (!setbackTicks.isEmpty() && tick - setbackTicks.getFirst() > CAP_WINDOW_TICKS) {
            setbackTicks.removeFirst();
            setbackSpeeds.removeFirst();
        }
        if (setbackTicks.size() < CAP_TRIGGER_COUNT) return;
        double slowest = Double.POSITIVE_INFINITY;
        for (double recorded : setbackSpeeds) slowest = Math.min(slowest, recorded);
        cap = Math.max(settings.start(), slowest * CAP_MARGIN);
        capTicks = CAP_DURATION_TICKS;
        setbackTicks.clear();
        setbackSpeeds.clear();
        if (engaged) {
            speed = Math.min(speed, ceiling());
            lastSpeed = speed;
        }
    }

    /** Once per client tick: an expired cap lets the ramp climb towards the maximum again. */
    public void tick() {
        if (capTicks > 0 && --capTicks == 0) cap = Double.POSITIVE_INFINITY;
    }

    public void reset() {
        engaged = false;
        speed = lastSpeed = 0;
        cap = Double.POSITIVE_INFINITY;
        capTicks = 0;
        setbackTicks.clear();
        setbackSpeeds.clear();
    }

    public boolean engaged() { return engaged; }
    /** The current ramp speed, or zero while not boosting. */
    public double speed() { return engaged ? speed : 0; }
    /** The most recent ramp speed, kept after release for setbacks that arrive late. */
    public double lastSpeed() { return lastSpeed; }
    public boolean capped() { return capTicks > 0; }
    public double cap() { return cap; }
    public int capTicksRemaining() { return capTicks; }
    public double ceiling() { return Math.min(settings.maximum(), cap); }

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
