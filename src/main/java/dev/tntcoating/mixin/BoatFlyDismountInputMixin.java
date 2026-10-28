/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.mixin;

import dev.tntcoating.modules.boatfly.BoatFlyEngine;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remap before LocalPlayer consumes input and sends the normal server input packet. */
@Mixin(KeyboardInput.class)
public abstract class BoatFlyDismountInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void comet$boatDismountKey(CallbackInfo info) {
        BoatFlyEngine boat = BoatFlyEngine.active();
        if (boat == null) return;
        ClientInput input = (ClientInput) (Object) this;
        input.keyPresses = boat.dismountInput(input.keyPresses);
    }
}
