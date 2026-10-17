package dev.tntcoating.mixin;

import dev.tntcoating.modules.AdaptiveElytraFly;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightMode;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replace one stock mode at its source so its endpoint guard cannot erase the requested movement first. */
@Mixin(value = ElytraFly.class, remap = false)
public abstract class ElytraFlyMixin {
    @Shadow private ElytraFlightMode currentMode;

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void comet$adaptiveFlight(PlayerMoveEvent event, CallbackInfo ci) {
        AdaptiveElytraFly adaptive = Modules.get().get(AdaptiveElytraFly.class);
        if (adaptive != null && adaptive.move((ElytraFly) (Object) this, currentMode, event)) ci.cancel();
    }
}
