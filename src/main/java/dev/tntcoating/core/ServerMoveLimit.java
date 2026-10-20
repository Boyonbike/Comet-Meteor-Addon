package dev.tntcoating.core;

import net.minecraft.world.phys.Vec3;

/**
 * How far a gliding player may move before the server sets them back, in blocks per tick.
 *
 * <p>The server measures each move packet from where the player was at the start of its tick. Vanilla
 * (ServerGamePacketListenerImpl.handleMovePlayer) rejects a packet when {@code distance² − serverVelocity² >
 * 300 × packetsThisTick}; Paper, which 6b6t runs, uses {@code max(300, (10 × packetsThisTick)²)} instead. Either
 * way the count resets to one past five packets. One packet per tick allows √300 ≈ 17.32. When two or more of the
 * client's packets reach a Paper server in the same tick, through lag or ping jitter, each may cover only 10 blocks
 * from the tick's start per packet: anything over 200 b/s in total is set back, which 6b6t setback logs confirm (no
 * rejections between 80 and 200 b/s, many above). Reporting a half-step every tick doubles the packets, so two ticks
 * arriving together still allow 20 blocks per tick; three arriving together (six packets) fail at any speed.
 *
 * <p>The limits below keep a small margin under those.
 */
public final class ServerMoveLimit {
    /** One move packet per tick: 340 b/s. */
    public static final double SINGLE = 17;
    /** Two move packets per tick (split moves), within Paper's allowance for two bunched ticks: 390 b/s. */
    public static final double SPLIT = 19.5;
    /** Above this a client tick is split into two packets: Paper's per-packet limit when packets bunch. */
    public static final double SPLIT_ABOVE = 10;

    private ServerMoveLimit() {}

    public static double of(boolean splitMoves) {
        return splitMoves ? SPLIT : SINGLE;
    }

    /**
     * The halfway point to report before this tick's position, or null when one packet is enough or the last
     * reported position no longer matches this tick's movement (a server correction moved the player since).
     */
    public static Vec3 halfStep(Vec3 lastReported, Vec3 current, double tickMovement) {
        double distance = current.distanceTo(lastReported);
        if (distance <= SPLIT_ABOVE || Math.abs(distance - tickMovement) > .5) return null;
        return lastReported.add(current).scale(.5);
    }
}
