/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-22, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

import java.util.Objects;

/**
 * VolytraFly's boost ramp for one axis of Auto Wasp's pursuit, in blocks per tick, kept apart from Adaptive Elytra
 * Fly so each module can be tuned alone. Speed starts at {@code start}, gains {@code step·(plateau − speed)/plateau}
 * per tick and stops at {@code limit} without reshaping the curve. Slowing down for a waypoint or the target leaves the
 * ramp where it is, since the server never checks slowing down; only a real collision costs ramp speed. A setback
 * rewinds {@code rewindTicks} of the curve instead of restarting it, then holds that speed for {@code holdTicks} before
 * accelerating again, so back-to-back setbacks cannot pin the flight.
 */
public final class AutoWaspRamp {
    public record Settings(double start, double plateau, double step, int rewindTicks, int holdTicks, double limit) {
        public Settings {
            if (!Double.isFinite(start) || start < 0 || !Double.isFinite(plateau) || plateau < 0) {
                throw new IllegalArgumentException("Ramp speeds must be finite and nonnegative.");
            }
            if (!Double.isFinite(limit) || limit < 0) {
                throw new IllegalArgumentException("Ramp limit must be finite and nonnegative.");
            }
            if (!Double.isFinite(step) || step <= 0) {
                throw new IllegalArgumentException("Ramp step must be finite and positive.");
            }
            if (rewindTicks < 0) throw new IllegalArgumentException("Rewind ticks cannot be negative.");
            if (holdTicks < 0) throw new IllegalArgumentException("Hold ticks cannot be negative.");
        }
    }

    private Settings settings;
    private boolean engaged;
    private double speed;
    private int hold;

    public AutoWaspRamp(Settings settings) {
        configure(settings);
    }

    /** Changes tuning without discarding the ramp. */
    public void configure(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (engaged) speed = Math.min(speed, ceiling());
    }

    /** Starts from the current speed, never below the start speed. */
    public void engage(double currentSpeed) {
        engaged = true;
        hold = 0;
        double current = Double.isFinite(currentSpeed) ? Math.max(0, currentSpeed) : 0;
        speed = Math.min(Math.max(floor(), current), ceiling());
    }

    /** One tick along the curve, unless a recent setback is still holding the speed. */
    public void advance() {
        if (!engaged) return;
        if (hold > 0) {
            hold--;
            return;
        }
        speed = next(speed, settings.step(), settings.plateau(), ceiling());
    }

    /** After a real collision cost speed, continue the ramp from what was kept. */
    public void limitTo(double actual) {
        if (!engaged || !(actual >= 0)) return;
        speed = Math.max(floor(), Math.min(speed, actual));
    }

    public void disengage() { engaged = false; }

    /** A server setback: rewind {@code rewindTicks} of the curve, then hold that speed for {@code holdTicks}. */
    public void corrected() {
        if (!engaged) return;
        speed = rewind(speed, settings.step(), settings.plateau(), floor(), settings.rewindTicks());
        hold = settings.holdTicks();
    }

    public void reset() {
        engaged = false;
        speed = 0;
        hold = 0;
    }

    public boolean engaged() { return engaged; }
    /** The current ramp speed, or zero while disengaged. */
    public double speed() { return engaged ? speed : 0; }
    /** The fastest the ramp goes: its limit, or the curve's plateau when that is lower. */
    public double ceiling() { return Math.min(settings.plateau(), settings.limit()); }

    private double floor() { return Math.min(settings.start(), ceiling()); }

    /** The plateau-curve gain at {@code speed}: steep when slow, tapering to zero at {@code plateau}. */
    static double gain(double speed, double step, double plateau) {
        if (!(plateau > 0)) return 0;
        return step * Math.max(0, plateau - speed) / plateau;
    }

    /** One tick along the curve, never above {@code ceiling}. */
    static double next(double speed, double step, double plateau, double ceiling) {
        return Math.min(speed + gain(speed, step, plateau), ceiling);
    }

    /** {@code ticks} steps back down the curve, each losing what that step would have gained, never below {@code floor}. */
    static double rewind(double speed, double step, double plateau, double floor, int ticks) {
        for (int tick = 0; tick < ticks && speed > floor; tick++) {
            speed = Math.max(floor, speed - gain(speed, step, plateau));
        }
        return speed;
    }
}
