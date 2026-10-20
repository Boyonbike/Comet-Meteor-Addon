package dev.tntcoating.mixin;

import dev.tntcoating.modules.AdaptiveElytraFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class SplitMoveMixin {
    // Before vanilla reports this tick's position, so a half-step reaches the server first.
    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void comet$splitMove(CallbackInfo ci) {
        Modules modules = Modules.get();
        AdaptiveElytraFly fly = modules == null ? null : modules.get(AdaptiveElytraFly.class);
        if (fly != null) fly.sendSplitMove();
    }
}
