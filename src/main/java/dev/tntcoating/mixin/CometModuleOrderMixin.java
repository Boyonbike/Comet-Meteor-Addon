package dev.tntcoating.mixin;

import dev.tntcoating.TntCoatingAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Modules.class, remap = false)
public abstract class CometModuleOrderMixin {
    @Inject(method = "sortModules", at = @At("TAIL"))
    private void comet$sortModuleGroup(CallbackInfo info) {
        TntCoatingAddon.sortModuleGroup();
    }
}
