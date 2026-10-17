package dev.tntcoating.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.function.Function;
import java.util.function.Predicate;

/** Fresh world checks for replacing isolated source water with a coating block. */
public final class LiquidFiller {
    private LiquidFiller() {}

    /** A null loadedState result means unknown terrain and prevents filling. */
    public static boolean isolatedWaterSource(BlockPos target, Predicate<BlockPos> outsideBuildHeight,
                                               Function<BlockPos, BlockState> loadedState) {
        if (outsideBuildHeight.test(target)) return false;
        BlockState state = loadedState.apply(target);
        // Only replace plain water: waterlogged blocks and aquatic plants must survive.
        if (state == null || !state.is(Blocks.WATER) || !state.canBeReplaced()
            || !waterSource(state)) return false;

        for (Direction direction : Direction.values()) {
            BlockPos neighbor = target.relative(direction);
            if (outsideBuildHeight.test(neighbor)) continue;
            BlockState adjacent = loadedState.apply(neighbor);
            // Fluid state also catches connected sources in plants and waterlogged blocks.
            if (adjacent == null || waterSource(adjacent)) return false;
        }
        return true;
    }

    private static boolean waterSource(BlockState state) {
        return state.getFluidState().isSourceOfType(Fluids.WATER);
    }
}
