package dev.tntcoating.core;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Fluid checks for a complete player body, independent of solid collision shapes. */
public final class HighwayLavaSafety {
    public interface Probe {
        boolean loaded(int chunkX, int chunkZ);
        boolean lava(BlockPos pos);

        /** World-space fluid volume, or null when the cell contains no lava. */
        default AABB lavaBounds(BlockPos pos) {
            return lava(pos) ? new AABB(pos) : null;
        }
    }

    private HighwayLavaSafety() {}

    /**
     * Checks a finite, bounded movement supplied by the caller; zero movement checks the current body.
     * The swept bounding box selects candidates, while exact intersection excludes diagonal side cells.
     * Fluid bounds can represent the actual surface height even when lava has no solid shape.
     */
    public static boolean intersects(AABB body, Vec3 displacement, Probe probe) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(displacement, "displacement");
        Objects.requireNonNull(probe, "probe");
        AABB sweep = body.expandTowards(displacement);
        int minX = (int) Math.floor(sweep.minX);
        int maxX = (int) Math.floor(Math.nextDown(sweep.maxX));
        int minY = (int) Math.floor(sweep.minY);
        int maxY = (int) Math.floor(Math.nextDown(sweep.maxY));
        int minZ = (int) Math.floor(sweep.minZ);
        int maxZ = (int) Math.floor(Math.nextDown(sweep.maxZ));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!probe.loaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (SweptBlockIntersection.entryFraction(body, displacement, new AABB(pos)).isEmpty()) continue;
                    AABB lava = probe.lavaBounds(pos);
                    if (lava != null && SweptBlockIntersection.entryFraction(body, displacement, lava).isPresent()) return true;
                }
            }
        }
        return false;
    }
}
