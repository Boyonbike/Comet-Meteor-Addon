package dev.tntcoating.core;

/**
 * How far a gliding player may move per tick before a vanilla server sets them back, in blocks per tick.
 *
 * <p>ServerGamePacketListenerImpl.handleMovePlayer measures each move packet from where the player was at the start of
 * the server tick and rejects it when {@code distance² − serverVelocity² > 300 × packetsThisTick} (the count resets to
 * one past five). One packet per tick allows √300 ≈ 17.32. When two of the client's packets reach the server in the
 * same tick, through lag or ping jitter, the second is checked as twice the distance against twice the allowance, which
 * fails above √150 ≈ 12.25 (245 b/s): that bunching, not speed itself, is the rubber-banding at 300 b/s. Sending a
 * half-step packet in every tick doubles the allowance, to √600 ≈ 24.49, and still allows 17.32 when two ticks bunch.
 *
 * <p>The limits below keep a small margin under those for the server's own glide velocity estimate.
 */
public final class ServerMoveLimit {
    /** One move packet per tick: 340 b/s. */
    public static final double SINGLE = 17;
    /** Two move packets per tick (split moves): 480 b/s. */
    public static final double SPLIT = 24;

    private ServerMoveLimit() {}

    public static double of(boolean splitMoves) {
        return splitMoves ? SPLIT : SINGLE;
    }
}
