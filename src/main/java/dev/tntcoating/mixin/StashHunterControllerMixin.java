package dev.tntcoating.mixin;

import dev.tntcoating.compat.StashHunterDrive;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stash-Hunter's ElytraController, when Stash-Hunter is installed (skipped otherwise). driveTowards is its one movement
 * step: it either hands the target to Baritone's elytra flight, which uses fireworks, or sets the player's velocity
 * itself. Stash-Hunter's choice of targets (grid, trail and edge following) happens before it and is untouched.
 */
@Pseudo
@Mixin(targets = "com.stashhunter.stashhunter.utils.ElytraController", remap = false)
public abstract class StashHunterControllerMixin {
    @Inject(method = "driveTowards", at = @At("HEAD"), cancellable = true, require = 0)
    private static void comet$driveTowards(Vec3 target, CallbackInfo ci) {
        if (StashHunterDrive.take(target)) ci.cancel();
    }
}
