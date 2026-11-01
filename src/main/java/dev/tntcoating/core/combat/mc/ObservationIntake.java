/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.CombatSession;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.Vec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.syncher.SynchedEntityData;

/**
 * Network-thread receipt recording and client-thread application for combat observations.
 *
 * <p>The netty side records only immutable facts: connection identity, monotonic receipt order, bundle
 * position, nanoTime and the raw packet fields. It never resolves an entity and never creates an
 * {@link Observation}; a packet is not applied when the reader sees it. The after-application hooks on the
 * client thread then consume the receipt, reserve the applied-state revision (one per bundle) and build the
 * observation from the already-applied world.
 *
 * <p>T1 reality: health and absorption both travel as entity metadata; {@code ClientboundSetHealthPacket}
 * carries no absorption, so it can never fabricate a coherent health snapshot and only a non-positive value
 * becomes a death marker (audit §4i, §6 residual). Ids are resolved through the accessor mixins only.
 */
public final class ObservationIntake implements ObservationListener {
    public static final int DEFAULT_CAPACITY = 4096;
    private static final int MAX_PENDING_RECEIPTS = 8192;

    private final CombatSession session;
    private final CombatClient client;
    private final EntityDataIds dataIds;
    private final int capacity;

    private final ArrayDeque<Observation> observations = new ArrayDeque<>();
    private final Map<Connection, Long> connectionIds = new IdentityHashMap<>();
    private final AtomicLong nextConnectionId = new AtomicLong(1);
    private final Map<Packet<?>, Receipt> pending = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Packet<?>, Integer> bundleIds = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<Integer, Long> bundleRevisions = new ConcurrentHashMap<>();
    private final AtomicLong nextReceiptSequence = new AtomicLong();
    private final AtomicInteger nextBundleId = new AtomicInteger(1);
    private final AtomicLong nextRevision = new AtomicLong();

    private volatile long currentConnectionId;
    private volatile boolean overflowed;
    private volatile long lastAppliedRevision;
    private String lastDimension = "";
    private boolean dimensionKnown;
    private int clientTick = -1;
    private volatile IntConsumer blockAckListener = sequence -> {
    };

    public ObservationIntake(CombatSession session, CombatClient client, EntityDataIds dataIds) {
        this(session, client, dataIds, DEFAULT_CAPACITY);
    }

    public ObservationIntake(CombatSession session, CombatClient client, EntityDataIds dataIds,
                             int capacity) {
        this.session = Objects.requireNonNull(session, "session");
        this.client = Objects.requireNonNull(client, "client");
        this.dataIds = Objects.requireNonNull(dataIds, "dataIds");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** Production intake resolving the private metadata ids through the accessor mixins. */
    public static ObservationIntake production(CombatSession session, CombatClient client) {
        return new ObservationIntake(session, client, EntityDataIds.production());
    }

    // ---- netty thread: immutable receipts only --------------------------------------------------

    @Override
    public void beforePacketRead(Connection connection, Packet<?> packet) {
        if (connection == null || packet == null) return;
        if (connection.getReceiving() != PacketFlow.CLIENTBOUND) return;
        long connectionId = connectionIds.computeIfAbsent(connection,
            ignored -> nextConnectionId.getAndIncrement());
        currentConnectionId = connectionId;
        if (packet instanceof ClientboundBundlePacket bundle) {
            int bundleId = nextBundleId.getAndIncrement();
            bundleIds.put(packet, bundleId);
            // A bundle applies as one unit but its members are handled later on the client thread, after
            // afterPacketRead. Reserve the revision now so a same-bundle explosion and health metadata stay
            // causally equal even though the receipts carry it explicitly.
            long revision = nextRevision.incrementAndGet();
            bundleRevisions.put(bundleId, revision);
            int position = 0;
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                record(connectionId, bundleId, position++, revision, sub);
            }
            return;
        }
        record(connectionId, -1, 0, -1L, packet);
    }

    @Override
    public void afterPacketRead(Connection connection, Packet<?> packet) {
        if (packet == null) return;
        Integer bundleId = bundleIds.remove(packet);
        if (bundleId != null) bundleRevisions.remove(bundleId);
    }

    private void record(long connectionId, int bundleId, int bundlePosition, long revision,
                        Packet<?> packet) {
        Raw raw = raw(packet);
        if (raw == null) return;
        Receipt receipt = new Receipt(nextReceiptSequence.getAndIncrement(), connectionId, bundleId,
            bundlePosition, System.nanoTime(), raw, revision);
        pending.put(packet, receipt);
        if (pending.size() > MAX_PENDING_RECEIPTS) {
            overflowed = true;
            pending.clear();
        }
    }

