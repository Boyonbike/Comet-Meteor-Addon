package dev.tntcoating.mixin;

import dev.tntcoating.modules.Phase;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class PhasePlayerMixin {
    @Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
    private void comet$allowPhaseInsideBlocks(double x, double z, CallbackInfo ci) {
        Modules modules = Modules.get();
        Phase phase = modules == null ? null : modules.get(Phase.class);
        if (phase != null && phase.suppressesPushOut()) ci.cancel();
    }
}
