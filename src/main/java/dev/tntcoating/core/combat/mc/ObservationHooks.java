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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Entry points called by Comet's thin mixins. Every installed listener receives every hook, so the Phase 0
 * recorder can stay attached beside Phase 2b's runtime instead of the two fighting over one slot. All calls
 * no-op while nothing is installed, so the hooks stay cheap in normal play.
 */
public final class ObservationHooks {
    private static final List<ObservationListener> LISTENERS = new CopyOnWriteArrayList<>();

    private ObservationHooks() {}

    public static void install(ObservationListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        if (!LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void uninstall(ObservationListener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean installed() {
        return !LISTENERS.isEmpty();
    }

    private static void dispatch(Consumer<ObservationListener> hook) {
        for (ObservationListener listener : LISTENERS) hook.accept(listener);
    }

    public static void beforePacketRead(Connection connection, Packet<?> packet) {
        dispatch(listener -> listener.beforePacketRead(connection, packet));
    }

    public static void afterPacketRead(Connection connection, Packet<?> packet) {
        dispatch(listener -> listener.afterPacketRead(connection, packet));
    }

    public static void afterSetHealth(ClientboundSetHealthPacket packet) {
        dispatch(listener -> listener.afterSetHealth(packet));
    }

    public static void afterSetEntityData(ClientboundSetEntityDataPacket packet) {
        dispatch(listener -> listener.afterSetEntityData(packet));
    }

    public static void afterUpdateMobEffect(ClientboundUpdateMobEffectPacket packet) {
        dispatch(listener -> listener.afterUpdateMobEffect(packet));
    }

    public static void afterRemoveMobEffect(ClientboundRemoveMobEffectPacket packet) {
        dispatch(listener -> listener.afterRemoveMobEffect(packet));
    }

    public static void afterSetEquipment(ClientboundSetEquipmentPacket packet) {
        dispatch(listener -> listener.afterSetEquipment(packet));
    }

    public static void afterDamageEvent(ClientboundDamageEventPacket packet) {
        dispatch(listener -> listener.afterDamageEvent(packet));
    }

    public static void afterExplosion(ClientboundExplodePacket packet) {
        dispatch(listener -> listener.afterExplosion(packet));
    }

    public static void afterPlayerPosition(ClientboundPlayerPositionPacket packet) {
        dispatch(listener -> listener.afterPlayerPosition(packet));
    }

    public static void afterRespawn(ClientboundRespawnPacket packet) {
        dispatch(listener -> listener.afterRespawn(packet));
    }

    public static void afterBlockChangedAck(ClientboundBlockChangedAckPacket packet) {
        dispatch(listener -> listener.afterBlockChangedAck(packet));
    }

    public static void afterBlockEvent(ClientboundBlockEventPacket packet) {
        dispatch(listener -> listener.afterBlockEvent(packet));
    }

    public static void afterEntityEvent(ClientboundEntityEventPacket packet) {
        dispatch(listener -> listener.afterEntityEvent(packet));
    }

    public static void afterMoveEntity(ClientboundMoveEntityPacket packet) {
        dispatch(listener -> listener.afterMoveEntity(packet));
    }

    public static void afterTeleportEntity(ClientboundTeleportEntityPacket packet) {
        dispatch(listener -> listener.afterTeleportEntity(packet));
    }

    public static void afterEntityPositionSync(ClientboundEntityPositionSyncPacket packet) {
        dispatch(listener -> listener.afterEntityPositionSync(packet));
    }
}
