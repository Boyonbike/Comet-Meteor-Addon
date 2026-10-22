/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-22, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

import net.minecraft.world.phys.Vec3;

/**
 * The target's velocity from how far it moved each tick. Another player's own delta movement is only whatever the
 * server last sent, so it is often stale or zero; positions are what the client actually shows. Where the target is
 * shown lags where the server has it by the interpolation of other players' movement plus the trip to the server,
 * and a move this client sends arrives a trip later again, so prediction leads by the ping plus that interpolation.
 */
public final class AutoWaspTargetTracker {
    /** Share of each new sample in the smoothed velocity. */
    static final double SMOOTHING = .5;
    /** A larger jump in one tick is a teleport, not movement. */
    static final double TELEPORT_DISTANCE = 16;
    /** Ticks Minecraft spreads each received position of another player over. */
    static final int INTERPOLATION_TICKS = 3;
    /** Prediction never leads further than this many ticks of movement, so a turning target is not overshot. */
    static final double MAX_LEAD_TICKS = 8;

    private int id = Integer.MIN_VALUE;
    private Vec3 last;
    private Vec3 velocity = Vec3.ZERO;

    /** Once per tick with the target's entity id and shown position. */
    public void sample(int id, Vec3 position) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)
            || !Double.isFinite(position.z)) {
            reset();
            return;
        }
        if (id != this.id || last == null) {
            this.id = id;
            last = position;
            velocity = Vec3.ZERO;
            return;
        }
        Vec3 moved = position.subtract(last);
        last = position;
        if (moved.lengthSqr() > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            velocity = Vec3.ZERO;
            return;
        }
        velocity = velocity.add(moved.subtract(velocity).scale(SMOOTHING));
    }

    /** Smoothed movement per tick, zero until two samples of the same target. */
    public Vec3 velocity() { return velocity; }

    /** Where the target will be on the server when a move sent now arrives. */
    public Vec3 predict(Vec3 position, int pingMs) {
        return position.add(velocity.scale(leadTicks(pingMs)));
    }

    /** The ping, a round trip in milliseconds, plus other players' interpolation, in ticks. */
    public static double leadTicks(int pingMs) {
        return Math.min(MAX_LEAD_TICKS, INTERPOLATION_TICKS + Math.max(0, pingMs) / 50.0);
    }

    public void reset() {
        id = Integer.MIN_VALUE;
        last = null;
        velocity = Vec3.ZERO;
    }
}
