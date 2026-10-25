package dev.tntcoating.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded scan for portal blocks in front of a flight. Never loads a chunk. */
public final class PortalLookahead {
    public interface Probe {
        boolean loaded(int chunkX, int chunkZ);
        boolean portal(BlockPos pos);
    }

    public record Hit(double distance, BlockPos pos) {}

    private PortalLookahead() {}

    /** Returns the nearest portal block intersecting the swept body, or empty. */
    public static Optional<Hit> first(AABB body, Vec3 direction, double maxDistance, Probe probe) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(probe, "probe");
        if (!Double.isFinite(maxDistance) || maxDistance <= 0) {
            throw new IllegalArgumentException("Portal lookahead distance must be positive.");
        }
        if (!Double.isFinite(direction.x) || !Double.isFinite(direction.z)
            || Math.abs(direction.x) + Math.abs(direction.z) < 1e-9) {
            throw new IllegalArgumentException("Portal lookahead needs a horizontal direction.");
        }

        Vec3 flat = new Vec3(direction.x, 0, direction.z).normalize();
        Vec3 displacement = flat.scale(maxDistance);
        AABB sweep = body.expandTowards(displacement);

        int minX = (int) Math.floor(sweep.minX);
        int maxX = (int) Math.floor(sweep.maxX);
        int minY = (int) Math.floor(sweep.minY);
        int maxY = (int) Math.floor(sweep.maxY);
        int minZ = (int) Math.floor(sweep.minZ);
        int maxZ = (int) Math.floor(sweep.maxZ);

        Hit best = null;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!probe.loaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!probe.portal(pos)) continue;
                    OptionalDouble fraction = SweptBlockIntersection.entryFraction(body, displacement,
                        new AABB(x, y, z, x + 1, y + 1, z + 1));
                    if (fraction.isEmpty()) continue;
                    double distance = fraction.getAsDouble() * maxDistance;
                    if (best == null || distance < best.distance()) {
                        best = new Hit(distance, pos.immutable());
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** True when the body currently intersects a loaded portal block. */
    public static boolean contains(AABB body, Probe probe) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(probe, "probe");
        int minX = (int) Math.floor(body.minX);
        int maxX = (int) Math.floor(body.maxX);
        int minY = (int) Math.floor(body.minY);
        int maxY = (int) Math.floor(body.maxY);
        int minZ = (int) Math.floor(body.minZ);
        int maxZ = (int) Math.floor(body.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!probe.loaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (probe.portal(pos) && body.intersects(new AABB(
                        pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}