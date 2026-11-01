/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.mixin;

import dev.tntcoating.core.combat.mc.ObservationHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One RETURN hook per packet whose applied state the combat layer observes. Every handler begins with
 * {@code PacketUtils.ensureRunningOnSameThread}, so a RETURN hook runs on the client thread after the vanilla
 * state change; handlers may return early, so HEAD would be wrong.
 */
@Mixin(ClientPacketListener.class)
public abstract class CombatPacketHooksMixin {
    @Inject(method = "handleSetHealth(Lnet/minecraft/network/protocol/game/ClientboundSetHealthPacket;)V",
        at = @At("RETURN"))
    private void comet$afterSetHealth(ClientboundSetHealthPacket packet, CallbackInfo ci) {
        ObservationHooks.afterSetHealth(packet);
    }

    @Inject(method = "handleSetEntityData(Lnet/minecraft/network/protocol/game/ClientboundSetEntityDataPacket;)V",
        at = @At("RETURN"))
    private void comet$afterSetEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        ObservationHooks.afterSetEntityData(packet);
    }

    @Inject(method = "handleUpdateMobEffect(Lnet/minecraft/network/protocol/game/ClientboundUpdateMobEffectPacket;)V",
        at = @At("RETURN"))
    private void comet$afterUpdateMobEffect(ClientboundUpdateMobEffectPacket packet, CallbackInfo ci) {
        ObservationHooks.afterUpdateMobEffect(packet);
    }

    @Inject(method = "handleRemoveMobEffect(Lnet/minecraft/network/protocol/game/ClientboundRemoveMobEffectPacket;)V",
        at = @At("RETURN"))
    private void comet$afterRemoveMobEffect(ClientboundRemoveMobEffectPacket packet, CallbackInfo ci) {
        ObservationHooks.afterRemoveMobEffect(packet);
    }

    @Inject(method = "handleSetEquipment(Lnet/minecraft/network/protocol/game/ClientboundSetEquipmentPacket;)V",
        at = @At("RETURN"))
    private void comet$afterSetEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        ObservationHooks.afterSetEquipment(packet);
    }

    @Inject(method = "handleDamageEvent(Lnet/minecraft/network/protocol/game/ClientboundDamageEventPacket;)V",
        at = @At("RETURN"))
    private void comet$afterDamageEvent(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        ObservationHooks.afterDamageEvent(packet);
    }

    @Inject(method = "handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V",
        at = @At("RETURN"))
    private void comet$afterExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        ObservationHooks.afterExplosion(packet);
    }

    @Inject(method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V",
        at = @At("RETURN"))
    private void comet$afterPlayerPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        ObservationHooks.afterPlayerPosition(packet);
    }

    @Inject(method = "handleRespawn(Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;)V",
        at = @At("RETURN"))
    private void comet$afterRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        ObservationHooks.afterRespawn(packet);
    }

    @Inject(method = "handleBlockChangedAck(Lnet/minecraft/network/protocol/game/ClientboundBlockChangedAckPacket;)V",
        at = @At("RETURN"))
    private void comet$afterBlockChangedAck(ClientboundBlockChangedAckPacket packet, CallbackInfo ci) {
        ObservationHooks.afterBlockChangedAck(packet);
    }

    @Inject(method = "handleBlockEvent(Lnet/minecraft/network/protocol/game/ClientboundBlockEventPacket;)V",
        at = @At("RETURN"))
    private void comet$afterBlockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        ObservationHooks.afterBlockEvent(packet);
    }

    @Inject(method = "handleEntityEvent(Lnet/minecraft/network/protocol/game/ClientboundEntityEventPacket;)V",
        at = @At("RETURN"))
    private void comet$afterEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        ObservationHooks.afterEntityEvent(packet);
    }

    @Inject(method = "handleMoveEntity(Lnet/minecraft/network/protocol/game/ClientboundMoveEntityPacket;)V",
        at = @At("RETURN"))
    private void comet$afterMoveEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        ObservationHooks.afterMoveEntity(packet);
    }

    @Inject(method = "handleTeleportEntity(Lnet/minecraft/network/protocol/game/ClientboundTeleportEntityPacket;)V",
        at = @At("RETURN"))
    private void comet$afterTeleportEntity(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        ObservationHooks.afterTeleportEntity(packet);
    }

    @Inject(method = "handleEntityPositionSync(Lnet/minecraft/network/protocol/game/ClientboundEntityPositionSyncPacket;)V",
        at = @At("RETURN"))
    private void comet$afterEntityPositionSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        ObservationHooks.afterEntityPositionSync(packet);
    }
}
