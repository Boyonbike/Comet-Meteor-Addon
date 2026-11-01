/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.HashMap;
import java.util.Map;

/**
 * One rolling packet budget shared by every combat dispatch path, including cleanup and defence intents.
 * Pure tick based: callers pass the server tick, so there is no clock dependency and no second budget.
 *
 * <p>The window contains acquisitions whose tick is greater than {@code tick - windowTicks} and at most
 * {@code tick}. Ticks must be non-decreasing in normal use; a backwards tick is evaluated as a window
 * ending at that tick and never revives older buckets.
 */
public final class PacketLedger {
    public static final int DEFAULT_WINDOW_TICKS = 20;
    public static final int CONSERVATIVE_CAPACITY = 60;
    public static final int ANARCHY_CAPACITY = 100;

    private final int capacity;
    private final int windowTicks;
    private final Map<Integer, Integer> packetsByTick = new HashMap<>();

    public PacketLedger(int capacity) {
        this(capacity, DEFAULT_WINDOW_TICKS);
    }

    public PacketLedger(int capacity, int windowTicks) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (windowTicks <= 0) throw new IllegalArgumentException("windowTicks must be positive");
        this.capacity = capacity;
        this.windowTicks = windowTicks;
    }

    public static PacketLedger conservative() {
        return new PacketLedger(CONSERVATIVE_CAPACITY, DEFAULT_WINDOW_TICKS);
    }

    public static PacketLedger anarchy() {
        return new PacketLedger(ANARCHY_CAPACITY, DEFAULT_WINDOW_TICKS);
    }

    /**
     * Atomically reserves {@code packets} in the window ending at {@code tick}; returns {@code false} if
     * that would exceed capacity. Zero is a successful no-op; negative values are rejected.
     */
    public boolean tryAcquire(int tick, int packets) {
        if (tick < 0 || packets < 0) return false;
        if (packets == 0) return true;
        int used = used(tick);
        if ((long) used + packets > capacity) return false;
        packetsByTick.merge(tick, packets, Integer::sum);
        return true;
    }

    /** Rolls back a reservation that never became a sent packet. Clamps at zero. */
    public void release(int tick, int packets) {
        if (tick < 0 || packets <= 0) return;
        Integer current = packetsByTick.get(tick);
        if (current == null) return;
        int left = current - packets;
        if (left > 0) packetsByTick.put(tick, left);
        else packetsByTick.remove(tick);
    }

    /** Packets currently counted in the window ending at {@code tick}. */
    public int used(int tick) {
        if (tick < 0) return 0;
        packetsByTick.keySet().removeIf(t -> t <= tick - windowTicks);
        int total = 0;
        for (Map.Entry<Integer, Integer> entry : packetsByTick.entrySet()) {
            int at = entry.getKey();
            if (at <= tick && at > tick - windowTicks) total += entry.getValue();
        }
        return total;
    }

    public int capacity() {
        return capacity;
    }

    public int windowTicks() {
        return windowTicks;
    }
}