    private Raw raw(Packet<?> packet) {
        if (packet instanceof ClientboundSetHealthPacket health) {
            return new RawHealth(health.getHealth(), health.getFood(), health.getSaturation());
        }
        if (packet instanceof ClientboundSetEntityDataPacket data) {
            List<SynchedEntityData.DataValue<?>> fields = new ArrayList<>(2);
            for (SynchedEntityData.DataValue<?> value : data.packedItems()) {
                if (value.id() == dataIds.health() || value.id() == dataIds.absorption()) {
                    fields.add(value);
                }
            }
            if (fields.isEmpty()) return null;
            return new RawEntityData(data.id(), List.copyOf(fields));
        }
        if (packet instanceof ClientboundExplodePacket explosion) {
            Vec center = new Vec(explosion.center().x, explosion.center().y, explosion.center().z);
            return new RawExplosion(center, explosion.radius());
        }
        if (packet instanceof ClientboundPlayerPositionPacket) {
            return new RawSetback();
        }
        if (packet instanceof ClientboundRespawnPacket respawn) {
            return new RawRespawn(respawn.commonPlayerSpawnInfo().dimension().identifier().toString());
        }
        if (packet instanceof ClientboundBlockChangedAckPacket ack) {
            return new RawBlockAck(ack.sequence());
        }
        if (packet instanceof ClientboundEntityEventPacket event) {
            return new RawEntityEvent(event.getEventId() & 0xFF);
        }
        return null;
    }

    // ---- client thread: after-application hooks -------------------------------------------------

    @Override
    public void afterSetHealth(ClientboundSetHealthPacket packet) {
        applyRecorded(packet);
    }

    @Override
    public void afterSetEntityData(ClientboundSetEntityDataPacket packet) {
        applyRecorded(packet);
    }

    @Override
    public void afterExplosion(ClientboundExplodePacket packet) {
        applyRecorded(packet);
    }

    @Override
    public void afterPlayerPosition(ClientboundPlayerPositionPacket packet) {
        applyRecorded(packet);
    }

    @Override
    public void afterRespawn(ClientboundRespawnPacket packet) {
        applyRecorded(packet);
    }

    @Override
    public void afterBlockChangedAck(ClientboundBlockChangedAckPacket packet) {
        applyRecorded(packet);
    }

    @Override
    public void afterEntityEvent(ClientboundEntityEventPacket packet) {
        if (!client.onClientThread() || packet == null) return;
        Receipt receipt = pending.remove(packet);
        if (receipt == null) return;
        EntityKey resolved = null;
        if (client.level() != null) {
            var entity = packet.getEntity(client.level());
            if (entity != null) resolved = new EntityKey(session.connection(), entity.getId(), entity.getUUID());
        }
        apply(receipt, resolved);
    }

    private void applyRecorded(Packet<?> packet) {
        if (!client.onClientThread() || packet == null) return;
        Receipt receipt = pending.remove(packet);
        if (receipt == null) return;
        apply(receipt, null);
    }

    /** Applies one receipt: stamps the revision, resolves identity against the applied world, enqueues. */
    void apply(Receipt receipt, EntityKey resolvedEntity) {
        if (receipt == null || !client.onClientThread()) return;
        if (receipt.connectionId != currentConnectionId) return;
        ConnectionKey conn = session.connection();
        if (conn == null) return;
        long revision = receipt.revision >= 0 ? receipt.revision : revisionFor(receipt.bundleId);
        lastAppliedRevision = Math.max(lastAppliedRevision, revision);
        EntityKey self = client.selfKey();
        switch (receipt.raw) {
            case RawHealth health -> {
                // T1 sends no absorption on this packet; use it only as a death marker.
                if (health.health() <= 0.0f && self != null) {
                    enqueue(new Observation(conn, receipt.sequence, revision, receipt.nanos, clientTick,
                        ObservationKind.DEATH, self, null, null, null, new ObservationPayload.Death()));
                }
            }
            case RawEntityData data -> {
                EntityKey entity = client.entity(data.entityId());
                if (entity == null) return;
                boolean healthKnown = false;
                boolean absorptionKnown = false;
                double healthValue = 0.0;
                double absorptionValue = 0.0;
                for (SynchedEntityData.DataValue<?> value : data.values()) {
                    if (value.id() == dataIds.health() && value.value() instanceof Float health) {
                        healthKnown = true;
                        healthValue = health;
                    } else if (value.id() == dataIds.absorption()
                        && value.value() instanceof Float absorption) {
                        absorptionKnown = true;
                        absorptionValue = absorption;
                    }
                }
                if (!healthKnown && !absorptionKnown) return;
                enqueue(new Observation(conn, receipt.sequence, revision, receipt.nanos, clientTick,
                    ObservationKind.ENTITY_DATA, entity, null, null, null,
                    new ObservationPayload.EntityData(healthKnown, healthValue, absorptionKnown,
                        absorptionValue, client.entityType(entity))));
            }
            case RawExplosion explosion -> {
                EntityKey crystal = client.crystalAt(explosion.center(), 1.0);
                enqueue(new Observation(conn, receipt.sequence, revision, receipt.nanos, clientTick,
                    ObservationKind.EXPLOSION, crystal, null, explosion.center(), null,
                    new ObservationPayload.Explosion(explosion.radius())));
            }
            case RawSetback ignored -> {
                if (self == null) return;
                enqueue(new Observation(conn, receipt.sequence, revision, receipt.nanos, clientTick,
                    ObservationKind.SETBACK, self, null, null, null, new ObservationPayload.Setback()));
            }
            case RawRespawn respawn -> {
                boolean changed = dimensionKnown && !lastDimension.equals(respawn.dimension());
                lastDimension = respawn.dimension();
                dimensionKnown = true;
                if (self == null) return;
                enqueue(new Observation(conn, receipt.sequence, revision, receipt.nanos, clientTick,
                    ObservationKind.RESPAWN, self, null, null, null,
                    new ObservationPayload.Respawn(respawn.dimension(), changed)));
            }
            case RawBlockAck ack -> blockAckListener.accept(ack.sequence());
            case RawEntityEvent event -> applyEntityEvent(receipt, revision, conn, self, resolvedEntity,
                event.eventId());
        }
    }

