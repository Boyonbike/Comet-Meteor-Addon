/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
 * The static hook seam between the mixins and the combat layer. Listeners are called in installation order
 * from the thread that fired the hook: the netty thread for the two packet-read hooks and the client thread
 * for every applied hook. A listener failure is isolated so one broken consumer cannot break packet
 * handling; listeners must be fast and must never send.
 */
public final class ObservationHooks {
    private static final CopyOnWriteArrayList<ObservationListener> LISTENERS = new CopyOnWriteArrayList<>();

    private ObservationHooks() {
    }

    public static void install(ObservationListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        LISTENERS.addIfAbsent(listener);
    }

    public static void uninstall(ObservationListener listener) {
        LISTENERS.remove(listener);
    }

    public static List<ObservationListener> installed() {
        return List.copyOf(LISTENERS);
    }

    private static void dispatch(Consumer<ObservationListener> call) {
        for (ObservationListener listener : LISTENERS) {
            try {
                call.accept(listener);
            } catch (RuntimeException ignored) {
                // Observation is passive: a faulty listener must not break packet handling.
            }
        }
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
