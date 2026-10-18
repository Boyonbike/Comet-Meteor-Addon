package dev.tntcoating.core;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Builds a horizontal scan body without treating a clipped jump as a horizontal wall. */
public final class AdaptiveSpeedTerrain {
    private static final double FEET_EPSILON = 1e-6;
    private static final double MAX_RISE = 4;

    private AdaptiveSpeedTerrain() {}

    /**
     * The supplied shapes cover the current footprint expanded by the positive rise, capped at four
     * blocks. The caller must verify that footprint's chunks are loaded before obtaining the shapes.
     * Minecraft resolves vertical collisions first, so clip a jump before extending the horizontal
     * scan upward. Descending never expands below the feet; normal physics resolves landing.
     * Invalid inputs return null, which {@link FlightCorridor} rejects without probing the world.
     */
    public static AABB sweepBody(AABB body, double verticalMotion, Iterable<VoxelShape> verticalCollisions) {
        if (!valid(body) || !Double.isFinite(verticalMotion) || verticalCollisions == null) return null;
        double minY = body.minY + FEET_EPSILON;
        if (minY >= body.maxY) return null;
        double rise = Math.max(0, Math.min(MAX_RISE, verticalMotion));
        if (rise > 0) {
            double resolved = Shapes.collide(Direction.Axis.Y, body, verticalCollisions, rise);
            if (!Double.isFinite(resolved) || resolved < 0 || resolved > rise) return null;
            rise = resolved;
        }
        return new AABB(body.minX, minY, body.minZ, body.maxX, body.maxY + rise, body.maxZ);
    }

    private static boolean valid(AABB body) {
        return body != null
            && Double.isFinite(body.minX) && Double.isFinite(body.minY) && Double.isFinite(body.minZ)
            && Double.isFinite(body.maxX) && Double.isFinite(body.maxY) && Double.isFinite(body.maxZ)
            && body.maxX > body.minX && body.maxY > body.minY && body.maxZ > body.minZ
            && body.maxX - body.minX <= 16 && body.maxY - body.minY <= 16 && body.maxZ - body.minZ <= 16;
    }
}
