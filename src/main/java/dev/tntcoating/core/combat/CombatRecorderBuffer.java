/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Thread-safe receipt store for the combat recorder.
 *
 * <p>Receipts are created on the network thread and stamped on the client thread once vanilla has applied the
 * packet. The store keeps them in receipt order; {@link #drain} only releases a prefix of that order, so a
 * bundle stays in order even when its sub-packets are applied out of order. A receipt that never receives an
 * application stamp is released as unapplied after the caller's pending window (or on {@link #drainAll}),
 * never silently.</p>
 */
public final class CombatRecorderBuffer {
    /** Input fields known at receipt time; the buffer assigns sequence and bundle identity. */
    public record Receipt(
        long connectionId,
        long nanos,
        long clientTick,
        ObservationKind kind,
        int entityId,
        String uuid,
        Double x,
        Double y,
        Double z,
        JsonObject payload
    ) {
        public Receipt {
            if (kind == null) throw new IllegalArgumentException("kind");
        }
    }

    private record BundleSlot(long bundleId, int position) {}

    private static final class Pending {
        private CombatEventRecord record;
        private final Object packetKey;

        private Pending(CombatEventRecord record, Object packetKey) {
            this.record = record;
            this.packetKey = packetKey;
        }
    }

    private final int capacity;
    private final Deque<Pending> pending = new ArrayDeque<>();
    private final Map<Object, List<Pending>> byPacket = new IdentityHashMap<>();
    private final Map<Object, BundleSlot> bundleSlots = new IdentityHashMap<>();
    private final Map<Object, List<Object>> bundleMembers = new IdentityHashMap<>();
    private long nextReceiptSeq;
    private long nextBundleId;
    private long dropped;

    public CombatRecorderBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /**
     * Registers the sub-packet order of a received bundle. Called before Meteor posts its per-sub-packet
     * Receive events, so receipts can carry their bundle position.
     */
    public synchronized void registerBundle(Object bundleKey, List<?> subPackets) {
        if (bundleKey == null || subPackets == null || subPackets.isEmpty()) return;
        long bundleId = nextBundleId++;
        List<Object> members = new ArrayList<>(subPackets.size());
        for (int i = 0; i < subPackets.size(); i++) {
            Object subPacket = subPackets.get(i);
            if (subPacket == null) continue;
            bundleSlots.put(subPacket, new BundleSlot(bundleId, i));
            members.add(subPacket);
        }
        bundleMembers.put(bundleKey, members);
    }

    /** Drops bundle bookkeeping for sub-packets that produced no receipt. */
    public synchronized void clearBundle(Object bundleKey) {
        List<Object> members = bundleMembers.remove(bundleKey);
        if (members == null) return;
        for (Object subPacket : members) bundleSlots.remove(subPacket);
    }

    /** Records one receipt; returns {@code null} when the bounded store is full. */
    public synchronized CombatEventRecord receive(Receipt receipt, Object packetKey) {
        if (pending.size() >= capacity) {
            dropped++;
            return null;
        }
        BundleSlot slot = packetKey == null ? null : bundleSlots.remove(packetKey);
        CombatEventRecord record = new CombatEventRecord(
            receipt.connectionId(),
            nextSequence(),
            slot == null ? CombatEventRecord.NO_BUNDLE : slot.bundleId(),
            slot == null ? CombatEventRecord.NO_BUNDLE_POSITION : slot.position(),
            receipt.nanos(),
            receipt.clientTick(),
            receipt.kind(),
            false,
            CombatEventRecord.NO_REVISION,
            receipt.entityId(),
            receipt.uuid(),
            receipt.x(), receipt.y(), receipt.z(),
            receipt.payload());
        Pending entry = new Pending(record, packetKey);
        pending.addLast(entry);
        if (packetKey != null) {
            byPacket.computeIfAbsent(packetKey, ignored -> new ArrayList<>(1)).add(entry);
        }
        return record;
    }

    /** Stamps the oldest unstamped receipt for this packet identity. */
    public synchronized boolean stamp(Object packetKey, UnaryOperator<CombatEventRecord> update) {
        return stamp(packetKey, ignored -> true, update);
    }

    /** Stamps the oldest unstamped receipt for this packet identity that matches {@code match}. */
    public synchronized boolean stamp(Object packetKey, Predicate<CombatEventRecord> match,
                                      UnaryOperator<CombatEventRecord> update) {
        if (packetKey == null) return false;
        List<Pending> entries = byPacket.get(packetKey);
        if (entries == null) return false;
        for (int i = 0; i < entries.size(); i++) {
            Pending entry = entries.get(i);
            if (!entry.record.applied() && match.test(entry.record)) {
                entry.record = update.apply(entry.record);
                entries.remove(i);
                if (entries.isEmpty()) byPacket.remove(packetKey);
                return true;
            }
        }
        return false;
    }

    /** Stamps the oldest unstamped receipt of {@code kind} for the entity id. */
    public synchronized boolean stampByEntity(ObservationKind kind, int entityId,
                                              UnaryOperator<CombatEventRecord> update) {
        return stampFirst(record -> record.kind() == kind && record.entityId() == entityId, update);
    }

    /** Stamps the oldest unstamped receipt of {@code kind} at the given block position. */
    public synchronized boolean stampByPosition(ObservationKind kind, int x, int y, int z,
                                                UnaryOperator<CombatEventRecord> update) {
        return stampFirst(record -> record.kind() == kind
            && record.x() != null && record.y() != null && record.z() != null
            && record.x() == x && record.y() == y && record.z() == z, update);
    }

    /** Convenience predicate for block positions packed into double fields. */
    private boolean stampFirst(Predicate<CombatEventRecord> match, UnaryOperator<CombatEventRecord> update) {
        for (Pending entry : pending) {
            if (!entry.record.applied() && match.test(entry.record)) {
                entry.record = update.apply(entry.record);
                removeFromPacketIndex(entry);
                return true;
            }
        }
        return false;
    }

    /**
     * Releases the longest applied prefix of the receipt order. An unapplied head is released only once it is
     * older than {@code maxPendingTicks}, so a bundle cannot be split by an out-of-order application.
     */
    public synchronized List<CombatEventRecord> drain(long currentTick, int maxPendingTicks) {
        List<CombatEventRecord> released = new ArrayList<>();
        while (!pending.isEmpty()) {
            Pending head = pending.peekFirst();
            CombatEventRecord record = head.record;
            if (!record.applied() && currentTick - record.clientTick() < maxPendingTicks) break;
            pending.removeFirst();
            removeFromPacketIndex(head);
            released.add(record.applied() ? record : record.withUnappliedReason("no-application"));
        }
        return released;
    }

    /** Releases everything, marking unresolved receipts as closed. Used when recording stops. */
    public synchronized List<CombatEventRecord> drainAll() {
        return drainAll("recorder-closed");
    }

    /** Releases everything, labelling unresolved receipts with {@code reason}. */
    public synchronized List<CombatEventRecord> drainAll(String reason) {
        List<CombatEventRecord> released = new ArrayList<>(pending.size());
        while (!pending.isEmpty()) {
            Pending entry = pending.removeFirst();
            removeFromPacketIndex(entry);
            released.add(entry.record.applied()
                ? entry.record
                : entry.record.withUnappliedReason(reason));
        }
        return released;
    }

    /** Reserves the next receipt number; labels use the same counter so a trace stays globally ordered. */
    public synchronized long nextSequence() {
        return nextReceiptSeq++;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized long dropped() {
        return dropped;
    }

    private void removeFromPacketIndex(Pending entry) {
        if (entry.packetKey == null) return;
        List<Pending> entries = byPacket.get(entry.packetKey);
        if (entries == null) return;
        entries.remove(entry);
        if (entries.isEmpty()) byPacket.remove(entry.packetKey);
    }
}
