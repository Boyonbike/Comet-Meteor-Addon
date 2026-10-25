package dev.tntcoating.core;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Immutable horizontal line followed by a highway flight. */
public final class HighwayCourse {
    private final Vec3 origin;
    private final Vec3 destination;
    private final Vec3 direction;
    private final Vec3 perpendicular;
    private final double length;
    private final double targetY;
    private final double arrivalDistance;

    public HighwayCourse(Vec3 origin, Vec3 destination, double targetY, double arrivalDistance) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.destination = Objects.requireNonNull(destination, "destination");
        if (!finite(origin.x) || !finite(origin.z) || !finite(destination.x) || !finite(destination.z)
            || !finite(targetY) || !finite(arrivalDistance)) {
            throw new IllegalArgumentException("Highway course coordinates must be finite.");
        }

        Vec3 flat = new Vec3(destination.x - origin.x, 0, destination.z - origin.z);
        length = flat.length();
        if (!finite(length) || length < 1) {
            throw new IllegalArgumentException("Highway course must be at least one block long.");
        }
        if (arrivalDistance < 0) throw new IllegalArgumentException("Arrival distance cannot be negative.");

        direction = flat.scale(1 / length);
        perpendicular = new Vec3(-direction.z, 0, direction.x);
        this.targetY = targetY;
        this.arrivalDistance = arrivalDistance;
    }

    /** An aim point and the yaw that faces it from the current position. */
    public record Waypoint(Vec3 aim, float yaw) {}

    /** The next point on the line, with a gentle pull back towards it after a lateral detour. */
    public Vec3 aimPoint(Vec3 position, double lookahead, double lateralGain) {
        Objects.requireNonNull(position, "position");
        if (!finite(position.x) || !finite(position.z) || !finite(lookahead) || !finite(lateralGain)) {
            throw new IllegalArgumentException("Aim inputs must be finite.");
        }
        double along = clamp(along(position), 0, length);
        double lateral = lateral(position);
        double forward = Math.min(length, along + Math.max(0, lookahead));
        return new Vec3(
            origin.x + direction.x * forward - perpendicular.x * lateral * lateralGain,
            targetY,
            origin.z + direction.z * forward - perpendicular.z * lateral * lateralGain
        );
    }

    /** The aim point plus the yaw from {@code position} to that point. */
    public Waypoint waypoint(Vec3 position, double lookahead, double lateralGain) {
        Vec3 aim = aimPoint(position, lookahead, lateralGain);
        return new Waypoint(aim, yaw(position, aim));
    }

    public double remaining(Vec3 position) {
        Objects.requireNonNull(position, "position");
        return Math.max(0, length - along(position));
    }

    public boolean arrived(Vec3 position) {
        Objects.requireNonNull(position, "position");
        return Math.hypot(destination.x - position.x, destination.z - position.z) <= arrivalDistance;
    }

    public double distanceToDestination(Vec3 position) {
        Objects.requireNonNull(position, "position");
        return Math.hypot(destination.x-position.x,destination.z-position.z);
    }

    /** Minecraft yaw in degrees: 0 is +Z, -90 is +X, 90 is -X. */
    public static float yaw(Vec3 from, Vec3 to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!finite(from.x) || !finite(from.z) || !finite(to.x) || !finite(to.z)) {
            throw new IllegalArgumentException("Yaw inputs must be finite.");
        }
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        if (dx == 0 && dz == 0) return 0;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public Vec3 origin() { return origin; }
    public Vec3 destination() { return destination; }
    public Vec3 direction() { return direction; }
    public Vec3 perpendicular() { return perpendicular; }
    public double length() { return length; }
    public double targetY() { return targetY; }
    public double arrivalDistance() { return arrivalDistance; }

    private double along(Vec3 position) {
        double dx = position.x - origin.x;
        double dz = position.z - origin.z;
        return dx * direction.x + dz * direction.z;
    }

    private double lateral(Vec3 position) {
        double dx = position.x - origin.x;
        double dz = position.z - origin.z;
        return dx * perpendicular.x + dz * perpendicular.z;
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
