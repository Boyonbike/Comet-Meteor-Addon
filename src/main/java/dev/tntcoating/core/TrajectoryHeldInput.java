package dev.tntcoating.core;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Exact simulator inputs; the cached stack is copied so mutable components cannot hide item changes. */
public record TrajectoryHeldInput(Motion motion, ItemStack stack) {
    public TrajectoryHeldInput snapshot() { return new TrajectoryHeldInput(motion, stack.copy()); }

    @Override public boolean equals(Object other) {
        return other instanceof TrajectoryHeldInput input && motion.equals(input.motion)
            && ItemStack.isSameItemSameComponents(stack, input.stack);
    }

    @Override public int hashCode() { return motion.hashCode(); }

    public record Motion(Vec3 origin, Vec3 movement, Pose pose, float eyeHeight, float yaw, float pitch,
                         Vec3 view, Vec3 up, int useTicks, boolean grounded, boolean accurate,
                         int steps, int ignoreFirst) {}
}
