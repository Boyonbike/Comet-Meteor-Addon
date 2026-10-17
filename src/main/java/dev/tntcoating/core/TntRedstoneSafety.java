package dev.tntcoating.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.function.Function;
import java.util.function.Predicate;

/** Live TNT checks: inactive circuits can power up in response to placement. */
public final class TntRedstoneSafety {
    public static final int BUFFER = 2;

    private TntRedstoneSafety() {}

    /** A null loadedState result means unknown terrain; never treat it as clear of redstone. */
    public static boolean canPlace(boolean tnt, BlockPos target, Predicate<BlockPos> outsideBuildHeight,
                                   Function<BlockPos, BlockState> loadedState, Predicate<BlockPos> powered) {
        if (!tnt) return true;
        if (powered.test(target)) return false;
        // Include the target, diagonals and vertical offsets, even through intervening terrain.
        for (BlockPos pos : BlockPos.betweenClosed(target.offset(-BUFFER, -BUFFER, -BUFFER),
                                                  target.offset(BUFFER, BUFFER, BUFFER))) {
            if (outsideBuildHeight.test(pos)) continue;
            BlockState state = loadedState.apply(pos);
            if (state == null || component(state)) return false;
        }
        return true;
    }

    public static boolean component(BlockState state) {
        // Inspect capabilities, not the current power value. POWERED also covers tripwire
        // and activator/powered rails, which need not emit a signal themselves.
        return state.isSignalSource() || state.hasProperty(BlockStateProperties.POWER)
            || state.hasProperty(BlockStateProperties.POWERED);
    }
}
