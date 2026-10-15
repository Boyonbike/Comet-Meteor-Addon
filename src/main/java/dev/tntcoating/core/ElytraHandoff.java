package dev.tntcoating.core;

/** Whether a trip Baritone is walking can be flown instead, and what to do once Baritone's elytra flight ends. */
public final class ElytraHandoff {
    private ElytraHandoff() {}

    public enum Refusal {
        /** Short enough to walk. */
        SHORT(true),
        /** Baritone ships no native elytra pathfinder for this CPU and operating system. */
        UNSUPPORTED(true),
        NO_ELYTRA(true),
        FIREWORKS(true),
        /** Mid-jump or falling; checked again once on the ground. */
        AIRBORNE(false),
        /** Something above the player, or standing in water or lava; checked again while Baritone walks. */
        COVERED(false);

        /** True when this trip is walked to the end; false when it is checked again while Baritone walks. */
        public final boolean settled;

        Refusal(boolean settled) {
            this.settled = settled;
        }
    }

    public enum End {
        /** Landed near the goal: Baritone walks the last stretch. */
        WALK_REST,
        /** Landed far from the goal, which Baritone does when fireworks or elytra durability run low. */
        LANDED_SHORT,
        /** Stopped while gliding, which only happens when the player cancels; the player has control. */
        STOPPED_MID_AIR,
        /** Stopped before ever leaving the ground. */
        STOPPED_ON_GROUND
    }

    /**
     * Null when the trip can be flown now.
     *
     * @param elytraDurability remaining durability of the best elytra Baritone could fly with (worn, or in the inventory when
     *                         Comet equips one); -1 when there is none
     * @param fireworks        fireworks Baritone can use and also counts when deciding to land; see the module for which slots
     */
    public static Refusal check(double distance, int minDistance, boolean supported, int elytraDurability, int minDurability,
                                int fireworks, int minFireworks, boolean fallFlying, boolean onGround, boolean clear) {
        if (distance < minDistance) return Refusal.SHORT;
        if (!supported) return Refusal.UNSUPPORTED;
        // Baritone lands for safety once durability is below its minimum or fireworks are at theirs, so taking off at
        // those levels would only land again straight away.
        if (elytraDurability < 0 || elytraDurability < minDurability) return Refusal.NO_ELYTRA;
        if (fireworks <= minFireworks) return Refusal.FIREWORKS;
        if (fallFlying) return null;
        if (!onGround) return Refusal.AIRBORNE;
        return clear ? null : Refusal.COVERED;
    }

    /**
     * Why Baritone's elytra process went inactive.
     *
     * @param glided whether the player glided at any point during this flight
     */
    public static End end(boolean fallFlying, boolean glided, double remaining, int minDistance) {
        if (fallFlying) return End.STOPPED_MID_AIR;
        if (!glided) return End.STOPPED_ON_GROUND;
        return remaining < minDistance ? End.WALK_REST : End.LANDED_SHORT;
    }

    public static double horizontalDistance(double x, double z, double targetX, double targetZ) {
        return Math.hypot(targetX - x, targetZ - z);
    }
}
