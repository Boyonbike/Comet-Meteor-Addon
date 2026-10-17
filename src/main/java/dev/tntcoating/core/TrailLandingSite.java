package dev.tntcoating.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Loaded, dry landing pads; callers must approach above the pad before descending. */
public final class TrailLandingSite {
    private static final int RADIUS = 64;

    private TrailLandingSite() {}

    /**
     * Find the nearest horizontal 3x3 pad within 64 blocks, at or below the player.
     * Never requests unloaded terrain. Null means hold altitude and retry after terrain loads.
     */
    public static Vec3 find(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        BlockPos origin = mc.player.blockPosition();
        Vec3 best = null;
        double bestDistance = RADIUS * RADIUS;
        // Inspect nearby rings first so the first usable pad bounds later, more expensive checks.
        for (int radius = 0; radius <= RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    double centreX = x + .5;
                    double centreZ = z + .5;
                    double distance = square(centreX - mc.player.getX()) + square(centreZ - mc.player.getZ());
                    if (distance > bestDistance || !mc.level.hasChunk(x >> 4, z >> 4)) continue;
                    int feetY = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (feetY > mc.player.getY()) continue;
                    Vec3 candidate = new Vec3(centreX, feetY, centreZ);
                    if (!valid(mc, candidate)) continue;
                    best = candidate;
                    bestDistance = distance;
                }
            }
            // The closest centre outside the completed ring is at least radius blocks away,
            // including when the player is near the edge of their current block.
            if (best != null && bestDistance <= square(radius)) return best;
        }
        return best;
    }

    /** Revalidate every approach/landing tick because terrain and loaded chunks can change. */
    public static boolean valid(Minecraft mc, Vec3 target) {
        if (mc.player == null || mc.level == null || target == null
            || !Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z)
            || target.y != Math.rint(target.y)) return false;
        ClientLevel level = mc.level;
        BlockPos feet = BlockPos.containing(target);
        // Check the approach at the present altitude too, so a dry pad on the far side of a hill
        // does not become an unreachable landing target. Check chunk availability before collision queries.
        int minChunkX = (Math.min(mc.player.blockPosition().getX(), feet.getX()) - 1) >> 4;
        int maxChunkX = (Math.max(mc.player.blockPosition().getX(), feet.getX()) + 1) >> 4;
        int minChunkZ = (Math.min(mc.player.blockPosition().getZ(), feet.getZ()) - 1) >> 4;
        int maxChunkZ = (Math.max(mc.player.blockPosition().getZ(), feet.getZ()) + 1) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) if (!level.hasChunk(x, z)) return false;
        }
        if (!level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(
            target.x - mc.player.getX(), 0, target.z - mc.player.getZ()))) return false;
        int top = Math.max(feet.getY() + 1, (int) Math.ceil(mc.player.getY() + 2));
        // Landing beneath a roof is excluded even if there is sufficient room to stand: the
        // entire 3x3 descent corridor must be clear from the player's current flight altitude.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos floor = feet.offset(dx, -1, dz);
                if (!loaded(level, floor)) return false;
                BlockState surface = level.getBlockState(floor);
                if (!surface.getFluidState().isEmpty() || hazardous(surface)
                    || !surface.isFaceSturdy(level, floor, Direction.UP)) return false;
                for (int y = feet.getY(); y <= top; y++) {
                    BlockPos air = new BlockPos(floor.getX(), y, floor.getZ());
                    if (y > level.getMaxY()) break;
                    if (!loaded(level, air)) return false;
                    BlockState state = level.getBlockState(air);
                    if (!state.isAir() || !state.getFluidState().isEmpty()) return false;
                }
            }
        }
        return true;
    }

    private static boolean loaded(ClientLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
            && level.getWorldBorder().isWithinBounds(pos);
    }

    private static boolean hazardous(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
            || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
            || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.POINTED_DRIPSTONE);
    }

    private static double square(double value) {
        return value * value;
    }
}
