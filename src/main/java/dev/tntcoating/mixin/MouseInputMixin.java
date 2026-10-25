/*
 * Original Comet implementation by Boyonabike.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.mixin;

import dev.tntcoating.modules.HighwayFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camera-only free look while Highway Fly is airborne. */
@Mixin(MouseHandler.class)
public abstract class MouseInputMixin {
    @Accessor("accumulatedDX")
    abstract double comet$accumulatedDX();

    @Accessor("accumulatedDY")
    abstract double comet$accumulatedDY();

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void comet$mouseLook(double amount, CallbackInfo ci) {
        HighwayFly module = module();
        if (module == null || !module.shouldControlMouseLook()) return;
        if (module.shouldCaptureMouseLook()) {
            module.onMouseLook(comet$accumulatedDX(), comet$accumulatedDY());
        }
        ci.cancel();
    }

    private static HighwayFly module() {
        Modules modules = Modules.get();
        return modules == null ? null : modules.get(HighwayFly.class);
    }
}
