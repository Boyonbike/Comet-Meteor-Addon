package dev.tntcoating.core;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Stateful local follower. Planning is requested only after the committed route stops being usable. */
public final class HighwayNavigation {
    private static final double LOOKAHEAD = 8;
    private static final double ARRIVAL = .65;
    private static final int CLEAR_TICKS = 6;
    private static final int SETBACK_TICKS = 10;
    private List<Vec3> points = List.of();
    private int segment;
    private double progress;
    private int clearTicks;
    private int cooldown;
    private int lastTick = Integer.MIN_VALUE;

    /** A null target means there is no safe committed steering target for this tick. */
    public record Step(Vec3 target, double speedLimit, boolean needsPath, boolean followingPath) {}

    /** A valid commitment cannot be replaced merely because a fresh search prefers the other side. */
    public boolean commit(HighwayPathfinder.Path path) {
        if (!points.isEmpty() || cooldown > 0 || path == null || !path.found()
            || path.points().stream().anyMatch(point -> !finite(point))) return false;
        points = List.copyOf(path.points());
        segment = 0;
        progress = 0;
        clearTicks = 0;
        return true;
    }

    public List<Vec3> points() { return points; }

    /** Keep the chosen side after a correction; a newly obstructed path still fails closed. */
    public void setback() {
        cooldown = SETBACK_TICKS;
        clearTicks = 0;
    }

    public void reset() {
        discard();
        cooldown = 0;
        clearTicks = 0;
        lastTick = Integer.MIN_VALUE;
    }

    /** Call on every movement update; counters advance at most once for a given game tick. */
    public Step update(int tick, Vec3 position, AABB body, Vec3 velocity, boolean directClear,
                       double cruiseSpeed, FlightCorridor.Probe probe) {
        if (!finite(position) || !finite(velocity) || !Double.isFinite(cruiseSpeed) || cruiseSpeed < 0) {
            discard();
            return new Step(null, 0, false, false);
        }
        if (lastTick != tick) {
            lastTick = tick;
            if (cooldown > 0) cooldown--;
            clearTicks = directClear && cooldown == 0 ? clearTicks + 1 : 0;
        }
        if (!points.isEmpty() && clearTicks >= CLEAR_TICKS) discard();
        if (points.isEmpty()) return idle(directClear, cruiseSpeed);

        advance(position);
        Vec3 end = points.get(points.size() - 1);
        if (segment == points.size() - 2 && position.distanceTo(end) <= ARRIVAL) {
            discard();
            return idle(directClear, cruiseSpeed);
        }

        Vec3 segmentStart = points.get(segment);
        Vec3 from = segmentStart.add(points.get(segment + 1).subtract(segmentStart).scale(progress));
        if (!clear(body, from.subtract(position), probe)) {
            discard();
            return new Step(null, 0, !directClear && cooldown == 0, false);
        }
        AABB fromBody = body.move(from.subtract(position));
        double remaining = LOOKAHEAD;
        Vec3 target = points.get(segment + 1);
        double turn = 0;
        Vec3 previousDirection = velocity.lengthSqr() > .01 ? velocity.normalize() : null;
        for (int index = segment; index < points.size() - 1 && remaining > 1e-7; index++) {
            Vec3 waypoint = points.get(index + 1);
            Vec3 delta = waypoint.subtract(from);
            double distance = delta.length();
            Vec3 direction = distance > 1e-7 ? delta.scale(1 / distance) : Vec3.ZERO;
            if (previousDirection != null && distance > 1e-7) {
                turn = Math.max(turn, Math.acos(Math.clamp(previousDirection.dot(direction), -1, 1)));
            }
            Vec3 next = distance <= remaining ? waypoint : from.add(direction.scale(remaining));
            if (!clear(fromBody, next.subtract(from), probe)) {
                discard();
                return new Step(null, 0, !directClear && cooldown == 0, false);
            }
            target = next;
            remaining -= distance;
            fromBody = fromBody.move(next.subtract(from));
            from = next;
            if (distance > 1e-7) previousDirection = direction;
        }
        // A visible local shortcut is useful, but never cut through the inside of a blocked corner.
        if (!clear(body, target.subtract(position), probe)) target = points.get(segment + 1);
        if (!clear(body, target.subtract(position), probe)) {
            discard();
            return new Step(null, 0, !directClear && cooldown == 0, false);
        }
        double limit = cruiseSpeed / (1 + 2 * turn);
        if (cooldown > 0) limit = Math.min(limit, .5);
        return new Step(target, limit, false, true);
    }

    private Step idle(boolean directClear, double cruiseSpeed) {
        return new Step(null, directClear ? cruiseSpeed : 0, !directClear && cooldown == 0, false);
    }

    private void advance(Vec3 position) {
        while (segment < points.size() - 1) {
            Vec3 start = points.get(segment);
            Vec3 delta = points.get(segment + 1).subtract(start);
            double length = delta.length();
            double projection = length < 1e-7 ? 1 : position.subtract(start).dot(delta) / (length * length);
            Vec3 projected = start.add(delta.scale(Math.clamp(projection, 0, 1)));
            if (position.distanceTo(projected) <= 2) progress = Math.max(progress, Math.clamp(projection, 0, 1));
            // Keep the approach leg until the waypoint plane is crossed. Advancing merely
            // within arrival radius can force an unsafe shortcut across the inside of a corner.
            boolean reached = projection >= 1
                && position.distanceTo(start.add(delta.scale(projection))) <= 1.5;
            if (!reached || segment >= points.size() - 2) break;
            segment++;
            progress = 0;
        }
    }

    private static boolean clear(AABB body, Vec3 delta, FlightCorridor.Probe probe) {
        double distance = delta.length();
        FlightCorridor.Result result = FlightCorridor.inspect(body, delta, distance, probe);
        return result.reason() == FlightCorridor.Reason.CLEAR && result.clearDistance() + 1e-6 >= distance;
    }

    private void discard() {
        points = List.of();
        segment = 0;
        progress = 0;
    }

    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
