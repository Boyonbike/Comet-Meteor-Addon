/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact behaviour and the project's own Phase 0
 * contracts; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import dev.tntcoating.core.combat.CombatEventRecord;
import dev.tntcoating.core.combat.CombatRecorder;
import dev.tntcoating.core.combat.CombatRecorderRuntime;
import dev.tntcoating.core.combat.CombatTraceLog;
import dev.tntcoating.core.combat.CombatTraceSink;
import dev.tntcoating.core.combat.ObservationKind;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minecraft-facing half of the Phase 0 trace recorder.
 *
 * <p>Receipts are recorded from Meteor's network-thread {@code PacketEvent.Receive}; the application stamp
 * arrives from {@link ObservationHooks} on the client thread (or from Meteor's own after-application events
 * for entity add/remove and block updates). Nothing here sends: the recorder has no send path and this host
 * never asks one to exist.</p>
 */
public final class CombatRecorderHost implements ObservationListener, CombatRecorderRuntime {
    private static final int PAYLOAD_STRING_LIMIT = 256;

    private final Minecraft mc;
    private final CombatRecorder recorder;
    private final CombatTraceSink log;
    private final Map<Connection, Long> connectionIds = new IdentityHashMap<>();
    private final AtomicLong nextConnectionId = new AtomicLong(1);
    private volatile long currentConnectionId;
    private volatile long tick;
    private String lastDimension;
    private boolean stopped;

    private CombatRecorderHost(Minecraft mc, CombatTraceSink log) {
        this.mc = mc;
        this.log = log;
        this.recorder = new CombatRecorder(this, log);
    }

    /** Opens a trace and installs the shared hook layer. The caller must eventually call {@link #stop()}. */
    public static CombatRecorderHost start(Minecraft mc, Path tracesDirectory, long maxBytes) throws IOException {
        CombatTraceLog log = CombatTraceLog.open(tracesDirectory, maxBytes);
        return start(mc, log);
    }

    /**
     * Starts the same observation host against an in-memory sink, so the tester debug log reuses the
     * recorder's audited packet/observation pipeline without writing a trace file. The caller owns
     * {@code sink} and may keep reading it after {@link #stop()}.
     */
    public static CombatRecorderHost startMemory(Minecraft mc, CombatTraceSink sink) {
        if (sink == null) throw new IllegalArgumentException("sink");
        return start(mc, sink);
    }

    private static CombatRecorderHost start(Minecraft mc, CombatTraceSink log) {
        CombatRecorderHost host = new CombatRecorderHost(mc, log);
        ObservationHooks.install(host);
        MeteorClient.EVENT_BUS.subscribe(host);
        host.recorder.label("recorder-start");
        return host;
    }

    /** Stops recording, uninstalls the hooks and closes the trace file. */
    public void stop() {
        if (stopped) return;
        stopped = true;
        MeteorClient.EVENT_BUS.unsubscribe(this);
        ObservationHooks.uninstall(this);
        recorder.label("recorder-stop");
        recorder.close();
    }

    public void label(String text) {
        recorder.label(text);
    }

    public Path path() {
        return log.path();
    }

    public long writtenRecords() {
        return log.writtenRecords();
    }

    public long droppedRecords() {
        return log.droppedRecords();
    }

    public boolean failed() {
        return log.failure() != null;
    }

    @Override
    public long connectionId() {
        return currentConnectionId;
    }

    @Override
    public long clientTick() {
        return tick;
    }

    // ---------------------------------------------------------------- receive side (network thread)

