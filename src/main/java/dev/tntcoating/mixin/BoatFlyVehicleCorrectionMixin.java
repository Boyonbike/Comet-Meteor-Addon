/*
 * Adapted from Quiettee Utils BoatPhase by Quiettee and contributors.
 * Copyright (c) Quiettee Utils contributors; Comet adaptations (c) 2026 Comet contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.tntcoating.mixin;

import dev.tntcoating.modules.boatfly.BoatFlyEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class BoatFlyVehicleCorrectionMixin {
    @Inject(method = "handleMoveVehicle", at = @At("HEAD"))
    private void comet$boatCorrectionBefore(ClientboundMoveVehiclePacket packet, CallbackInfo info) {
        if (!Minecraft.getInstance().isSameThread()) return;
        BoatFlyEngine module = BoatFlyEngine.active();
        if (module != null) module.beforeVehicleCorrection((ClientPacketListener) (Object) this, packet);
    }

    @Inject(method = "handleMoveVehicle", at = @At("RETURN"))
    private void comet$boatCorrectionAfter(ClientboundMoveVehiclePacket packet, CallbackInfo info) {
        if (!Minecraft.getInstance().isSameThread()) return;
        BoatFlyEngine module = BoatFlyEngine.active();
        if (module != null) module.afterVehicleCorrection((ClientPacketListener) (Object) this, packet);
    }
}
