/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.mixin;

import dev.tntcoating.gametest.server.ServerGroundTruth;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Inject(method = "handleAttack", at = @At("HEAD"))
    private void comet$attackPacket(ServerboundAttackPacket packet, CallbackInfo ci) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (!player.level().getServer().isSameThread()) return;
        ServerGroundTruth.event("attack_packet", line -> {
            line.addProperty("player", player.getId());
            line.addProperty("target", packet.entityId());

        });
    }

    @Inject(method = "handleInteract", at = @At("HEAD"))
    private void comet$interactPacket(ServerboundInteractPacket packet, CallbackInfo ci) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (!player.level().getServer().isSameThread()) return;
        ServerGroundTruth.event("interact_packet", line -> {
            line.addProperty("player", player.getId());
            line.addProperty("entityId", packet.entityId());
            line.addProperty("hand", packet.hand().name());
        });
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void comet$useItemOnPacket(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (!player.level().getServer().isSameThread()) return;
        ServerGroundTruth.event("use_item_on_packet", line -> {
            line.addProperty("player", player.getId());
            line.addProperty("hand", packet.getHand().name());
            line.addProperty("sequence", packet.getSequence());
            line.addProperty("x", packet.getHitResult().getBlockPos().getX());
            line.addProperty("y", packet.getHitResult().getBlockPos().getY());
            line.addProperty("z", packet.getHitResult().getBlockPos().getZ());
            line.addProperty("face", packet.getHitResult().getDirection().getName());
        });
    }
}
