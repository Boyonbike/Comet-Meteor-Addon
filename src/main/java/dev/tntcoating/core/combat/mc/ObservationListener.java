/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
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
 * Applied-state observation surface. The first two hooks run on the netty thread before vanilla applies a
 * packet ({@code beforePacketRead}) and just after it returns ({@code afterPacketRead}); they only stamp
 * receipts. Every {@code after*} hook runs on the client thread at handler RETURN, i.e. after the vanilla
 * state change.
 */
public interface ObservationListener {
    default void beforePacketRead(Connection connection, Packet<?> packet) {
    }

    default void afterPacketRead(Connection connection, Packet<?> packet) {
    }

    default void afterSetHealth(ClientboundSetHealthPacket packet) {
    }

    default void afterSetEntityData(ClientboundSetEntityDataPacket packet) {
    }

    default void afterUpdateMobEffect(ClientboundUpdateMobEffectPacket packet) {
    }

    default void afterRemoveMobEffect(ClientboundRemoveMobEffectPacket packet) {
    }

    default void afterSetEquipment(ClientboundSetEquipmentPacket packet) {
    }

    default void afterDamageEvent(ClientboundDamageEventPacket packet) {
    }

    default void afterExplosion(ClientboundExplodePacket packet) {
    }

    default void afterPlayerPosition(ClientboundPlayerPositionPacket packet) {
    }

    default void afterRespawn(ClientboundRespawnPacket packet) {
    }

    default void afterBlockChangedAck(ClientboundBlockChangedAckPacket packet) {
    }

    default void afterBlockEvent(ClientboundBlockEventPacket packet) {
    }

    default void afterEntityEvent(ClientboundEntityEventPacket packet) {
    }

    default void afterMoveEntity(ClientboundMoveEntityPacket packet) {
    }

    default void afterTeleportEntity(ClientboundTeleportEntityPacket packet) {
    }

    default void afterEntityPositionSync(ClientboundEntityPositionSyncPacket packet) {
    }
}
