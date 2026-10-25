package dev.tntcoating.core;

import java.util.Objects;
import java.util.OptionalDouble;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Exact intersection of a translating axis-aligned body with an obstacle. */
public final class SweptBlockIntersection {
    private SweptBlockIntersection() {}

    /**
     * Returns the entry fraction of {@code displacement}, in [0, 1), or empty if the body
     * never overlaps the obstacle during the sweep. Existing overlap returns zero.
     * Touching a face or edge without entering the obstacle does not count as overlap.
     */
    public static OptionalDouble entryFraction(AABB body, Vec3 displacement, AABB obstacle) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(displacement, "displacement");
        Objects.requireNonNull(obstacle, "obstacle");
        double[] bodyMin = {body.minX, body.minY, body.minZ};
        double[] bodyMax = {body.maxX, body.maxY, body.maxZ};
        double[] obstacleMin = {obstacle.minX, obstacle.minY, obstacle.minZ};
        double[] obstacleMax = {obstacle.maxX, obstacle.maxY, obstacle.maxZ};
        double[] movement = {displacement.x, displacement.y, displacement.z};
        double entry = 0;
        double exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(movement[axis])) {
                throw new IllegalArgumentException("Sweep displacement must be finite.");
            }
            if (movement[axis] == 0) {
                if (bodyMax[axis] <= obstacleMin[axis] || bodyMin[axis] >= obstacleMax[axis]) {
                    return OptionalDouble.empty();
                }
                continue;
            }
            double first = (obstacleMin[axis] - bodyMax[axis]) / movement[axis];
            double last = (obstacleMax[axis] - bodyMin[axis]) / movement[axis];
            entry = Math.max(entry, Math.min(first, last));
            exit = Math.min(exit, Math.max(first, last));
            if (entry >= exit) return OptionalDouble.empty();
        }
        return OptionalDouble.of(entry);
    }
}
