package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.tntcoating.compat.StashHunterDrive;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Stash-Hunter's StuckDetector, when Stash-Hunter is installed (skipped otherwise). Its tick treats "Baritone has no
 * path" as stuck and toggles ElytraFly to recover. While Comet flies with ElytraFly, Baritone has no path by design,
 * so that check is skipped; the detector's own check for a gliding player standing still still runs.
 */
@Pseudo
@Mixin(targets = "com.stashhunter.stashhunter.modules.StuckDetector", remap = false)
public abstract class StashHunterStuckMixin {
    @WrapOperation(method = "onTick", at = @At(value = "INVOKE", target = "Lcom/stashhunter/stashhunter/baritone/BaritoneBridge;isModLoaded()Z"), require = 0)
    private boolean comet$skipBaritoneCheck(Operation<Boolean> original) {
        return !StashHunterDrive.driving() && original.call();
    }
}
