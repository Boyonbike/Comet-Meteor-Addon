package dev.tntcoating.core;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Finds the nearest loaded Nether portal block within a bounded box. Never loads a chunk. */
public final class LobbyPortalFinder {
    /** A loaded-chunk and block query supplied by the caller. */
    public interface Probe {
        boolean loaded(int chunkX, int chunkZ);

        /** Palette test for one chunk section; lets the scan skip sections without portal blocks. */
        boolean maybePortal(int chunkX, int sectionY, int chunkZ);

        boolean portal(BlockPos pos);
    }

    public record Result(BlockPos pos, double distanceSq) {
        public Result {
            Objects.requireNonNull(pos, "pos");
            if (!Double.isFinite(distanceSq) || distanceSq < 0) {
                throw new IllegalArgumentException("Portal distance must be finite and non-negative.");
            }
            pos = pos.immutable();
        }
    }

    private LobbyPortalFinder() {}

    /**
     * Returns the nearest portal block inside the horizontal radius and vertical range.
     * Only loaded chunks are inspected, and a section is scanned only when its palette may
     * contain a portal block.
     */
    public static Optional<Result> nearest(
        Vec3 origin,
        int radius,
        int verticalRadius,
        int minY,
        int maxY,
        Probe probe
    ) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(probe, "probe");
        if (!Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z)) {
            throw new IllegalArgumentException("Origin must be finite.");
        }
        if (radius <= 0) throw new IllegalArgumentException("Search radius must be positive.");
        if (verticalRadius < 0) throw new IllegalArgumentException("Vertical radius cannot be negative.");
        if (minY > maxY) throw new IllegalArgumentException("Minimum Y cannot be above maximum Y.");

        int originX = (int) Math.floor(origin.x);
        int originY = (int) Math.floor(origin.y);
        int originZ = (int) Math.floor(origin.z);
        int minX = originX - radius;
        int maxX = originX + radius;
        int minZ = originZ - radius;
        int maxZ = originZ + radius;
        int minBlockY = Math.max(minY, originY - verticalRadius);
        int maxBlockY = Math.min(maxY, originY + verticalRadius);
        if (minBlockY > maxBlockY) return Optional.empty();

        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);
        int minSectionY = Math.floorDiv(minBlockY, 16);
        int maxSectionY = Math.floorDiv(maxBlockY, 16);

        Result best = null;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            int startX = Math.max(minX, chunkX << 4);
            int endX = Math.min(maxX, (chunkX << 4) + 15);
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!probe.loaded(chunkX, chunkZ)) continue;
                int startZ = Math.max(minZ, chunkZ << 4);
                int endZ = Math.min(maxZ, (chunkZ << 4) + 15);

                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    if (!probe.maybePortal(chunkX, sectionY, chunkZ)) continue;
                    int startY = Math.max(minBlockY, sectionY << 4);
                    int endY = Math.min(maxBlockY, (sectionY << 4) + 15);

                    for (int x = startX; x <= endX; x++) {
                        for (int z = startZ; z <= endZ; z++) {
                            for (int y = startY; y <= endY; y++) {
                                BlockPos pos = new BlockPos(x, y, z);
                                if (!probe.portal(pos)) continue;

                                double dx = x + .5 - origin.x;
                                double dy = y + .5 - origin.y;
                                double dz = z + .5 - origin.z;
                                double distanceSq = dx * dx + dy * dy + dz * dz;
                                if (best == null || distanceSq < best.distanceSq() - 1e-9
                                    || (Math.abs(distanceSq - best.distanceSq()) <= 1e-9
                                        && earlier(pos, best.pos()))) {
                                    best = new Result(pos, distanceSq);
                                }
                            }
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Stable tie-break, so equal distances do not depend on chunk iteration order. */
    private static boolean earlier(BlockPos a, BlockPos b) {
        if (a.getY() != b.getY()) return a.getY() < b.getY();
        if (a.getX() != b.getX()) return a.getX() < b.getX();
        return a.getZ() < b.getZ();
    }
}
