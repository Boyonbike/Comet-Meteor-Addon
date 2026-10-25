package dev.tntcoating.core.scaffold;

import java.util.function.BiPredicate;

/** Collision-support policy sampled along a move; the adapter supplies actual confirmed world geometry. */
public final class ScaffoldEdgeGuard {
    public record Movement(double x, double z) {}
    private ScaffoldEdgeGuard() {}
    public static Movement clip(double x, double z, BiPredicate<Double, Double> support) {
        double length = Math.hypot(x, z);
        if (!Double.isFinite(length) || length > 12.8) return new Movement(0, 0);
        if (length < 1e-8) return new Movement(x, z);
        int steps = Math.max(1, (int) Math.ceil(length / .05));
        double safe = 0;
        for (int step = 1; step <= steps; step++) {
            double fraction = (double) step / steps;
            if (!support.test(x * fraction, z * fraction)) break;
            safe = fraction;
        }
        return new Movement(x * safe, z * safe);
    }
}
