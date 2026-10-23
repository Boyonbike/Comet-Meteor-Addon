/*
 * Original Comet implementation by Boyonabike.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.mixin;

import dev.tntcoating.modules.NoHurtCam;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class NoHurtCamMixin {
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void comet$cancelHurtCamera(CallbackInfo ci) {
        if (hurtCameraDisabled()) ci.cancel();
    }

    private static boolean hurtCameraDisabled() {
        Modules modules = Modules.get();
        if (modules == null) return false;
        NoHurtCam module = modules.get(NoHurtCam.class);
        return module != null && module.shouldDisableHurtCam();
    }
}
