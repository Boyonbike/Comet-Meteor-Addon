package dev.tntcoating.mixin;

import dev.tntcoating.modules.AdaptiveElytraFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class SmartElytraSwapMixin {
    // Equip before vanilla checks the second Jump press. Vanilla opens the wings and sends its normal flight packet.
    @Inject(method = "aiStep", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;tryToStartFallFlying()Z"))
    private void comet$equipForFlight(CallbackInfo ci) {
        Modules modules = Modules.get();
        AdaptiveElytraFly fly = modules == null ? null : modules.get(AdaptiveElytraFly.class);
        if (fly != null) fly.prepareSmartTakeoff();
    }
}
