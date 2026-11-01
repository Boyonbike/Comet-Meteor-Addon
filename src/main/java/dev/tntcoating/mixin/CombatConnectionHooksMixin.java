/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
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
 * Runs on the netty thread before and after vanilla decodes a packet. The hook only stamps the receipt and
 * expands bundles; it must never read or mutate world state.
 */
@Mixin(Connection.class)
public abstract class CombatConnectionHooksMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD"))
    private void comet$beforePacketRead(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        ObservationHooks.beforePacketRead((Connection) (Object) this, packet);
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("RETURN"))
    private void comet$afterPacketRead(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        ObservationHooks.afterPacketRead((Connection) (Object) this, packet);
    }
}
