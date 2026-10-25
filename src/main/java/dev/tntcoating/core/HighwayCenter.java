package dev.tntcoating.core;

import java.util.Objects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Finds the lateral center of the free highway cross-section around a position. */
public final class HighwayCenter {
    private static final double EPSILON = 1e-7;

    private HighwayCenter() {
    }

    public record Config(double maxOffset, double step) {
        public Config {
            if (!Double.isFinite(maxOffset) || maxOffset < 0 || maxOffset > 16) {
                throw new IllegalArgumentException("Center search offset must be between 0 and 16.");
            }
            if (!Double.isFinite(step) || step < .1 || step > 2) {
                throw new IllegalArgumentException("Center search step must be between 0.1 and 2.");
            }
        }
    }

    /**
     * Returns the offset from {@code position} to the middle of the clearest free run.
     * Zero means the position is already centered. A negative offset moves opposite
     * {@code perpendicular}.
     */
    public static double offset(AABB body, Vec3 position, Vec3 perpendicular, double targetY,
                                Config config, FlightCorridor.Probe probe) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(perpendicular, "perpendicular");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(probe, "probe");
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
            || !Double.isFinite(targetY) || !Double.isFinite(perpendicular.x) || !Double.isFinite(perpendicular.z)) {
            throw new IllegalArgumentException("Center search inputs must be finite.");
        }

        double dy = targetY - body.minY;
        double runStart = Double.NaN;
        double bestCenter = 0;
        double bestDistance = Double.POSITIVE_INFINITY;

        // Walk one extra step past the limit so a run ending at maxOffset is considered.
        for (double offset = -config.maxOffset(); offset <= config.maxOffset() + EPSILON; offset += config.step()) {
            Vec3 at = position.add(perpendicular.scale(offset));
            AABB shifted = body.move(at.x - position.x, dy, at.z - position.z);
            boolean clear = FlightCorridor.inspectStart(shifted, probe).reason() == FlightCorridor.Reason.CLEAR;
            if (clear) {
                if (Double.isNaN(runStart)) runStart = offset;
            } else if (!Double.isNaN(runStart)) {
                bestCenter = choose(bestCenter, bestDistance, runStart, offset - config.step());
                bestDistance = Math.min(bestDistance, Math.abs((runStart + offset - config.step()) * .5));
                runStart = Double.NaN;
            }
        }
        if (!Double.isNaN(runStart)) {
            double center = (runStart + config.maxOffset()) * .5;
            if (Math.abs(center) < bestDistance) bestCenter = center;
        }
        return bestCenter;
    }

    private static double choose(double bestCenter, double bestDistance, double start, double end) {
        double center = (start + end) * .5;
        return Math.abs(center) < bestDistance ? center : bestCenter;
    }
}
