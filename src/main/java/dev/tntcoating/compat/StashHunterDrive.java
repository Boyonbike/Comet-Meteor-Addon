package dev.tntcoating.compat;

import net.minecraft.world.phys.Vec3;

/**
 * Hand-off between Stash-Hunter, which is optional, and the stash-hunter-elytrafly module. Comet's Stash-Hunter mixins
 * call into this class, so it must not refer to Stash-Hunter's classes. Everything runs on the client thread.
 */
public final class StashHunterDrive {
    private static boolean enabled;
    private static boolean driving;
    private static Vec3 pending;

    private StashHunterDrive() {}

    /**
     * Called at the start of Stash-Hunter's movement step with the point it wants to move towards. True takes the
     * movement over, so Stash-Hunter neither starts Baritone's firework flight nor sets the player's velocity itself.
     */
    public static boolean take(Vec3 target) {
        if (!enabled) return false;
        pending = target;
        return true;
    }

    /** The point Stash-Hunter asked for since the last call, or null when it asked for none. */
    public static Vec3 poll() {
        Vec3 target = pending;
        pending = null;
        return target;
    }

    /** While true, Stash-Hunter's stuck detector skips its "Baritone dropped the path" check, since Baritone isn't flying. */
    public static boolean driving() {
        return enabled && driving;
    }

    public static void enable(boolean on) {
        enabled = on;
        driving = false;
        pending = null;
    }

    public static void setDriving(boolean on) {
        driving = on;
    }
}
