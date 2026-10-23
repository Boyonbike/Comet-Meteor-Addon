/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-22, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

import net.minecraft.world.phys.Vec3;

/**
 * What a vanilla server, and so 6b6t, accepts from an elytra flight it does not simulate, and VolytraFly's speed curves
 * within it, in blocks per tick. ServerGamePacketListenerImpl rejects a move packet when
 * {@code distance² − serverVelocity² > 300 × packetsThisTick}, measured from where the player was at the start of the
 * server tick. One packet per tick may therefore cover √300 ≈ 17.3 blocks; when lag or ping jitter delivers two in the
 * same server tick, the second is checked as twice the distance against twice the allowance, which fails above
 * √150 ≈ 12.25 (245 b/s). Slowing down and turning are never checked.
 */
public final class AutoWaspSpeed {
    /** The fastest total speed never set back for distance, even when two packets arrive together: 240 b/s. */
    public static final double BUNCHED_SAFE = 12;
    /** VolytraFly's horizontal boost starts here at once: 60 b/s. */
    public static final double HORIZONTAL_START = 3;
    /** ... and tapers towards this top, 300 b/s, set back only when packets bunch. */
    public static final double HORIZONTAL_TOP = 15;
    /** VolytraFly's vertical boost starts at 30 b/s ... */
    public static final double VERTICAL_START = 1.5;
    /** ... and climbs at this step at rest towards its 60 b/s top. */
    public static final double VERTICAL_STEP = .5;
    public static final double VERTICAL_TOP = 3;
    /** Settings are in blocks per second; flight works in blocks per tick. */
    public static final double TICKS_PER_SECOND = 20;
    /** Vertical speed above this (1 b/s) is a climb or descent for the 3D limit. */
    static final double CLIMB_SPEED = .05;
    /** The 3D limit outlasts a climb or descent by this many ticks, so it cannot flicker. */
    public static final int LIMIT_3D_HOLD_TICKS = 10;
    private static final double EPSILON = 1e-9;

    private AutoWaspSpeed() {}

    /** Whether a total speed passes the server's check when two move packets reach it in one tick. */
    public static boolean acceptedWhenBunched(double speed) {
        return (2 * speed) * (2 * speed) <= 2 * 300;
    }

    /** {@code velocity} shortened, keeping its direction, so its total length is at most {@code max}. */
    public static Vec3 capLength(Vec3 velocity, double max) {
        if (!(max >= 0)) return Vec3.ZERO;
        double length = velocity.length();
        if (length <= max || length < EPSILON) return velocity;
        return velocity.scale(max / length);
    }

    /**
     * Holds both boost ramps within a 3D speed cap, the vertical one scaled to its share and the horizontal one taking
     * what remains, so once the cap lifts boost speeds up again from it along its curve instead of snapping back.
     */
    public static void holdRampsTo(AutoWaspRamp horizontal, AutoWaspRamp vertical, double cap) {
        double ramps = Math.hypot(horizontal.speed(), vertical.speed());
        if (!(cap >= 0) || ramps <= cap) return;
        vertical.limitTo(vertical.speed() * cap / ramps);
        // The vertical ramp never drops below its start speed, so horizontal takes what remains.
        horizontal.limitTo(Math.sqrt(Math.max(0, cap * cap - vertical.speed() * vertical.speed())));
    }

    /** {@code velocity} with its horizontal part shortened to at most {@code max}. */
    public static Vec3 capHorizontal(Vec3 velocity, double max) {
        if (!(max >= 0)) return new Vec3(0, velocity.y, 0);
        double horizontal = velocity.horizontalDistance();
        if (horizontal <= max || horizontal < EPSILON) return velocity;
        double scale = max / horizontal;
        return new Vec3(velocity.x * scale, velocity.y, velocity.z * scale);
    }

    /** Whether the 3D limit applies: while climbing or descending, and for a short grace once that stops. */
    public static final class Limit3d {
        private int ticks;

        /** Once per movement tick with the vertical speed pursuit wants. */
        public boolean update(double verticalSpeed) {
            if (Math.abs(verticalSpeed) > CLIMB_SPEED) ticks = LIMIT_3D_HOLD_TICKS;
            else if (ticks > 0) ticks--;
            return ticks > 0;
        }

        public boolean active() { return ticks > 0; }

        public void reset() { ticks = 0; }
    }
}