    private void applyEntityEvent(Receipt receipt, long revision, ConnectionKey conn, EntityKey self,
                                  EntityKey resolved, int eventId) {
        if (resolved == null) return;
        if (eventId == 3) {
            if (!resolved.equals(self)) return;
            enqueue(new Observation(conn, receipt.sequence, revision, receipt.nanos, clientTick,
                ObservationKind.DEATH, self, null, null, null, new ObservationPayload.Death()));
            return;
        }
        if (eventId == 35) {
            enqueue(new Observation(conn, receipt.sequence, revision, receipt.nanos, clientTick,
                ObservationKind.TOTEM_POP, resolved, null, null, null,
                new ObservationPayload.TotemPop()));
        }
    }

    private long revisionFor(int bundleId) {
        if (bundleId < 0) return nextRevision.incrementAndGet();
        return bundleRevisions.computeIfAbsent(bundleId, ignored -> nextRevision.incrementAndGet());
    }

    private void enqueue(Observation observation) {
        synchronized (observations) {
            if (observations.size() >= capacity) {
                overflowed = true;
                return;
            }
            observations.addLast(observation);
        }
    }

    /** Test seam: enqueue a fully formed observation as if a hook had produced it. */
    void enqueueForTest(Observation observation) {
        enqueue(Objects.requireNonNull(observation, "observation"));
    }

    // ---- runtime-facing surface -----------------------------------------------------------------

    /** Copy in receipt order, cleared atomically. Client thread only. */
    public List<Observation> drain() {
        synchronized (observations) {
            List<Observation> drained = List.copyOf(observations);
            observations.clear();
            return drained;
        }
    }

    public int queued() {
        synchronized (observations) {
            return observations.size();
        }
    }

    public long appliedRevision() {
        return lastAppliedRevision;
    }

    public boolean overflowed() {
        return overflowed;
    }

    /** Reconciliation is a hold release, not a permanent latch. */
    public void clearOverflow() {
        overflowed = false;
    }

    public void setClientTick(int clientTick) {
        this.clientTick = clientTick;
    }

    public long currentConnectionId() {
        return currentConnectionId;
    }

    public void connectionClosed() {
        pending.clear();
        bundleIds.clear();
        bundleRevisions.clear();
        lastDimension = "";
        dimensionKnown = false;
    }

    /** Runtime hook: receives the prediction sequence of an applied block-changed ack. */
    public void setBlockAckListener(IntConsumer listener) {
        this.blockAckListener = listener == null ? sequence -> {
        } : listener;
    }

    // ---- deterministic tests seams --------------------------------------------------------------

    Receipt record(long connectionId, int bundleId, int bundlePosition, Raw raw) {
        return record(connectionId, bundleId, bundlePosition, -1L, raw);
    }

    Receipt record(long connectionId, int bundleId, int bundlePosition, long revision, Raw raw) {
        useConnection(connectionId);
        return new Receipt(nextReceiptSequence.getAndIncrement(), connectionId, bundleId, bundlePosition,
            System.nanoTime(), raw, revision);
    }

    void useConnection(long connectionId) {
        currentConnectionId = connectionId;
    }

    record Receipt(long sequence, long connectionId, int bundleId, int bundlePosition, long nanos,
                   Raw raw, long revision) {
    }

    sealed interface Raw permits RawHealth, RawEntityData, RawExplosion, RawSetback, RawRespawn,
        RawBlockAck, RawEntityEvent {
    }

    record RawHealth(float health, int food, float saturation) implements Raw {
    }

    record RawEntityData(int entityId, List<SynchedEntityData.DataValue<?>> values) implements Raw {
        RawEntityData {
            values = List.copyOf(values);
        }
    }

    record RawExplosion(Vec center, float radius) implements Raw {
    }

    record RawSetback() implements Raw {
    }

    record RawRespawn(String dimension) implements Raw {
    }

    record RawBlockAck(int sequence) implements Raw {
    }

    record RawEntityEvent(int eventId) implements Raw {
    }
}
