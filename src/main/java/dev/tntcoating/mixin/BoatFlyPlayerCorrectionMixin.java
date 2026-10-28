/*
 * Adapted from Quiettee Utils player correction hooks by Quiettee and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.tntcoating.mixin;

import dev.tntcoating.events.BoatPlayerCorrectionEvent;
import dev.tntcoating.modules.boatfly.BoatFlyEngine;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Brackets vanilla correction handling without changing its packets or look direction. */
@Mixin(ClientPacketListener.class)
public abstract class BoatFlyPlayerCorrectionMixin {
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void comet$beforePlayerCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread() || (Object) this != client.getConnection() || BoatFlyEngine.active() == null) return;
        MeteorClient.EVENT_BUS.post(new BoatPlayerCorrectionEvent.Before(packet));
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void comet$afterPlayerCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread() || (Object) this != client.getConnection() || BoatFlyEngine.active() == null) return;
        MeteorClient.EVENT_BUS.post(new BoatPlayerCorrectionEvent.After(packet));
    }
}
