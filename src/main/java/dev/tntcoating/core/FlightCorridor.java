package dev.tntcoating.core;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded, conservative checks of the complete player body along a flight path. */
public final class FlightCorridor {
    public static final double MAX_DISTANCE = 64;
    public static final double SEGMENT_LENGTH = 2;
    public static final int MAX_SEGMENTS = 32;
    public static final int REFINEMENT_STEPS = 8;
    public static final int MAX_CHUNKS_PER_SEGMENT = 16;

    private FlightCorridor() {}

    public interface Probe {
        /** A non-loading lookup: implementations must never request or generate a chunk. */
        boolean loaded(int chunkX, int chunkZ);

        /** Called only after every chunk covered by this swept box is loaded. */
        boolean clear(AABB sweptSegment);

        /** Keeps this probe's non-loading chunk checks and treats every block as clear. */
        static Probe chunksOnly(Probe probe) {
            return new Probe() {
                @Override public boolean loaded(int chunkX, int chunkZ) { return probe.loaded(chunkX, chunkZ); }
                @Override public boolean clear(AABB sweptSegment) { return true; }
            };
        }
    }

    public enum Reason { CLEAR, CHUNKS, OBSTACLE, INVALID }

    /** Counts include the bounded refinement checks; CLEAR can cover a capped horizon. */
    public record Result(double clearDistance, Reason reason, int segments,
                         int chunkChecks, int collisionChecks) {}

    /**
     * The fastest speed that can still stop within {@code clearance} at {@code braking} per tick, reserving this
     * tick's travel as well as the stopping distance, and never more than the clearance itself.
     */
    public static double safeSpeed(double braking, double clearance) {
        // sqrt(b*b + 2*b*d) - b, in an equivalent form that avoids cancellation and overflow.
        double rootBraking = Math.sqrt(braking);
        double twiceClearance = 2 * clearance;
        double brakingSpeed = rootBraking * (twiceClearance
            / (Math.hypot(rootBraking, Math.sqrt(twiceClearance)) + rootBraking));
        return Math.min(clearance, brakingSpeed);
    }

    /**
     * A downward physical move may land on shapes wholly below the current feet:
     * Minecraft's normal movement collision resolves touchdown. Shapes extending above
     * the feet remain obstacles, as do all shapes during level or ascending movement.
     */
    public static boolean floorContactAllowed(AABB playerBox, Vec3 motion, double obstacleTop) {
        return valid(playerBox) && motion != null
            && finite(motion.x, motion.y, motion.z, obstacleTop)
            && motion.y < 0 && obstacleTop <= playerBox.minY + 1e-7;
    }

    /** Checks only the current body, so an unsafe starting overlap can be handled separately. */
    public static Result inspectStart(AABB playerBox, Probe probe) {
        if (!valid(playerBox) || probe == null) return new Result(0, Reason.INVALID, 0, 0, 0);
        Counts counts = new Counts();
        Reason reason = check(playerBox, Vec3.ZERO, 0, 0, probe, counts);
        return counts.result(0, reason);
    }

    /**
     * Checks up to 64 blocks along the normalized three-dimensional direction. Each swept
     * segment is the union of the start and end player boxes, so diagonal checks are
     * deliberately conservative. The first unsafe segment is refined to within 1/128
     * block, allowing slow movement close to a wall or unloaded chunk boundary.
     *
     * At most 40 segments, 640 chunk lookups and 40 collision probes are performed.
     * Non-finite inputs and implausibly large player boxes fail closed without probing.
     */
    public static Result inspect(AABB playerBox, Vec3 direction, double distance, Probe probe) {
        if (!valid(playerBox) || direction == null || probe == null
            || !finite(direction.x, direction.y, direction.z, distance) || distance < 0) {
            return new Result(0, Reason.INVALID, 0, 0, 0);
        }

        double length = Math.hypot(Math.hypot(direction.x, direction.y), direction.z);
        if (!Double.isFinite(length)) return new Result(0, Reason.INVALID, 0, 0, 0);
        if (distance == 0 || length == 0) return new Result(0, Reason.CLEAR, 0, 0, 0);

        Vec3 unit = new Vec3(direction.x / length, direction.y / length, direction.z / length);
        double horizon = Math.min(distance, MAX_DISTANCE);
        Counts counts = new Counts();
        double start = 0;
        for (int index = 0; index < MAX_SEGMENTS && start < horizon; index++) {
            double end = Math.min(horizon, (index + 1) * SEGMENT_LENGTH);
            Reason reason = check(playerBox, unit, start, end, probe, counts);
            if (reason != Reason.CLEAR) {
                double safe = start;
                double unsafe = end;
                for (int refinement = 0; refinement < REFINEMENT_STEPS; refinement++) {
                    double middle = (safe + unsafe) * .5;
                    // Keep the prefix anchored at the last wholly safe segment. This
                    // cannot jump over an obstruction found in an earlier refinement.
                    Reason refined = check(playerBox, unit, start, middle, probe, counts);
                    if (refined == Reason.CLEAR) safe = middle;
                    else {
                        unsafe = middle;
                        reason = refined;
                    }
                }
                return counts.result(safe, reason);
            }
            start = end;
        }
        return counts.result(start, Reason.CLEAR);
    }

    private static Reason check(AABB body, Vec3 unit, double start, double end,
                                Probe probe, Counts counts) {
        counts.segments++;
        AABB from = body.move(unit.x * start, unit.y * start, unit.z * start);
        AABB to = body.move(unit.x * end, unit.y * end, unit.z * end);
        AABB swept = new AABB(
            Math.min(from.minX, to.minX), Math.min(from.minY, to.minY), Math.min(from.minZ, to.minZ),
            Math.max(from.maxX, to.maxX), Math.max(from.maxY, to.maxY), Math.max(from.maxZ, to.maxZ)
        );
        double minX = Math.floor(swept.minX / 16);
        double minZ = Math.floor(swept.minZ / 16);
        // AABB maxima are exclusive. nextDown after division also handles max == 0.
        double maxX = Math.floor(Math.nextDown(swept.maxX / 16));
        double maxZ = Math.floor(Math.nextDown(swept.maxZ / 16));
        if (minX < Integer.MIN_VALUE || minZ < Integer.MIN_VALUE
            || maxX > Integer.MAX_VALUE || maxZ > Integer.MAX_VALUE
            || maxX < minX || maxZ < minZ
            || (maxX - minX + 1) * (maxZ - minZ + 1) > MAX_CHUNKS_PER_SEGMENT) {
            return Reason.INVALID;
        }

        // Long counters cannot wrap at the int chunk coordinate boundary.
        for (long x = (long) minX; x <= (long) maxX; x++) {
            for (long z = (long) minZ; z <= (long) maxZ; z++) {
                counts.chunkChecks++;
                if (!probe.loaded((int) x, (int) z)) return Reason.CHUNKS;
            }
        }
        counts.collisionChecks++;
        return probe.clear(swept) ? Reason.CLEAR : Reason.OBSTACLE;
    }

    private static boolean valid(AABB box) {
        return box != null && finite(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
            && box.maxX > box.minX && box.maxY > box.minY && box.maxZ > box.minZ
            && box.maxX - box.minX <= 16 && box.maxY - box.minY <= 16 && box.maxZ - box.minZ <= 16;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static final class Counts {
        int segments;
        int chunkChecks;
        int collisionChecks;

        Result result(double distance, Reason reason) {
            return new Result(distance, reason, segments, chunkChecks, collisionChecks);
        }
    }
}
