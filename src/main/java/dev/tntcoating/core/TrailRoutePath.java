package dev.tntcoating.core;

import java.util.List;
import java.util.Objects;

/** Checks the calculated flight polyline, including terrain detours that a straight heading check cannot see. */
public final class TrailRoutePath {
    public static final int MAX_POINTS = 4_096;
    private static final double SAMPLE_DISTANCE = 8;
    private static final double MAX_PATH_OFFSET = 64;

    private TrailRoutePath() {}

    public record Point(double x, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(z)
                || Math.floor(x / 16) < Integer.MIN_VALUE || Math.floor(x / 16) > Integer.MAX_VALUE
                || Math.floor(z / 16) < Integer.MIN_VALUE || Math.floor(z / 16) > Integer.MAX_VALUE)
                throw new IllegalArgumentException("path position must have finite integer chunk coordinates");
        }
    }

    /**
     * Join the closest point on the current path and check the next lookAhead blocks of arc length.
     * Only the initial departure distance and the original player cell are exempt from the narrow check; later
     * small segments cannot restart that exemption. Each segment also checks the wider trail
     * corridor without distance grace, so curved paths cannot sneak back alongside a flown trail.
     * Each segment is checked against routes flown roughly parallel to it; steep crossings are allowed.
     * A path more than 64 blocks from the player cannot be certified safe.
     */
    public static boolean wouldRevisit(TrailRouteMemory memory, List<Point> path,
                                       double playerX, double playerZ, double lookAhead) {
        Objects.requireNonNull(memory);
        Objects.requireNonNull(path);
        Point player = new Point(playerX, playerZ);
        if (!Double.isFinite(lookAhead) || lookAhead < 1 || lookAhead > 1_024)
            throw new IllegalArgumentException("look-ahead must be between 1 and 1024 blocks");
        int count = Math.min(path.size(), MAX_POINTS);
        if (count < 2) return false;
        if (memory.isFull()) return true;

        int closestSegment = 0;
        Point closest = path.getFirst();
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < count; i++) {
            Point start = Objects.requireNonNull(path.get(i));
            Point end = Objects.requireNonNull(path.get(i + 1));
            double dx = end.x - start.x;
            double dz = end.z - start.z;
            double squaredLength = dx * dx + dz * dz;
            double fraction = squaredLength == 0 ? 0 : Math.clamp(
                ((playerX - start.x) * dx + (playerZ - start.z) * dz) / squaredLength, 0, 1);
            Point projected = new Point(start.x + dx * fraction, start.z + dz * fraction);
            double distance = Math.hypot(projected.x - playerX, projected.z - playerZ);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestSegment = i;
                closest = projected;
            }
        }
        if (closestDistance > MAX_PATH_OFFSET) return true;
        int originX = chunk(playerX);
        int originZ = chunk(playerZ);
        var directHits = new java.util.BitSet();
        double walked = 0;
        Point from = player;
        // Include the short connector onto the path, then follow its original travel order.
        for (int i = closestSegment; i < count; i++) {
            Point to = i == closestSegment ? closest : path.get(i);
            double length = Math.hypot(to.x - from.x, to.z - from.z);
            double checkedLength = Math.min(length, lookAhead - walked);
            if (checkedLength > 0) {
                double checkedFraction = checkedLength / length;
                if (memory.wouldParallelRetrace(from.x, from.z,
                    from.x + (to.x - from.x) * checkedFraction,
                    from.z + (to.z - from.z) * checkedFraction)) return true;
                double first = Math.max(0, TrailRouteMemory.DEFAULT_START_GRACE_BLOCKS - walked);
                int sector = TrailRouteMemory.sector(to.x - from.x, to.z - from.z);
                if (first <= checkedLength) {
                    int steps = Math.max(1, (int) Math.ceil((checkedLength - first) / SAMPLE_DISTANCE));
                    for (int step = 0; step <= steps; step++) {
                        double distance = first + (checkedLength - first) * step / steps;
                        double fraction = distance / length;
                        double x = from.x + (to.x - from.x) * fraction;
                        double z = from.z + (to.z - from.z) * fraction;
                        // A parallel route directly under the path for 32 blocks of arc, not one stray cell.
                        if (memory.isNearParallelChunkExcept(chunk(x), chunk(z),
                            TrailRouteMemory.DEFAULT_CORRIDOR_CHUNKS, sector, originX, originZ)) {
                            directHits.set((int) ((walked + distance) / 16));
                            if (directHits.cardinality() >= TrailRouteMemory.MIN_DIRECT_BINS) return true;
                        }
                    }
                }
                walked += checkedLength;
            }
            if (walked >= lookAhead) return false;
            from = to;
        }
        return false;
    }

    private static int chunk(double coordinate) { return (int) Math.floor(coordinate / 16); }
}
