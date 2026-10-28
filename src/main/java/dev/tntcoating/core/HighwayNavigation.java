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
    private Vec3 failedWaypoint;
    private boolean lastCollision;
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

    public Vec3 nextWaypoint() {
        return points.isEmpty() ? null : points.get(segment + 1);
    }

    /** Collision failures are consumed once; unloading and normal route completion are not failures. */
    public Vec3 takeFailedWaypoint() {
        Vec3 failed = failedWaypoint;
        failedWaypoint = null;
        return failed;
    }

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
        failedWaypoint = null;
    }

    /** Call on every movement update; counters advance at most once for a given game tick. */
    public Step update(int tick, Vec3 position, AABB body, Vec3 velocity, boolean directClear,
                       double cruiseSpeed, FlightCorridor.Probe probe) {
        return update(tick, position, body, velocity, directClear, cruiseSpeed, 6, .05, probe);
    }

    /** Speed is blocks/second; turn rate is degrees/tick and braking is blocks/tick squared. */
    public Step update(int tick, Vec3 position, AABB body, Vec3 velocity, boolean directClear,
                       double cruiseSpeed, double turnRateDegrees, double braking, FlightCorridor.Probe probe) {
        if (!finite(position) || !finite(velocity) || !Double.isFinite(cruiseSpeed) || cruiseSpeed < 0
            || !Double.isFinite(turnRateDegrees) || turnRateDegrees <= 0
            || !Double.isFinite(braking) || braking <= 0) {
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
            invalidate(nextWaypoint());
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
                invalidate(waypoint);
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
            invalidate(nextWaypoint());
            return new Step(null, 0, !directClear && cooldown == 0, false);
        }
        double limit = Math.min(cruiseSpeed / (1 + 2 * turn), position.distanceTo(target) * 20);
        limit = Math.min(limit, flightLimit(position, body, velocity, target, cruiseSpeed,
            turnRateDegrees, braking, probe));
        if (cooldown > 0) limit = Math.min(limit, .5);
        return new Step(target, limit, false, true);
    }

    /** Follow the actual path slope; cruise altitude hold is too gentle for a one-block gap. */
    public static float steeringPitch(Vec3 position, Vec3 target) {
        Vec3 delta = target.subtract(position);
        return (float) Math.clamp(-Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))), -89, 89);
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

    private double flightLimit(Vec3 position, AABB body, Vec3 velocity, Vec3 target, double cruise,
                               double turnRateDegrees, double braking, FlightCorridor.Probe probe) {
        Vec3 approach = target.subtract(position);
        // Expanded swept bodies measure room for corrections, without allowing a corner shortcut.
        double margin = .15;
        if (clear(body.inflate(.25, 0, .25).expandTowards(0, .25, 0), approach, probe)) margin = .5;
        if (margin > .15 && clear(body.inflate(.75, 0, .75).expandTowards(0, .75, 0), approach, probe)) margin = 1.5;
        double limit = cruise * (margin == .15 ? .5 : margin == .5 ? .8 : 1);
        double omega = Math.toRadians(Math.min(90, turnRateDegrees));
        Vec3 incoming = points.get(segment + 1).subtract(points.get(segment)).normalize();
        if (velocity.lengthSqr() > .01) {
            double angle = Math.acos(Math.clamp(velocity.normalize().dot(incoming), -1, 1));
            if (angle > omega) {
                // During heading capture, reserve lateral room for the uncorrected velocity.
                limit = Math.min(limit, 20 * margin * omega / angle);
            }
        }
        double distance = position.distanceTo(points.get(segment + 1));
        for (int index = segment + 1; index < points.size() - 1 && distance <= 64; index++) {
            Vec3 outgoing = points.get(index + 1).subtract(points.get(index)).normalize();
            double angle = Math.acos(Math.clamp(incoming.dot(outgoing), -1, 1));
            if (angle > 1e-5) {
                // Radius is constrained by free lateral room. Brake before the bend, reserving
                // a fraction of a block plus this tick's movement in the quadratic budget.
                double cornerSpeed = margin * omega / Math.max(.01, Math.tan(angle * .5));
                double available = Math.max(0, distance - .35);
                double budget = Math.sqrt(cornerSpeed * cornerSpeed + braking * braking
                    + 2 * braking * available) - braking;
                limit = Math.min(limit, Math.max(1e-6, budget) * 20);
            }
            distance += points.get(index).distanceTo(points.get(index + 1));
            incoming = outgoing;
        }
        // The direct approach was swept above. Always retain enough braking distance
        // to stop inside that verified horizon, including this tick's movement.
        return Math.min(limit, FlightCorridor.safeSpeed(braking, approach.length()) * 20);
    }

    private void invalidate(Vec3 waypoint) {
        if (lastCollision) failedWaypoint = waypoint;
        discard();
    }

    private boolean clear(AABB body, Vec3 delta, FlightCorridor.Probe probe) {
        double distance = delta.length();
        FlightCorridor.Result result = FlightCorridor.inspect(body, delta, distance, probe);
        lastCollision = result.reason() == FlightCorridor.Reason.OBSTACLE;
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
