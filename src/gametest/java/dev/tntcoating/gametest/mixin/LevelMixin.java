/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.mixin;

import dev.tntcoating.gametest.server.ServerGroundTruth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Unique
    private final ThreadLocal<BlockState> comet$oldState = new ThreadLocal<>();

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"))
    private void comet$blockBefore(BlockPos pos, BlockState state, int flags, int recursion,
                                   CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel) {
            comet$oldState.set(((Level) (Object) this).getBlockState(pos));
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("RETURN"))
    private void comet$blockAfter(BlockPos pos, BlockState state, int flags, int recursion,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerLevel)) return;
        BlockState old = comet$oldState.get();
        comet$oldState.remove();
        ServerGroundTruth.event("block_change", line -> {
            line.addProperty("x", pos.getX());
            line.addProperty("y", pos.getY());
            line.addProperty("z", pos.getZ());
            line.addProperty("old", old == null ? "unknown" : BuiltInRegistries.BLOCK.getKey(old.getBlock()).toString());
            line.addProperty("new", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            line.addProperty("changed", cir.getReturnValue());
        });
    }
}
