/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.mixin;

import dev.tntcoating.gametest.server.ServerGroundTruth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private static void comet$pistonTickStart(Level level, BlockPos pos, BlockState state,
                                              PistonMovingBlockEntity entity, CallbackInfo ci) {
        if (!(level instanceof ServerLevel)) return;
        ServerGroundTruth.event("piston_tick_start", line -> {
            line.addProperty("x", pos.getX());
            line.addProperty("y", pos.getY());
            line.addProperty("z", pos.getZ());
            line.addProperty("direction", entity.getDirection().getName());
            line.addProperty("extending", entity.isExtending());
            line.addProperty("sourcePiston", entity.isSourcePiston());
            line.addProperty("progress", entity.getProgress(1.0f));
            line.addProperty("moved", entity.getMovedState().toString());
        });
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private static void comet$pistonTickEnd(Level level, BlockPos pos, BlockState state,
                                            PistonMovingBlockEntity entity, CallbackInfo ci) {
        if (!(level instanceof ServerLevel)) return;
        ServerGroundTruth.event("piston_tick_end", line -> {
            line.addProperty("x", pos.getX());
            line.addProperty("y", pos.getY());
            line.addProperty("z", pos.getZ());
            line.addProperty("direction", entity.getDirection().getName());
            line.addProperty("extending", entity.isExtending());
            line.addProperty("progress", entity.getProgress(1.0f));
        });
    }
}
