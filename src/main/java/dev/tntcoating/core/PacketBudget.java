package dev.tntcoating.core;

import java.util.Arrays;

/** Packets sent over the last second, kept as twenty one-tick buckets. */
public final class PacketBudget {
    private static final int WINDOW = 20;
    private final int[] buckets = new int[WINDOW];
    private boolean started;
    private int lastTick;
    private int total;

    /** Use and swing, plus any hotbar switches, an extra rotation packet and the two sneak-input packets of a container support. */
    public static int placementCost(int switchPackets, boolean extraRotation, boolean container) {
        return 2 + switchPackets + (extraRotation ? 1 : 0) + (container ? 2 : 0);
    }

    public void record(int tick, int packets) {
        advance(tick);
        buckets[Math.floorMod(tick, WINDOW)] += packets;
        total += packets;
    }

    public int perSecond(int tick) {
        advance(tick);
        return total;
    }

    public boolean allows(int tick, int cost, int limit) {
        return perSecond(tick) + cost <= limit;
    }

    public void reset() {
        Arrays.fill(buckets, 0);
        total = 0;
        started = false;
    }

    private void advance(int tick) {
        if (!started || tick < lastTick || tick - lastTick >= WINDOW) {
            reset();
            started = true;
            lastTick = tick;
            return;
        }
        while (lastTick < tick) {
            int bucket = Math.floorMod(++lastTick, WINDOW);
            total -= buckets[bucket];
            buckets[bucket] = 0;
        }
    }
}
