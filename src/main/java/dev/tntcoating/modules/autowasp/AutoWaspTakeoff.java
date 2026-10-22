/*
 * Part of Comet's Auto Wasp port; see AutoWasp.java and THIRD_PARTY_NOTICES.md for DEVILS provenance.
 * Written for Comet by Boyonabike on 2026-10-22, independent of Adaptive Elytra Fly's classes.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules.autowasp;

/**
 * When to jump and when to open the elytra while pursuit is not gliding. Like a second press of the Jump key, an open
 * happens near the top of a jump or while falling. Another open waits until a pause has passed since the last one, so
 * a refused open, which the client only learns of a round trip later, is not repeated every few ticks; a glide that
 * ends long after its open can reopen at once. In liquid the pause is shorter, since a refused open there costs more.
 */
public final class AutoWaspTakeoff {
    public enum Step { WAIT, JUMP, OPEN }

    /** Vertical speed near the top of a jump, about 1.2 blocks up. */
    static final double OPEN_BELOW = 0.1;
    /** Ticks from one open attempt to the next in the air. */
    public static final int REOPEN_TICKS = 10;
    /** Ticks from one open attempt to the next in water or lava. */
    public static final int LIQUID_REOPEN_TICKS = 2;
    private static final long NEVER = Long.MIN_VALUE;

    private long lastOpen = NEVER;

    /** Called once per client tick while an elytra is worn but the player is not gliding. */
    public Step next(long tick, boolean onGround, boolean fallFlying, double verticalSpeed, boolean inLiquid) {
        if (fallFlying) return Step.WAIT;
        if (onGround) return Step.JUMP;
        if (!inLiquid && verticalSpeed > OPEN_BELOW) return Step.WAIT;
        if (lastOpen != NEVER && tick - lastOpen < (inLiquid ? LIQUID_REOPEN_TICKS : REOPEN_TICKS)) return Step.WAIT;
        lastOpen = tick;
        return Step.OPEN;
    }

    /** The next open may happen at once. */
    public void reset() {
        lastOpen = NEVER;
    }
}
