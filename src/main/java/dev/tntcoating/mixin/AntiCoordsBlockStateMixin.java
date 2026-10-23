/*
 * Adapted from NoTextureRotations by rfresh2 (https://github.com/rfresh2/NoTextureRotations).
 * Modified for Comet by Boyonabike.
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.tntcoating.modules.AntiCoords;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every renderer asks the block state for its seed, so hooking here also covers doors, beds and tall plants,
 * whose blocks override the seed and would slip past a hook on Block.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class AntiCoordsBlockStateMixin {
    @ModifyVariable(method = "getSeed", at = @At("HEAD"), argsOnly = true)
    private BlockPos comet$seedPos(BlockPos pos) {
        return AntiCoords.seedPos(pos);
    }

    @ModifyReturnValue(method = "getSeed", at = @At("RETURN"))
    private long comet$seed(long seed) {
        return AntiCoords.seed(seed);
    }

    @Inject(method = "getOffset", at = @At("HEAD"), cancellable = true)
    private void comet$offset(BlockPos pos, CallbackInfoReturnable<Vec3> cir, @Local(argsOnly = true) LocalRef<BlockPos> posRef) {
        if (!((BlockBehaviour.BlockStateBase) (Object) this).hasOffsetFunction()) return;
        AntiCoords.Mode mode = AntiCoords.offsetMode();
        if (mode == AntiCoords.Mode.Fixed) cir.setReturnValue(Vec3.ZERO);
        else if (mode == AntiCoords.Mode.Keyed) posRef.set(AntiCoords.offsetPos(pos));
    }
}
