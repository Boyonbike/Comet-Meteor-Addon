package dev.tntcoating.core;

/**
 * Takes off from flat ground for Baritone's elytra flight. Baritone opens the elytra only once the player falls faster
 * than 0.377 blocks per tick, which a jump from flat ground never reaches, so without a drop it waits forever. This jumps
 * and opens the elytra near the top of the jump, as a double tap of the jump key does; Baritone fires the first firework.
 */
public final class ElytraTakeoff {
    public enum Step { WAIT, JUMP, OPEN, COVERED, GAVE_UP }

    /** Vertical speed near the top of a jump, about 1.2 blocks up. */
    static final double OPEN_BELOW = 0.1;
    /**
     * Ground time before jumping again after being in the air. It also covers Baritone's landing, which stays active on
     * the ground until the player stops sliding, so a landing is never mistaken for a failed take-off.
     */
    public static final int RETRY_TICKS = 60;
    public static final int MAX_JUMPS = 3;

    private int jumps;
    private int groundTicks;
    private boolean airborne;
    private boolean opened;

    /** Called once per tick while Baritone's elytra process is active and Baritone isn't taking off by itself. */
    public Step next(boolean onGround, boolean fallFlying, double verticalSpeed, boolean clear) {
        if (fallFlying || !onGround) {
            airborne = true;
            groundTicks = 0;
            // Like the vanilla key press, one attempt per jump: a refused open is not repeated until the next landing.
            if (fallFlying || opened || verticalSpeed > OPEN_BELOW) return Step.WAIT;
            opened = true;
            return Step.OPEN;
        }
        opened = false;
        groundTicks++;
        if (airborne && groundTicks < RETRY_TICKS) return Step.WAIT;
        if (jumps >= MAX_JUMPS) return Step.GAVE_UP;
        if (!clear) return Step.COVERED;
        jumps++;
        return Step.JUMP;
    }

    /** @param airborne true when the flight starts in the air, so a later landing waits before jumping */
    public void reset(boolean airborne) {
        jumps = 0;
        groundTicks = 0;
        opened = false;
        this.airborne = airborne;
    }
}
