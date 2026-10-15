package dev.tntcoating.core;

/** Fixed user rules shared by scanning and the final interaction checks. */
public final class CoatingRules {
    private CoatingRules() {}

    public static boolean surface(boolean tnt, boolean fluid, boolean replaceable, boolean collision,
                                  float resistance, float hardness) {
        // Vanilla TNT power is 4. Even the strongest ray starts below 4 * 1.3;
        // each block subtracts (resistance + .3) * .3 before it can be destroyed.
        return !tnt && !fluid && !replaceable && collision && hardness >= 0
            && Float.isFinite(resistance) && resistance >= 0 && (resistance + .3f) * .3f < 4 * 1.3f;
    }

    /** Tunnel direction applies to coating/scaffold, not the separately validated jump support. */
    public static boolean tunnelAllows(boolean tunnelEnabled, boolean underfoot, boolean behindFacing) {
        return !tunnelEnabled || underfoot || behindFacing;
    }

    public static boolean heightAllowed(int targetY, int feetY, boolean touchesWall) {
        return (targetY != feetY && targetY != feetY + 1) || touchesWall;
    }

}
