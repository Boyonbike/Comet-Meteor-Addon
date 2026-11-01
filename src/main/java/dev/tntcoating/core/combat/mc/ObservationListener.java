/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;

/**
 * Shared purpose-built after-application hook layer (plan P0.3, audit §3.10.6).
 *
 * <p>Meteor only exposes after-application events for entity add/remove, block updates and entity movement.
 * Every other audited observation is delivered here, always on the client thread after vanilla has applied
 * the packet. Phase 2b's {@code CombatRuntime} implements this same interface instead of adding a second hook
 * layer. Methods default to no-ops so a listener only overrides what it consumes.</p>
 */
public interface ObservationListener {
    /** Runs at the head of {@code Connection.channelRead0}, on the network thread, before Meteor's Receive. */
    default void beforePacketRead(Connection connection, Packet<?> packet) {}

    /** Runs as {@code Connection.channelRead0} returns, on the network thread. Drops bundle bookkeeping. */
    default void afterPacketRead(Connection connection, Packet<?> packet) {}

    default void afterSetHealth(ClientboundSetHealthPacket packet) {}

    default void afterSetEntityData(ClientboundSetEntityDataPacket packet) {}

    default void afterUpdateMobEffect(ClientboundUpdateMobEffectPacket packet) {}

    default void afterRemoveMobEffect(ClientboundRemoveMobEffectPacket packet) {}

    default void afterSetEquipment(ClientboundSetEquipmentPacket packet) {}

    default void afterDamageEvent(ClientboundDamageEventPacket packet) {}

    default void afterExplosion(ClientboundExplodePacket packet) {}

    default void afterPlayerPosition(ClientboundPlayerPositionPacket packet) {}

    default void afterRespawn(ClientboundRespawnPacket packet) {}

    default void afterBlockChangedAck(ClientboundBlockChangedAckPacket packet) {}

    default void afterBlockEvent(ClientboundBlockEventPacket packet) {}

    default void afterEntityEvent(ClientboundEntityEventPacket packet) {}

    default void afterMoveEntity(ClientboundMoveEntityPacket packet) {}

    default void afterTeleportEntity(ClientboundTeleportEntityPacket packet) {}

    default void afterEntityPositionSync(ClientboundEntityPositionSyncPacket packet) {}
}
