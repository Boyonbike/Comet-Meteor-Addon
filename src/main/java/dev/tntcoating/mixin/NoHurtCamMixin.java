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
    private void comet$skipHurtCamera(CallbackInfo ci) {
        Modules modules = Modules.get();
        NoHurtCam module = modules == null ? null : modules.get(NoHurtCam.class);
        if (module != null && module.shouldDisableHurtCam()) ci.cancel();
    }
}
