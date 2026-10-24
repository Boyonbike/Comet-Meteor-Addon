/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-24, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

import net.minecraft.world.phys.Vec3;

/**
 * Keeps Auto Wasp's live steering point a minimum horizontal distance from the target.
 * The aim itself is never changed: outside the standoff the same instance is returned,
 * and inside it the hold point keeps the aim's height.
 */
public final class AutoWaspStandoff {
    private static final double EPSILON = 1.0E-4;

    private AutoWaspStandoff() {}

    /**
     * Returns {@code aim} outside the standoff, otherwise the point on a target-centred
     * ring of radius {@code minDistance} along the bearing from the target to the player,
     * at the aim's height. Null inputs, non-finite or non-positive distances and a
     * missing bearing return {@code aim}, so a standoff of 0 behaves exactly as before.
     */
    public static Vec3 holdPoint(Vec3 aim, Vec3 targetPos, Vec3 playerPos, double minDistance) {
        if (aim == null || targetPos == null || playerPos == null) return aim;
        if (!Double.isFinite(minDistance) || minDistance <= 0) return aim;

        double dx = playerPos.x - targetPos.x;
        double dz = playerPos.z - targetPos.z;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal >= minDistance || horizontal < EPSILON) return aim;

        double scale = minDistance / horizontal;
        return new Vec3(targetPos.x + dx * scale, aim.y, targetPos.z + dz * scale);
    }
}
