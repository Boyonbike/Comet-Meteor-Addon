/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-22, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

import net.minecraft.world.phys.Vec3;

/** Pursuit velocity shaping in blocks per tick: arrival braking, heading limits, speed caps and maneuvering. */
public final class AutoWaspSteering {
    /**
     * The most a vanilla server accepts per tick from one move packet: it rejects a move when
     * {@code distance² − serverVelocity² > 300}, so √300 ≈ 17.32 less a small margin.
     */
    public static final double SERVER_MOVE_LIMIT = 17;
    /** At or below this horizontal speed, steering turns at once for precise close-range control. */
    public static final double TURN_FREE_SPEED = .6;
    /** A heading change at least this large is a reversal: speed is shed along the old heading first. */
    public static final double REVERSAL_DEGREES = 135;
    /** Effective heading assistance never exceeds a right angle per tick. */
    public static final double MAX_TURN_RATE = 90;
    /** A heading change faster than this, in degrees per tick, counts as turning for maneuvering. */
    public static final double MANEUVER_TURN_DEGREES = 2;
    /** Maneuvering outlasts its trigger by this many ticks, so the cap cannot flicker. */
    public static final int MANEUVER_HOLD_TICKS = 10;
    private static final double MANEUVER_VERTICAL = .05;
    private static final double EPSILON = 1e-9;

    private AutoWaspSteering() {}

    /**
     * The speed towards a point {@code distance} away that can still stop there braking at {@code braking} per tick,
     * and never more than the distance itself, so the final tick lands on the point instead of overshooting it.
     */
    public static double arrivalSpeed(double distance, double braking) {
        if (!(distance > 0)) return 0;
        if (!(braking > 0) || !Double.isFinite(braking)) return distance;
        return Math.min(distance, Math.sqrt(2 * braking * distance));
    }

    /** Heading assistance at {@code speed}: the configured rate, rising to twice it at {@code top}. Zero disables. */
    public static double turnRate(double rate, double speed, double top) {
        if (!(rate > 0)) return 0;
        double ratio = top > 0 ? Math.min(1, Math.max(0, speed) / top) : 1;
        return Math.min(MAX_TURN_RATE, rate * (1 + ratio));
    }

    /**
     * Swings the horizontal heading of {@code previous} towards {@code desired} by at most {@code rateDegrees}, keeping
     * the desired horizontal speed and vertical velocity. Slow flight and a zero rate turn at once. A reversal sheds
     * {@code braking} of speed per tick along the old heading until slow enough to turn.
     */
    public static Vec3 limitTurn(Vec3 previous, Vec3 desired, double rateDegrees, double braking) {
        double speed = previous.horizontalDistance();
        double target = desired.horizontalDistance();
        if (!(rateDegrees > 0) || speed <= TURN_FREE_SPEED || target < EPSILON) return desired;
        double heading = Math.atan2(previous.z, previous.x);
        double difference = Math.IEEEremainder(Math.atan2(desired.z, desired.x) - heading, 2 * Math.PI);
        if (Math.abs(difference) >= Math.toRadians(REVERSAL_DEGREES)) {
            double slower = Math.max(0, speed - Math.max(0, braking));
            return new Vec3(previous.x / speed * slower, desired.y, previous.z / speed * slower);
        }
        double turn = Math.min(Math.abs(difference), Math.toRadians(Math.min(MAX_TURN_RATE, rateDegrees)));
        double angle = heading + Math.copySign(turn, difference);
        return new Vec3(Math.cos(angle) * target, desired.y, Math.sin(angle) * target);
    }

    /** {@code velocity} shortened, keeping its direction, so its total length is at most {@code max}. */
    public static Vec3 capLength(Vec3 velocity, double max) {
        double length = velocity.length();
        if (!(max >= 0)) return Vec3.ZERO;
        if (length <= max || length < EPSILON) return velocity;
        return velocity.scale(max / length);
    }

    /** {@code velocity} with its horizontal part shortened to at most {@code max}. */
    public static Vec3 capHorizontal(Vec3 velocity, double max) {
        double horizontal = velocity.horizontalDistance();
        if (!(max >= 0)) return new Vec3(0, velocity.y, 0);
        if (horizontal <= max || horizontal < EPSILON) return velocity;
        double scale = max / horizontal;
        return new Vec3(velocity.x * scale, velocity.y, velocity.z * scale);
    }

    /** Moving horizontally, climbing or descending and turning at once, held for a short grace once that stops. */
    public static final class Maneuver {
        private int ticks;

        /** One step per movement tick; true while maneuvering. */
        public boolean update(Vec3 previous, Vec3 desired) {
            boolean turning = false;
            if (previous.horizontalDistance() > EPSILON && desired.horizontalDistance() > EPSILON) {
                double gap = Math.IEEEremainder(Math.atan2(desired.z, desired.x)
                    - Math.atan2(previous.z, previous.x), 2 * Math.PI);
                turning = Math.abs(gap) > Math.toRadians(MANEUVER_TURN_DEGREES);
            }
            if (turning && Math.abs(desired.y) > MANEUVER_VERTICAL) ticks = MANEUVER_HOLD_TICKS;
            else if (ticks > 0) ticks--;
            return ticks > 0;
        }

        public void reset() { ticks = 0; }
    }
}
