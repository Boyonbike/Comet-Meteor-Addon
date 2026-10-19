package dev.tntcoating.core;

/** Shared guards for setting values and movement maths that must never propagate NaN or infinity. */
public final class Finite {
    private Finite() {}

    /** Clamps a finite value; non-finite input returns the caller's explicit fallback. */
    public static double clamp(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : fallback;
    }

    public static void requireNonnegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative.");
        }
    }
}
