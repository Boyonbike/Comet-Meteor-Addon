package dev.tntcoating.core;

/** Speedmine's pause, air-mining and tool-slot ownership rules, without the Meteor module. */
public final class SpeedminePolicy {
    private SpeedminePolicy() {}

    /** Mining pauses while another inventory action, or an item use, owns the player. */
    public static boolean shouldPauseMining(boolean multitask, boolean usingItem, boolean eating, int priority) {
        return (!multitask && (usingItem || eating))
            || (priority > SpeedmineInventory.Priority.NORMAL && (!multitask || priority != SpeedmineInventory.Priority.EATING));
    }

    /** Bedrock's vanilla break may continue through manual or automatic eating. */
    public static boolean canMineBedrockWhileEating(boolean enabled, boolean eating, int priority) {
        return enabled && eating && !shouldPauseMining(true, true, true, priority);
    }

    /** Air mining is claimed only while airborne, allowed and slow enough. */
    public static boolean canClaimAir(boolean onGround, boolean gliding, boolean allowGliding, boolean creative,
                                      boolean spectator, double speed, double maximum) {
        return !onGround && (!gliding || allowGliding) && !creative && !spectator && speed <= maximum;
    }

    /** A held tool is only restored while Speedmine still owns the server slot. */
    public static boolean shouldRestoreSlot(int held, int server) {
        return held >= 0 && held == server;
    }
}
