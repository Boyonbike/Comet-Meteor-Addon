package dev.tntcoating.mixin;

import dev.tntcoating.core.scaffold.ScaffoldCoordinator;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ownership scope is set only by ScaffoldMixin; these common utility hooks leave all other callers unchanged. */
@Mixin(value = BlockUtils.class, remap = false)
public abstract class ScaffoldLegacyDispatchMixin {
    @Redirect(method = "place(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/InteractionHand;IZIZZZ)Z", at = @At(value = "INVOKE",
        target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DDILjava/lang/Runnable;)V"))
    private static void comet$scopedRotation(double yaw, double pitch, int priority, Runnable callback) {
        Rotations.rotate(yaw, pitch, priority, ScaffoldCoordinator.guardLegacyRotation(callback));
    }
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private static void comet$actualDispatch(BlockHitResult hit, InteractionHand hand, boolean swing, CallbackInfo ci) {
        if (!ScaffoldCoordinator.legacyDispatch()) ci.cancel();
    }
}
