/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only Phase 0 trace recorder. It turns receipts into JSON Lines and never sends anything.
 *
 * <p>Pure by design: the Minecraft-facing half lives in {@code core/combat/mc/CombatRecorderHost} and only
 * translates packets/events into the calls below.</p>
 */
public final class CombatRecorder {
    public static final int DEFAULT_CAPACITY = 8_192;
    /** Receipts unresolved for this many client ticks are released as unapplied. */
    public static final int MAX_PENDING_TICKS = 20;

    /** Fields known at receipt time; the recorder adds connection, sequence, tick and nanoTime. */
    public record Receipt(
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

    private final CombatRecorderRuntime runtime;
    private final CombatTraceSink log;
    private final CombatRecorderBuffer buffer;
    private final AtomicLong appliedRevision = new AtomicLong();
    private long lastDropped;
    private boolean closed;

    public CombatRecorder(CombatRecorderRuntime runtime, CombatTraceSink log) {
        this(runtime, log, DEFAULT_CAPACITY);
    }

    public CombatRecorder(CombatRecorderRuntime runtime, CombatTraceSink log, int capacity) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.log = Objects.requireNonNull(log, "log");
        this.buffer = new CombatRecorderBuffer(capacity);
    }

    /** Called on the receiving thread before Meteor posts its per-sub-packet Receive events. */
    public void registerBundle(Object bundleKey, List<?> subPackets) {
        buffer.registerBundle(bundleKey, subPackets);
    }

    /** Drops bundle bookkeeping for sub-packets that produced no receipt. */
    public void clearBundle(Object bundleKey) {
        buffer.clearBundle(bundleKey);
    }

    /** Called on the receiving thread. Returns the unapplied receipt, or null if the buffer is full. */
    public CombatEventRecord receive(Receipt receipt, Object packetKey) {
        Objects.requireNonNull(receipt, "receipt");
        return buffer.receive(new CombatRecorderBuffer.Receipt(
            runtime.connectionId(),
            System.nanoTime(),
            runtime.clientTick(),
            receipt.kind(),
            receipt.entityId(),
            receipt.uuid(),
            receipt.x(),
            receipt.y(),
            receipt.z(),
            receipt.payload()), packetKey);
    }

    /**
     * Stamps one packet's receipt after vanilla applied it, on the client thread. Empty resolved values keep
     * whatever the receipt already carried; {@code extra} is merged into the payload.
     */
    public boolean markApplied(Object packetKey, int entityId, String uuid,
                               Double x, Double y, Double z, JsonObject extra) {
        long revision = appliedRevision.incrementAndGet();
        return buffer.stamp(packetKey,
            record -> applied(record, revision, entityId, uuid, x, y, z, extra));
    }

    /** Stamps the oldest receipt of a kind for an entity, for Meteor events that do not expose the packet. */
    public boolean markAppliedByEntity(ObservationKind kind, int entityId,
                                       String uuid, Double x, Double y, Double z, JsonObject extra) {
        long revision = appliedRevision.incrementAndGet();
        return buffer.stampByEntity(kind, entityId,
            record -> applied(record, revision, entityId, uuid, x, y, z, extra));
    }

    /** Stamps the oldest receipt of a kind at a block cell, for per-position block update events. */
    public boolean markAppliedByPosition(ObservationKind kind, int blockX, int blockY, int blockZ,
                                         Double x, Double y, Double z, JsonObject extra) {
        long revision = appliedRevision.incrementAndGet();
        return buffer.stampByPosition(kind, blockX, blockY, blockZ,
            record -> applied(record, revision, CombatEventRecord.NO_ENTITY, null, x, y, z, extra));
    }

    /** Drains ordered receipts and reports buffer overflow. Called once per client tick. */
    public void tick() {
        if (closed) return;
        write(buffer.drain(runtime.clientTick(), MAX_PENDING_TICKS));
        long dropped = buffer.dropped();
        if (dropped > lastDropped) {
            lastDropped = dropped;
            writeLabel("recorder-overflow dropped=" + dropped);
        }
    }

    /** Writes a free-text marker line immediately. */
    public void label(String text) {
        Objects.requireNonNull(text, "text");
        writeLabel(text);
    }

    /** Records a connection change, flushing anything still pending from the previous connection first. */
    public void session(String event) {
        if (closed) return;
        write(buffer.drainAll("connection-change"));
        JsonObject payload = new JsonObject();
        payload.addProperty("event", event);
        CombatEventRecord record = new CombatEventRecord(
            runtime.connectionId(),
            buffer.nextSequence(),
            CombatEventRecord.NO_BUNDLE,
            CombatEventRecord.NO_BUNDLE_POSITION,
            System.nanoTime(),
            runtime.clientTick(),
            ObservationKind.CONNECTION_CHANGE,
            true,
            appliedRevision.incrementAndGet(),
            CombatEventRecord.NO_ENTITY,
            null, null, null, null,
            payload);
        log.append(CombatTraceCodec.encodeEvent(record));
    }

    /** Writes every buffered receipt, applying whatever is still unresolved as unapplied. */
    public void flush() {
        write(buffer.drainAll());
    }

    /** Ends recording: flushes the buffer and closes the trace file. */
    public void close() {
        if (closed) return;
        closed = true;
        flush();
        log.close();
    }

    public long appliedRevision() {
        return appliedRevision.get();
    }

    public long dropped() {
        return buffer.dropped();
    }

    public int pendingCount() {
        return buffer.pendingCount();
    }

    private void write(List<CombatEventRecord> records) {
        for (CombatEventRecord record : records) {
            log.append(CombatTraceCodec.encodeEvent(record));
        }
    }

    private void writeLabel(String text) {
        CombatLabelRecord record = new CombatLabelRecord(
            runtime.connectionId(),
            buffer.nextSequence(),
            System.nanoTime(),
            runtime.clientTick(),
            text);
        log.append(CombatTraceCodec.encodeLabel(record));
    }

    private static CombatEventRecord applied(CombatEventRecord record, long revision,
                                             int entityId, String uuid,
                                             Double x, Double y, Double z, JsonObject extra) {
        JsonObject payload = record.payload();
        if (extra != null) {
            for (Map.Entry<String, JsonElement> entry : extra.entrySet()) {
                payload.add(entry.getKey(), entry.getValue());
            }
        }
        return record.withApplied(
            revision,
            entityId == CombatEventRecord.NO_ENTITY ? record.entityId() : entityId,
            uuid != null ? uuid : record.uuid(),
            x != null ? x : record.x(),
            y != null ? y : record.y(),
            z != null ? z : record.z(),
            payload);
    }
}
