package dev.tntcoating.core;

import java.util.Objects;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Picks a lateral or vertical detour that keeps a highway flight inside its allowed band. */
public final class HighwayDetour {
    private static final int MAX_VERTICAL_LEVELS = 8;
    private static final double EPSILON = 1e-7;

    public record Config(double maxOffsetDegrees, double stepDegrees,
                         double minY, double maxY, double verticalStep,
                         double probeDistance, double minClearDistance) {
        public Config {
            requireFinite(maxOffsetDegrees, "Maximum detour angle");
            requireFinite(stepDegrees, "Detour step");
            requireFinite(minY, "Minimum Y");
            requireFinite(maxY, "Maximum Y");
            requireFinite(verticalStep, "Vertical step");
            requireFinite(probeDistance, "Probe distance");
            requireFinite(minClearDistance, "Minimum clear distance");
            if (maxOffsetDegrees < 0 || maxOffsetDegrees > 90) {
                throw new IllegalArgumentException("Maximum detour angle must be in [0, 90].");
            }
            if (stepDegrees <= 0 || stepDegrees > 45) {
                throw new IllegalArgumentException("Detour step must be in (0, 45].");
            }
            if (minY > maxY) throw new IllegalArgumentException("Minimum Y cannot exceed maximum Y.");
            if (verticalStep <= 0) throw new IllegalArgumentException("Vertical step must be positive.");
            if (probeDistance <= 0 || probeDistance > FlightCorridor.MAX_DISTANCE) {
                throw new IllegalArgumentException("Probe distance must be within the corridor's maximum.");
            }
            if (minClearDistance < 0 || minClearDistance > FlightCorridor.MAX_DISTANCE) {
                throw new IllegalArgumentException("Minimum clear distance must be within the corridor's maximum.");
            }
        }
    }

    public enum Reason { CLEAR, DETOUR, BLOCKED, CHUNKS, INVALID }

    public record Choice(float yawOffsetDegrees, double targetY, double clearDistance, Reason reason) {}

    private HighwayDetour() {}

    public static Choice choose(AABB body, double baseYaw, double preferredY, Config config,
                                FlightCorridor.Probe probe) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(probe, "probe");
        requireFinite(baseYaw, "Base yaw");
        requireFinite(preferredY, "Preferred Y");

        double preferred = clamp(preferredY, config.minY(), config.maxY());
        double required = Math.max(config.probeDistance(), config.minClearDistance());
        FlightCorridor.Result base = probeAt(body, preferred, baseYaw, config.probeDistance(), probe);
        if (base.reason() == FlightCorridor.Reason.CHUNKS || base.reason() == FlightCorridor.Reason.INVALID) {
            return new Choice(0, preferred, base.clearDistance(), toReason(base.reason()));
        }
        if (base.reason() == FlightCorridor.Reason.CLEAR && base.clearDistance() + EPSILON >= required) {
            return new Choice(0, preferred, base.clearDistance(), Reason.CLEAR);
        }

        // Lateral first: it is cheaper and keeps the preferred altitude.
        int steps = (int) Math.floor(config.maxOffsetDegrees() / config.stepDegrees() + EPSILON);
        for (int step = 1; step <= steps; step++) {
            double offset = step * config.stepDegrees();
            Choice choice = lateral(body, baseYaw, preferred, offset, required, config, probe);
            if (choice != null) return choice;
            choice = lateral(body, baseYaw, preferred, -offset, required, config, probe);
            if (choice != null) return choice;
        }

        // Then the nearest Y levels inside the configured band.
        double range = Math.max(preferred - config.minY(), config.maxY() - preferred);
        int levels = Math.min(MAX_VERTICAL_LEVELS, (int) Math.floor(range / config.verticalStep() + EPSILON));
        for (int level = 1; level <= levels; level++) {
            double distance = level * config.verticalStep();
            Choice choice = vertical(body, baseYaw, preferred + distance, required, config, probe);
            if (choice != null) return choice;
            choice = vertical(body, baseYaw, preferred - distance, required, config, probe);
            if (choice != null) return choice;
        }

