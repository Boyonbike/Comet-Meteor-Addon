/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-22, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

import net.minecraft.world.phys.Vec3;

/**
 * Sorts server position corrections during pursuit. A setback only rewinds the speed ramp; a correction far away is a
 * teleport (a command, a portal plugin) that ends the current route; and a second setback straight back to the spot
 * the last one used means the server rejects every move from there, so pursuit glides with vanilla physics for a few
 * ticks until it accepts one.
 */
public final class AutoWaspSetbacks {
    public enum Kind { SETBACK, PINNED, TELEPORT }

    /** Corrections further than this from the last reported position are teleports. */
    public static final double TELEPORT_DISTANCE = 64;
    /** A repeat setback to the same spot within this many ticks pins the flight. */
    public static final int PINNED_WINDOW_TICKS = 40;
    private static final double SAME_SPOT_SQR = .01;

    private Vec3 lastTarget;
    private long lastTick;
    private int pinnedTicks;

    /** {@code from} is where pursuit last reported itself, or null when unknown. */
    public Kind classify(long tick, Vec3 target, Vec3 from) {
        boolean teleport = from == null || target.distanceToSqr(from) > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
        boolean pinned = !teleport && lastTarget != null && tick - lastTick <= PINNED_WINDOW_TICKS
            && target.distanceToSqr(lastTarget) < SAME_SPOT_SQR;
        lastTarget = teleport ? null : target;
        lastTick = tick;
        return teleport ? Kind.TELEPORT : pinned ? Kind.PINNED : Kind.SETBACK;
    }

    /** Glide with vanilla physics for this many ticks. */
    public void pin(int ticks) {
        pinnedTicks = Math.max(0, ticks);
    }

    /** Once per client tick. */
    public void tick() {
        if (pinnedTicks > 0) pinnedTicks--;
    }

    public boolean pinned() { return pinnedTicks > 0; }

    public void reset() {
        lastTarget = null;
        lastTick = 0;
        pinnedTicks = 0;
    }
}
