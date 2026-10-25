/*
 * Original Comet implementation by Boyonabike.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.mixin;

import dev.tntcoating.modules.HighwayFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Applies Highway Fly's camera yaw/pitch without changing the player's flight heading. */
@Mixin(Camera.class)
public abstract class FreeLookCameraMixin {
    @ModifyArgs(
        method = "alignWithEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V")
    )
    private void comet$freeLookRotation(Args args) {
        Modules modules = Modules.get();
        if (modules == null) return;
        HighwayFly module = modules.get(HighwayFly.class);
        if (module != null && module.shouldOverrideCamera()) {
            args.set(0, module.cameraYaw());
            args.set(1, module.cameraPitch());
        }
    }
}
