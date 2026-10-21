package dev.tntcoating.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Bridge between applied server updates and Meteor's existing Scaffold module. */
public interface ScaffoldPlacementFeedback {
    void comet$serverBlockUpdate(BlockPos position, BlockState serverState);

    /** A server position correction, after the client has moved the player to it. */
    void comet$serverSetback();
}
