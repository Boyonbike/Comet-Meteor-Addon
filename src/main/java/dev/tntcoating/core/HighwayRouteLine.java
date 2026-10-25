package dev.tntcoating.core;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A stable cardinal or diagonal highway line anchored at the configured destination. */
public final class HighwayRouteLine {
    private static final double LINE_EPSILON = 1e-7;
    private final Vec3 destination;
    private final double directionX;
    private final double directionZ;

    private HighwayRouteLine(Vec3 destination, int stepX, int stepZ) {
        this.destination = destination;
        double length = Math.hypot(stepX, stepZ);
        directionX = stepX / length;
        directionZ = stepZ / length;
    }

    /** Chooses the nearest of eight standard headings once, with its line through the destination. */
    public static HighwayRouteLine from(Vec3 start, Vec3 destination) {
        requireFinite(start);
        requireFinite(destination);
        double angle = Math.atan2(destination.z - start.z, destination.x - start.x);
        int heading = Math.floorMod((int) Math.round(angle / (Math.PI / 4)), 8);
        return switch (heading) {
            case 0 -> new HighwayRouteLine(destination, 1, 0);
            case 1 -> new HighwayRouteLine(destination, 1, 1);
            case 2 -> new HighwayRouteLine(destination, 0, 1);
            case 3 -> new HighwayRouteLine(destination, -1, 1);
            case 4 -> new HighwayRouteLine(destination, -1, 0);
            case 5 -> new HighwayRouteLine(destination, -1, -1);
            case 6 -> new HighwayRouteLine(destination, 0, -1);
            default -> new HighwayRouteLine(destination, 1, -1);
        };
    }

    /** Projects onto the stable line, then advances the requested distance without passing the destination. */
    public Vec3 target(Vec3 position, double forwardDistance) {
        requireFinite(position);
        if (!Double.isFinite(forwardDistance) || forwardDistance < 0) {
            throw new IllegalArgumentException("Forward distance must be finite and nonnegative.");
        }
        double along = (position.x - destination.x) * directionX
            + (position.z - destination.z) * directionZ;
        double targetAlong = Math.min(0, along + forwardDistance);
        return new Vec3(destination.x + directionX * targetAlong, destination.y,
            destination.z + directionZ * targetAlong);
    }

    /** Restricts standing goals to block centers on the same line; their ground height may vary. */
    public boolean onLine(BlockPos feet) {
        Objects.requireNonNull(feet, "feet");
        double lateral = (feet.getX() + .5 - destination.x) * directionZ
            - (feet.getZ() + .5 - destination.z) * directionX;
        return Math.abs(lateral) <= LINE_EPSILON;
    }

    private static void requireFinite(Vec3 position) {
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
            throw new IllegalArgumentException("Highway coordinates must be finite.");
        }
    }
}