        return new Choice(0, preferred, 0, Reason.BLOCKED);
    }

    public static boolean clearAt(AABB body, double targetY, double yaw, double probeDistance,
                                  FlightCorridor.Probe probe) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(probe, "probe");
        requireFinite(targetY, "Target Y");
        requireFinite(yaw, "Yaw");
        requireFinite(probeDistance, "Probe distance");
        if (probeDistance <= 0 || probeDistance > FlightCorridor.MAX_DISTANCE) {
            throw new IllegalArgumentException("Probe distance must be within the corridor's maximum.");
        }
        FlightCorridor.Result result = probeAt(body, targetY, yaw, probeDistance, probe);
        return result.reason() == FlightCorridor.Reason.CLEAR && result.clearDistance() + EPSILON >= probeDistance;
    }

    private static Choice lateral(AABB body, double baseYaw, double targetY, double offset, double required,
                                  Config config, FlightCorridor.Probe probe) {
        FlightCorridor.Result result = probeAt(body, targetY, baseYaw + offset, config.probeDistance(), probe);
        if (result.reason() != FlightCorridor.Reason.CLEAR || result.clearDistance() + EPSILON < required) return null;
        return new Choice((float) offset, targetY, result.clearDistance(), Reason.DETOUR);
    }

    private static Choice vertical(AABB body, double baseYaw, double targetY, double required,
                                   Config config, FlightCorridor.Probe probe) {
        if (targetY < config.minY() - EPSILON || targetY > config.maxY() + EPSILON) return null;

        FlightCorridor.Result base = probeAt(body, targetY, baseYaw, config.probeDistance(), probe);
        if (base.reason() == FlightCorridor.Reason.CLEAR && base.clearDistance() + EPSILON >= required) {
            return new Choice(0, targetY, base.clearDistance(), Reason.DETOUR);
        }

        int steps = (int) Math.floor(config.maxOffsetDegrees() / config.stepDegrees() + EPSILON);
        for (int step = 1; step <= steps; step++) {
            double offset = step * config.stepDegrees();
            FlightCorridor.Result result = probeAt(body, targetY, baseYaw + offset, config.probeDistance(), probe);
            if (result.reason() == FlightCorridor.Reason.CLEAR && result.clearDistance() + EPSILON >= required) {
                return new Choice((float) offset, targetY, result.clearDistance(), Reason.DETOUR);
            }
            result = probeAt(body, targetY, baseYaw - offset, config.probeDistance(), probe);
            if (result.reason() == FlightCorridor.Reason.CLEAR && result.clearDistance() + EPSILON >= required) {
                return new Choice((float) -offset, targetY, result.clearDistance(), Reason.DETOUR);
            }
        }
        return null;
    }

    private static FlightCorridor.Result probeAt(AABB body, double targetY, double yaw, double distance,
                                                 FlightCorridor.Probe probe) {
        double dy = targetY - body.minY;
        if (Math.abs(dy) > EPSILON) {
            FlightCorridor.Result vertical = FlightCorridor.inspect(body, new Vec3(0, Math.signum(dy), 0),
                Math.abs(dy), probe);
            if (vertical.reason() != FlightCorridor.Reason.CLEAR
                || vertical.clearDistance() + EPSILON < Math.abs(dy)) return vertical;
        }
        AABB shifted = body.move(0, dy, 0);
        return FlightCorridor.inspect(shifted, direction(yaw), distance, probe);
    }

    private static Vec3 direction(double yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    private static Reason toReason(FlightCorridor.Reason reason) {
        return switch (reason) {
            case CLEAR -> Reason.CLEAR;
            case CHUNKS -> Reason.CHUNKS;
            case OBSTACLE -> Reason.BLOCKED;
            case INVALID -> Reason.INVALID;
        };
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite.");
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}