    @Override
    public void beforePacketRead(Connection connection, Packet<?> packet) {
        // The in-process test server shares this classloader; its Connection objects must not
        // overwrite the client connection epoch.
        if (connection.getReceiving() != PacketFlow.CLIENTBOUND) return;
        assignConnection(connection);
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Object> subPackets = new ArrayList<>();
            for (Packet<? super ClientGamePacketListener> subPacket : bundle.subPackets()) {
                subPackets.add(subPacket);
            }
            recorder.registerBundle(packet, subPackets);
        }
    }

    @Override
    public void afterPacketRead(Connection connection, Packet<?> packet) {
        if (connection.getReceiving() != PacketFlow.CLIENTBOUND) return;
        recorder.clearBundle(packet);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacketReceive(PacketEvent.Receive event) {
        if (stopped) return;
        // The in-process server shares the event bus; only clientbound traffic belongs here.
        if (event.connection == null
            || event.connection.getReceiving() != PacketFlow.CLIENTBOUND) return;
        assignConnection(event.connection);
        Packet<?> packet = event.packet;

        if (packet instanceof ClientboundAddEntityPacket add) {
            JsonObject payload = payload("add_entity");
            payload.addProperty("entityType", id(BuiltInRegistries.ENTITY_TYPE.getKey(add.getType())));
            payload.addProperty("data", add.getData());
            payload.addProperty("xRot", add.getXRot());
            payload.addProperty("yRot", add.getYRot());
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.SPAWN, add.getId(),
                add.getUUID().toString(), add.getX(), add.getY(), add.getZ(), payload), packet);
        } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            for (int entityId : remove.getEntityIds()) {
                recorder.receive(new CombatRecorder.Receipt(ObservationKind.REMOVED, entityId,
                    null, null, null, null, payload("remove_entities")), packet);
            }
        } else if (packet instanceof ClientboundBlockUpdatePacket block) {
            recorder.receive(blockReceipt("block_update", block.getPos(), block.getBlockState()), packet);
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            section.runUpdates((pos, state) -> recorder.receive(blockReceipt("section_blocks_update", pos, state), packet));
        } else if (packet instanceof ClientboundBlockEventPacket blockEvent) {
            JsonObject payload = payload("block_event");
            payload.addProperty("block", id(BuiltInRegistries.BLOCK.getKey(blockEvent.getBlock())));
            payload.addProperty("b0", blockEvent.getB0());
            payload.addProperty("b1", blockEvent.getB1());
            payload.addProperty("blockEvent", true);
            payload.addProperty("pos", blockEvent.getPos().toShortString());
            recorder.receive(positioned(ObservationKind.BLOCK_UPDATE, blockEvent.getPos(), payload), packet);
        } else if (packet instanceof ClientboundBlockChangedAckPacket ack) {
            JsonObject payload = payload("block_changed_ack");
            payload.addProperty("blockChangedAck", true);
            payload.addProperty("sequence", ack.sequence());
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.BLOCK_UPDATE, -1,
                null, null, null, null, payload), packet);
        } else if (packet instanceof ClientboundExplodePacket explosion) {
            Vec3 center = explosion.center();
            JsonObject payload = payload("explode");
            payload.addProperty("radius", explosion.radius());
            payload.addProperty("blockCount", explosion.blockCount());
            payload.addProperty("hasPlayerKnockback", explosion.playerKnockback().isPresent());
            explosion.playerKnockback().ifPresent(knockback -> {
                payload.addProperty("knockbackX", knockback.x);
                payload.addProperty("knockbackY", knockback.y);
                payload.addProperty("knockbackZ", knockback.z);
            });
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.EXPLOSION, -1,
                null, center.x, center.y, center.z, payload), packet);
        } else if (packet instanceof ClientboundSetHealthPacket health) {
            JsonObject payload = payload("set_health");
            payload.addProperty("health", health.getHealth());
            payload.addProperty("food", health.getFood());
            payload.addProperty("saturation", health.getSaturation());
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.HEALTH, -1,
                null, null, null, null, payload), packet);
        } else if (packet instanceof ClientboundSetEntityDataPacket entityData) {
            JsonObject payload = payload("set_entity_data");
            JsonArray values = new JsonArray();
            for (SynchedEntityData.DataValue<?> value : entityData.packedItems()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", value.id());
                entry.addProperty("value", truncate(String.valueOf(value.value())));
                values.add(entry);
            }
            payload.add("values", values);
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.ENTITY_DATA, entityData.id(),
                null, null, null, null, payload), packet);
        } else if (packet instanceof ClientboundUpdateMobEffectPacket effect) {
            JsonObject payload = payload("update_mob_effect");
            payload.addProperty("effect", key(effect.getEffect()));
            payload.addProperty("amplifier", effect.getEffectAmplifier());
            payload.addProperty("durationTicks", effect.getEffectDurationTicks());
            payload.addProperty("visible", effect.isEffectVisible());
            payload.addProperty("ambient", effect.isEffectAmbient());
            payload.addProperty("showIcon", effect.effectShowsIcon());
            payload.addProperty("blend", effect.shouldBlend());
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.EFFECT, effect.getEntityId(),
                null, null, null, null, payload), packet);
        } else if (packet instanceof ClientboundRemoveMobEffectPacket effect) {
            JsonObject payload = payload("remove_mob_effect");
            payload.addProperty("effect", key(effect.effect()));
            payload.addProperty("removed", true);
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.EFFECT, effect.entityId(),
                null, null, null, null, payload), packet);
        } else if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            JsonObject payload = payload("set_equipment");
            JsonArray slots = new JsonArray();
            for (Pair<EquipmentSlot, ItemStack> slot : equipment.getSlots()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", slot.getFirst().getSerializedName());
                entry.addProperty("item", item(slot.getSecond()));
                slots.add(entry);
            }
            payload.add("slots", slots);
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.EQUIPMENT, equipment.getEntity(),
                null, null, null, null, payload), packet);
        } else if (packet instanceof ClientboundDamageEventPacket damage) {
            JsonObject payload = payload("damage_event");
            payload.addProperty("sourceType", key(damage.sourceType()));
            payload.addProperty("sourceCauseId", damage.sourceCauseId());
            payload.addProperty("sourceDirectId", damage.sourceDirectId());
            damage.sourcePosition().ifPresent(position -> {
                payload.addProperty("sourceX", position.x);
                payload.addProperty("sourceY", position.y);
                payload.addProperty("sourceZ", position.z);
            });
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.DAMAGE_EVENT, damage.entityId(),
                null, null, null, null, payload), packet);
        } else if (packet instanceof ClientboundEntityEventPacket entityEvent) {
            byte eventId = entityEvent.getEventId();
            ObservationKind kind = eventId == EntityEvent.PROTECTED_FROM_DEATH
                ? ObservationKind.TOTEM_POP
                : ObservationKind.ENTITY_STATUS;
            JsonObject payload = payload("entity_event");
            payload.addProperty("eventId", eventId);
            recorder.receive(new CombatRecorder.Receipt(kind, -1,
                null, null, null, null, payload), packet);
        } else if (packet instanceof ClientboundPlayerPositionPacket position) {
            Vec3 change = position.change().position();
            JsonObject payload = payload("player_position");
            payload.addProperty("id", position.id());
            payload.addProperty("targetX", change.x);
            payload.addProperty("targetY", change.y);
            payload.addProperty("targetZ", change.z);
            payload.add("relatives", relatives(position.relatives()));
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.SETBACK, -1,
                null, change.x, change.y, change.z, payload), packet);
        } else if (packet instanceof ClientboundRespawnPacket respawn) {
            JsonObject payload = payload("respawn");
            payload.addProperty("dimension", respawn.commonPlayerSpawnInfo().dimension().identifier().toString());
            payload.addProperty("dataToKeep", respawn.dataToKeep());
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.RESPAWN, -1,
                null, null, null, null, payload), packet);
        } else if (packet instanceof ClientboundMoveEntityPacket move) {
            JsonObject payload = payload("move_entity");
            payload.addProperty("xa", move.getXa());
            payload.addProperty("ya", move.getYa());
            payload.addProperty("za", move.getZa());
            payload.addProperty("hasPosition", move.hasPosition());
            payload.addProperty("hasRotation", move.hasRotation());
            payload.addProperty("yRot", move.getYRot());
            payload.addProperty("xRot", move.getXRot());
            payload.addProperty("onGround", move.isOnGround());
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.MOVE, -1,
                null, null, null, null, payload), packet);
        } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            Vec3 position = teleport.change().position();
            JsonObject payload = payload("teleport_entity");
            payload.addProperty("onGround", teleport.onGround());
            payload.add("relatives", relatives(teleport.relatives()));
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.MOVE, teleport.id(),
                null, position.x, position.y, position.z, payload), packet);
        } else if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            Vec3 position = sync.values().position();
            JsonObject payload = payload("entity_position_sync");
            payload.addProperty("onGround", sync.onGround());
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.MOVE, sync.id(),
                null, position.x, position.y, position.z, payload), packet);
        }
    }

    private CombatRecorder.Receipt blockReceipt(String packetName, BlockPos pos, BlockState state) {
        JsonObject payload = payload(packetName);
        payload.addProperty("block", id(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
        payload.addProperty("pos", pos.toShortString());
        payload.addProperty("movingPiston", state.getBlock() == Blocks.MOVING_PISTON);
        return positioned(ObservationKind.BLOCK_UPDATE, pos, payload);
    }

    private static CombatRecorder.Receipt positioned(ObservationKind kind, BlockPos pos, JsonObject payload) {
        return new CombatRecorder.Receipt(kind, -1, null,
            (double) pos.getX(), (double) pos.getY(), (double) pos.getZ(), payload);
    }

    // ---------------------------------------------------------------- application side (client thread)

    @Override
    public void afterSetHealth(ClientboundSetHealthPacket packet) {
        Player player = mc.player;
        if (player == null) {
            recorder.markApplied(packet, CombatEventRecord.NO_ENTITY, null, null, null, null, null);
            return;
        }
        Vec3 position = player.position();
        JsonObject extra = new JsonObject();
        extra.addProperty("appliedHealth", player.getHealth());
        extra.addProperty("appliedAbsorption", player.getAbsorptionAmount());
        extra.addProperty("appliedFood", player.getFoodData().getFoodLevel());
        extra.addProperty("appliedSaturation", player.getFoodData().getSaturationLevel());
        recorder.markApplied(packet, player.getId(), player.getUUID().toString(),
            position.x, position.y, position.z, extra);

        if (player.getHealth() <= 0.0f) {
            JsonObject payload = payload("derived_death");
            payload.addProperty("health", player.getHealth());
            Object deathKey = new Object();
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.DEATH, player.getId(),
                player.getUUID().toString(), position.x, position.y, position.z, payload), deathKey);
            recorder.markApplied(deathKey, player.getId(), player.getUUID().toString(),
                position.x, position.y, position.z, null);
        }
    }

    @Override
    public void afterSetEntityData(ClientboundSetEntityDataPacket packet) {
        Entity entity = entity(packet.id());
        if (entity == null) {
            recorder.markApplied(packet, packet.id(), null, null, null, null, null);
            return;
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("entityType", id(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
        if (entity instanceof LivingEntity living) {
            extra.addProperty("appliedHealth", living.getHealth());
            extra.addProperty("appliedAbsorption", living.getAbsorptionAmount());
        }
        vec(entity.position(), extra);
        recorder.markApplied(packet, entity.getId(), entity.getUUID().toString(),
            entity.getX(), entity.getY(), entity.getZ(), extra);
    }

    @Override
    public void afterUpdateMobEffect(ClientboundUpdateMobEffectPacket packet) {
        applyResolved(packet, entity(packet.getEntityId()), null);
    }

    @Override
    public void afterRemoveMobEffect(ClientboundRemoveMobEffectPacket packet) {
        applyResolved(packet, entity(packet.entityId()), null);
    }

    @Override
    public void afterSetEquipment(ClientboundSetEquipmentPacket packet) {
        Entity entity = entity(packet.getEntity());
        JsonObject extra = new JsonObject();
        if (entity instanceof LivingEntity living) {
            JsonArray slots = new JsonArray();
            for (Pair<EquipmentSlot, ItemStack> slot : packet.getSlots()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", slot.getFirst().getSerializedName());
                entry.addProperty("item", item(living.getItemBySlot(slot.getFirst())));
                slots.add(entry);
            }
            extra.add("appliedSlots", slots);
        }
        applyResolved(packet, entity, extra);
    }

    @Override
    public void afterDamageEvent(ClientboundDamageEventPacket packet) {
        Entity entity = entity(packet.entityId());
        JsonObject extra = new JsonObject();
        if (entity != null) {
            extra.addProperty("entityType", id(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
        }
        applyResolved(packet, entity, extra);
    }

    @Override
    public void afterExplosion(ClientboundExplodePacket packet) {
        JsonObject extra = new JsonObject();
        Player player = mc.player;
        if (player != null) {
            vec(player.position(), extra);
            extra.addProperty("playerHealthAfter", player.getHealth());
        }
        recorder.markApplied(packet, CombatEventRecord.NO_ENTITY, null, null, null, null, extra);
    }

    @Override
    public void afterPlayerPosition(ClientboundPlayerPositionPacket packet) {
        Player player = mc.player;
        if (player == null) {
            recorder.markApplied(packet, CombatEventRecord.NO_ENTITY, null, null, null, null, null);
            return;
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("appliedYaw", player.getYRot());
        extra.addProperty("appliedPitch", player.getXRot());
        recorder.markApplied(packet, player.getId(), player.getUUID().toString(),
            player.getX(), player.getY(), player.getZ(), extra);
    }

    @Override
    public void afterRespawn(ClientboundRespawnPacket packet) {
        String dimension = packet.commonPlayerSpawnInfo().dimension().identifier().toString();
        JsonObject extra = new JsonObject();
        extra.addProperty("dimension", dimension);
        extra.addProperty("dimensionChanged", lastDimension != null && !lastDimension.equals(dimension));
        if (lastDimension != null) extra.addProperty("fromDimension", lastDimension);
        lastDimension = dimension;
        Player player = mc.player;
        if (player == null) {
            recorder.markApplied(packet, CombatEventRecord.NO_ENTITY, null, null, null, null, extra);
            return;
        }
        vec(player.position(), extra);
        recorder.markApplied(packet, player.getId(), player.getUUID().toString(),
            player.getX(), player.getY(), player.getZ(), extra);
    }

    @Override
    public void afterBlockChangedAck(ClientboundBlockChangedAckPacket packet) {
        recorder.markApplied(packet, CombatEventRecord.NO_ENTITY, null, null, null, null, null);
    }

    @Override
    public void afterBlockEvent(ClientboundBlockEventPacket packet) {
        JsonObject extra = new JsonObject();
        ClientLevel level = mc.level;
        if (level != null) {
            extra.addProperty("appliedBlock",
                id(BuiltInRegistries.BLOCK.getKey(level.getBlockState(packet.getPos()).getBlock())));
        }
        applyAt(packet, packet.getPos(), extra);
    }

    @Override
    public void afterEntityEvent(ClientboundEntityEventPacket packet) {
        Entity entity = mc.level == null ? null : packet.getEntity(mc.level);
        JsonObject extra = new JsonObject();
        extra.addProperty("eventId", packet.getEventId());
        applyResolved(packet, entity, extra);
    }

    @Override
    public void afterMoveEntity(ClientboundMoveEntityPacket packet) {
        Entity entity = mc.level == null ? null : packet.getEntity(mc.level);
        applyResolved(packet, entity, null);
    }

    @Override
    public void afterTeleportEntity(ClientboundTeleportEntityPacket packet) {
        applyResolved(packet, entity(packet.id()), null);
    }

    @Override
    public void afterEntityPositionSync(ClientboundEntityPositionSyncPacket packet) {
        applyResolved(packet, entity(packet.id()), null);
    }

    // Meteor's own after-application events.

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (stopped) return;
        Entity entity = event.entity;
        JsonObject extra = new JsonObject();
        extra.addProperty("entityType", id(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
        recorder.markAppliedByEntity(ObservationKind.SPAWN, entity.getId(), entity.getUUID().toString(),
            entity.getX(), entity.getY(), entity.getZ(), extra);
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (stopped) return;
        Entity entity = event.entity;
        JsonObject extra = new JsonObject();
        extra.addProperty("entityType", id(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())));
        Entity.RemovalReason reason = entity.getRemovalReason();
        extra.addProperty("removalReason", reason == null ? "unknown" : reason.name());
        recorder.markAppliedByEntity(ObservationKind.REMOVED, entity.getId(), entity.getUUID().toString(),
            entity.getX(), entity.getY(), entity.getZ(), extra);
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (stopped) return;
        BlockPos pos = event.pos;
        JsonObject extra = new JsonObject();
        extra.addProperty("oldBlock", id(BuiltInRegistries.BLOCK.getKey(event.oldState.getBlock())));
        extra.addProperty("newBlock", id(BuiltInRegistries.BLOCK.getKey(event.newState.getBlock())));
        extra.addProperty("movingPiston", event.newState.getBlock() == Blocks.MOVING_PISTON);
        boolean stamped = recorder.markAppliedByPosition(ObservationKind.BLOCK_UPDATE,
            pos.getX(), pos.getY(), pos.getZ(), (double) pos.getX(), (double) pos.getY(), (double) pos.getZ(), extra);
        if (!stamped) {
            // The recorder may have been enabled after the packet was received; keep the observation anyway.
            Object key = new Object();
            recorder.receive(new CombatRecorder.Receipt(ObservationKind.BLOCK_UPDATE, -1, null,
                (double) pos.getX(), (double) pos.getY(), (double) pos.getZ(), extra), key);
            recorder.markApplied(key, CombatEventRecord.NO_ENTITY, null,
                (double) pos.getX(), (double) pos.getY(), (double) pos.getZ(), null);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (stopped) return;
        tick++;
        recorder.tick();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (stopped) return;
        // Connection identity is assigned by the raw connection hook. GameJoinedEvent can
        // expose a different Connection object in the in-process test server.
        ClientLevel level = mc.level;
        if (level != null) lastDimension = level.dimension().identifier().toString();
        recorder.session("join");
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (stopped) return;
        recorder.session("leave");
        synchronized (connectionIds) {
            connectionIds.clear();
        }
        currentConnectionId = 0L;
    }

    // ---------------------------------------------------------------- helpers

    private void applyResolved(Packet<?> packet, @Nullable Entity entity, @Nullable JsonObject extra) {
        if (entity == null) {
            recorder.markApplied(packet, CombatEventRecord.NO_ENTITY, null, null, null, null, extra);
            return;
        }
        recorder.markApplied(packet, entity.getId(), entity.getUUID().toString(),
            entity.getX(), entity.getY(), entity.getZ(), extra);
    }

    private void applyAt(Packet<?> packet, BlockPos pos, @Nullable JsonObject extra) {
        recorder.markApplied(packet, CombatEventRecord.NO_ENTITY, null,
            (double) pos.getX(), (double) pos.getY(), (double) pos.getZ(), extra);
    }

    private @Nullable Entity entity(int entityId) {
        ClientLevel level = mc.level;
        return level == null ? null : level.getEntity(entityId);
    }

    private void assignConnection(@Nullable Connection connection) {
        if (connection == null) return;
        long id;
        synchronized (connectionIds) {
            Long known = connectionIds.get(connection);
            if (known == null) {
                id = nextConnectionId.getAndIncrement();
                connectionIds.put(connection, id);
            } else {
                id = known;
            }
        }
        currentConnectionId = id;
    }

    private static JsonObject payload(String packet) {
        JsonObject payload = new JsonObject();
        payload.addProperty("packet", packet);
        return payload;
    }

    private static JsonArray relatives(Iterable<Relative> relatives) {
        JsonArray array = new JsonArray();
        for (Relative relative : relatives) array.add(relative.name());
        return array;
    }

    private static String id(@Nullable Identifier identifier) {
        return identifier == null ? "unknown" : identifier.toString();
    }

    private static <T> String key(Holder<T> holder) {
        return holder.unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
    }

    private static String item(ItemStack stack) {
        if (stack.isEmpty()) return "minecraft:air";
        String item = id(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return stack.getCount() > 1 ? item + " x" + stack.getCount() : item;
    }

    private static void vec(Vec3 position, JsonObject target) {
        target.addProperty("resolvedX", position.x);
        target.addProperty("resolvedY", position.y);
        target.addProperty("resolvedZ", position.z);
    }

    private static String truncate(String value) {
        return value.length() <= PAYLOAD_STRING_LIMIT
            ? value
            : value.substring(0, PAYLOAD_STRING_LIMIT) + "...";
    }
}
