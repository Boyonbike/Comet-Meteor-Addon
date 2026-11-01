/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.mixin;

import dev.tntcoating.core.combat.mc.ObservationHooks;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The only point that sees a {@code ClientboundBundlePacket} before Meteor unpacks it: vanilla receives the
 * bundle as one packet, Meteor posts Receive per sub-packet just before {@code genericsFtw}. Recording bundle
 * membership at HEAD lets receipts carry a bundle id and position.
 */
@Mixin(Connection.class)
public abstract class CombatConnectionHooksMixin {
    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD"))
    private void comet$beforePacketRead(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        ObservationHooks.beforePacketRead((Connection) (Object) this, packet);
    }

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("RETURN"))
    private void comet$afterPacketRead(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        ObservationHooks.afterPacketRead((Connection) (Object) this, packet);
    }
}
