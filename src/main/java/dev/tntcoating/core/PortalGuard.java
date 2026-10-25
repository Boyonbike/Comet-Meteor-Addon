/*
 * Original Comet implementation by Boyonabike.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core;

/** Pure decisions used by the anti-portal module, kept free of Minecraft types for unit testing. */
public final class PortalGuard {
    private PortalGuard() {
    }

    /** Whether a position inside the given portal types should be blocked by the configured settings. */
    public static boolean shouldBlock(boolean inNetherPortal, boolean inEndPortal, boolean blockNether, boolean blockEnd) {
        return inNetherPortal && blockNether || inEndPortal && blockEnd;
    }

    /**
     * Whether the last outbound position outside a portal is close enough to still be the server's
     * position. A normal movement packet moves well under one block, so a much farther target means
     * the anchor is stale (a teleport or a missed reset) and should not be sent.
     */
    public static boolean anchorUsable(double distanceSq, double maxDistance) {
        if (!(maxDistance > 0.0) || Double.isNaN(distanceSq)) return false;
        return distanceSq <= maxDistance * maxDistance;
    }
}
