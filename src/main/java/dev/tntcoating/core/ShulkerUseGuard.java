package dev.tntcoating.core;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Set;

/** Remembers shulkers placed during one hold of the Use control. */
public final class ShulkerUseGuard {
    private final Set<BlockPos> placed = new HashSet<>();

    public void placed(BlockPos pos, boolean shulker, boolean useHeld) {
        update(useHeld);
        if (shulker && useHeld) placed.add(pos.immutable());
    }

    public void update(boolean useHeld) {
        if (!useHeld) clear();
    }

    public boolean shouldBlock(BlockPos pos, boolean shulker, boolean secondaryUse, boolean hasHeldItem) {
        // Vanilla bypasses the container interaction while sneaking with an item in either hand.
        // Allow that path so players can keep placing against the shulker, but protect empty hands too.
        return shulker && placed.contains(pos) && !(secondaryUse && hasHeldItem);
    }

    public void clear() {
        placed.clear();
    }
}
