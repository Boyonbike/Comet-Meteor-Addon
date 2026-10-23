package dev.tntcoating.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Which blocks Speedmine may break around enemies, independent of Meteor and the world. */
public final class SpeedmineTargeting {
    private SpeedmineTargeting() {}

    /** Self, spectators, friends and players beyond range are never targets. */
    public static boolean eligibleEnemy(boolean self, boolean spectator, boolean friend, double distance, double range) {
        return !self && !spectator && !friend && distance <= range;
    }

    /** Phase targets win; surround targets are only consulted when phase produced nothing. */
    public static List<BlockPos> prioritizeTargets(boolean phase, boolean surround,
            Supplier<List<BlockPos>> phaseTargets, Supplier<List<BlockPos>> surroundTargets) {
        List<BlockPos> targets = phase ? phaseTargets.get() : List.of();
        return targets.isEmpty() && surround ? surroundTargets.get() : targets;
    }

    /** Every block the enemy's hitbox overlaps. Merely touching a face is not phasing into its block. */
    public static List<BlockPos> phaseCandidates(AABB box) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(Math.nextDown(box.maxX), Math.nextDown(box.maxY), Math.nextDown(box.maxZ)))) {
            out.add(pos.immutable());
        }
        return out;
    }

    /** True if the block is inside or directly against our own hitbox — breaking it drops or exposes us. */
    public static boolean protectsSelf(AABB self, BlockPos pos) {
        return self.inflate(1).intersects(pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    /** Keeps the current bedrock target while it stays eligible, otherwise takes the first one. */
    public static BlockPos selectBedrockTarget(BlockPos previous, List<BlockPos> targets, Predicate<BlockPos> bedrock) {
        if (previous != null && targets.contains(previous) && bedrock.test(previous)) return previous;
        return targets.stream().filter(bedrock).findFirst().orElse(null);
    }
}
