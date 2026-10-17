package dev.tntcoating.core;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Direction-aware memory of flown chunk corridors, with no Minecraft dependency. Each chunk keeps the undirected
 * directions it was flown in, as sixteen 11.25-degree sectors. Flying along or alongside a remembered route, in
 * either direction, is a retrace; crossing one at a steep angle is not, so long sessions cannot fence themselves in.
 */
public final class TrailRouteMemory {
    public static final int MAX_ENTRIES = 1_000_000;
    public static final int DEFAULT_CORRIDOR_CHUNKS = 2;
    public static final int TRAIL_CORRIDOR_CHUNKS = 8;
    public static final double DEFAULT_START_GRACE_BLOCKS = 80;
    static final int SECTORS = 16;
    /** Sectors either side that still count as the same route: up to about 50 degrees apart. */
    private static final int PARALLEL_SECTORS = 4;
    private static final int DIRECTIONS = (1 << SECTORS) - 1;
    /** A cell remembered without a direction (a single point, or a version 1 save) matches every direction. */
    static final int UNKNOWN_DIRECTION = 1 << SECTORS;
    /** First recorded since this memory was loaded; never saved. */
    private static final int THIS_SESSION = 1 << (SECTORS + 1);
    // Recording stores chunk cells, so their centres can lie a little ahead of the player.
    private static final double CELL_PROJECTION_GRACE = Math.sqrt(2) * 8 + 1e-6;
    // A missed tick can span many chunks; a teleport must not reserve the intervening world.
    private static final double MAX_CONTINUOUS_MOVE = 1_024;
    private static final double MAX_QUERY_DISTANCE = 65_536;
    private static final double MIN_DIRECTION_MOVE = 1e-6;
    /**
     * Parallel history must span this many 16-block steps along the proposed direction to count as a route, so a
     * brief hop during an old turn cannot close a whole direction: 64 blocks alongside, 32 directly underneath.
     */
    static final int MIN_PARALLEL_BINS = 4;
    static final int MIN_DIRECT_BINS = 2;

    /** Chunk key to direction bits; 0 means absent. */
    private final Long2IntOpenHashMap visited = new Long2IntOpenHashMap();
    /** 8x8-chunk regions holding any flown chunk, so most "near a flight?" questions need a few lookups. */
    private final LongOpenHashSet regions = new LongOpenHashSet();
    private static final int REGION_SHIFT = 3;
    private final int capacity;
    private double lastX;
    private double lastZ;
    private boolean hasLast;
    private long revision;

    public TrailRouteMemory() { this(MAX_ENTRIES); }

    public TrailRouteMemory(int capacity) {
        if (capacity < 1 || capacity > MAX_ENTRIES)
            throw new IllegalArgumentException("route capacity must be between 1 and " + MAX_ENTRIES);
        this.capacity = capacity;
        visited.defaultReturnValue(0);
    }

    /** Record actual movement only; loaded map chunks are not flown routes. */
    public void recordPosition(double x, double z) {
        validatePosition(x, z);
        double distance = hasLast ? Math.hypot(x - lastX, z - lastZ) : Double.POSITIVE_INFINITY;
        if (distance <= MAX_CONTINUOUS_MOVE) {
            int direction = distance > MIN_DIRECTION_MOVE ? 1 << sector(x - lastX, z - lastZ) : 0;
            traverse(lastX, lastZ, x, z, (cx, cz, t) -> {
                remember(cx, cz, direction);
                return true;
            });
        } else remember(chunk(x), chunk(z), 0);
        lastX = x;
        lastZ = z;
        hasLast = true;
    }

    public void record(double x, double z) { recordPosition(x, z); }

    /** Break interpolation at reconnect, dimension change, or a new flight. History stays intact. */
    public void breakContinuity() { hasLast = false; }

    /**
     * Complete the final movement into touchdown or a module stop, then end that flight segment.
     * Repeated calls while grounded do not reserve walking routes or connect separate flights.
     */
    public void finishPosition(double x, double z) {
        validatePosition(x, z);
        if (hasLast) recordPosition(x, z);
        breakContinuity();
    }

    /** Reserve one chunk without a known direction; it then blocks travel in every direction. */
    public void rememberTrailChunk(int chunkX, int chunkZ) { remember(chunkX, chunkZ, 0); }

    public boolean isVisitedChunk(int chunkX, int chunkZ) {
        return visited.get(key(chunkX, chunkZ)) != 0;
    }

    /** The square corridor also catches nearby parallel passes over the same trail, in any direction. */
    public boolean isNearVisitedChunk(int chunkX, int chunkZ, int radiusChunks) {
        return near(chunkX, chunkZ, radiusChunks, false, 0, -1);
    }

    /** Exclude exactly the flight's original occupied cell, including across a curved path. */
    public boolean isNearVisitedChunkExcept(int chunkX, int chunkZ, int radiusChunks,
                                           int excludedChunkX, int excludedChunkZ) {
        return near(chunkX, chunkZ, radiusChunks, true, key(excludedChunkX, excludedChunkZ), -1);
    }

    /**
     * Starts a new session: everything recorded so far, including before a module toggle while this memory stayed
     * loaded, now counts as an earlier flight for {@link #isNearEarlierFlight}.
     */
    public void startSession() {
        for (var iterator = visited.long2IntEntrySet().fastIterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if ((entry.getIntValue() & THIS_SESSION) != 0) entry.setValue(entry.getIntValue() & ~THIS_SESSION);
        }
    }

    /** Whether a flight before this session passed within radiusChunks; this session's own path is excluded. */
    public boolean isNearEarlierFlight(int chunkX, int chunkZ, int radiusChunks) {
        validateRadius(radiusChunks);
        if (!nearRegion(chunkX, chunkZ, radiusChunks)) return false;
        for (long x = (long) chunkX - radiusChunks; x <= (long) chunkX + radiusChunks; x++) {
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) continue;
            for (long z = (long) chunkZ - radiusChunks; z <= (long) chunkZ + radiusChunks; z++) {
                if (z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                int directions = visited.get(key((int) x, (int) z));
                if (directions != 0 && (directions & THIS_SESSION) == 0) return true;
            }
        }
        return false;
    }

    /** As {@link #isNearVisitedChunkExcept}, counting only cells flown roughly parallel to the given sector. */
    boolean isNearParallelChunkExcept(int chunkX, int chunkZ, int radiusChunks, int sector,
                                      int excludedChunkX, int excludedChunkZ) {
        return near(chunkX, chunkZ, radiusChunks, true, key(excludedChunkX, excludedChunkZ), sector);
    }

    private boolean near(int chunkX, int chunkZ, int radiusChunks, boolean hasExcluded, long excluded, int sector) {
        validateRadius(radiusChunks);
        if (!nearRegion(chunkX, chunkZ, radiusChunks)) return false;
        for (long x = (long) chunkX - radiusChunks; x <= (long) chunkX + radiusChunks; x++) {
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) continue;
            for (long z = (long) chunkZ - radiusChunks; z <= (long) chunkZ + radiusChunks; z++) {
                if (z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                long candidate = key((int) x, (int) z);
                if (hasExcluded && candidate == excluded) continue;
                int directions = visited.get(candidate);
                if (directions != 0 && (sector < 0 || parallel(directions, sector))) return true;
            }
        }
        return false;
    }

    /**
     * Whether flying from one point to another would repeat a remembered route: along it, back down it, or alongside
     * it within eight chunks. A steep crossing of a remembered route is allowed.
     */
    public boolean wouldRetrace(double fromX, double fromZ, double toX, double toZ) {
        return wouldRetrace(fromX, fromZ, toX, toZ, DEFAULT_CORRIDOR_CHUNKS, DEFAULT_START_GRACE_BLOCKS)
            || wouldParallelRetrace(fromX, fromZ, toX, toZ);
    }

    /**
     * Avoid the whole flown trail, including a parallel pass 100 blocks to either side. Historical
     * cells behind the proposed direction are safe to depart from: their distance can only increase.
     * Cells ahead within eight chunks are not exempt, even during the initial departure. This lets
     * a flight extend its current frontier or leave sideways without a wide blind grace period that
     * would also permit a U-turn. Only cells flown within about 50 degrees of this direction count,
     * and only once they span 64 blocks along it.
     */
    public boolean wouldParallelRetrace(double fromX, double fromZ, double toX, double toZ) {
        validatePosition(fromX, fromZ);
        validatePosition(toX, toZ);
        double distance = Math.hypot(toX - fromX, toZ - fromZ);
        if (isFull() || distance > MAX_QUERY_DISTANCE) return true;
        if (distance == 0) return false;
        double directionX = (toX - fromX) / distance;
        double directionZ = (toZ - fromZ) / distance;
        int sector = sector(directionX, directionZ);
        var bins = new java.util.BitSet();
        return !traverse(fromX, fromZ, toX, toZ, (cx, cz, entry) -> {
            for (long x = (long) cx - TRAIL_CORRIDOR_CHUNKS; x <= (long) cx + TRAIL_CORRIDOR_CHUNKS; x++) {
                if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) continue;
                for (long z = (long) cz - TRAIL_CORRIDOR_CHUNKS; z <= (long) cz + TRAIL_CORRIDOR_CHUNKS; z++) {
                    if (z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                    int directions = visited.get(key((int) x, (int) z));
                    if (directions == 0 || !parallel(directions, sector)) continue;
                    double forward = (x * 16.0 + 8 - fromX) * directionX
                        + (z * 16.0 + 8 - fromZ) * directionZ;
                    if (forward <= CELL_PROJECTION_GRACE) continue;
                    bins.set((int) (forward / 16));
                    if (bins.cardinality() >= MIN_PARALLEL_BINS) return false;
                }
            }
            return true;
        });
    }

    /**
     * How much of a remembered parallel route lies alongside the segment, in 16-block steps along it; zero when the
     * segment is clear. Full memory reports the maximum, since then nothing can be certified clear.
     */
    public int parallelOverlap(double fromX, double fromZ, double toX, double toZ) {
        validatePosition(fromX, fromZ);
        validatePosition(toX, toZ);
        double distance = Math.hypot(toX - fromX, toZ - fromZ);
        if (isFull() || distance > MAX_QUERY_DISTANCE) return Integer.MAX_VALUE;
        if (distance == 0) return 0;
        double directionX = (toX - fromX) / distance;
        double directionZ = (toZ - fromZ) / distance;
        int sector = sector(directionX, directionZ);
        var bins = new java.util.BitSet();
        traverse(fromX, fromZ, toX, toZ, (cx, cz, entry) -> {
            for (long x = (long) cx - TRAIL_CORRIDOR_CHUNKS; x <= (long) cx + TRAIL_CORRIDOR_CHUNKS; x++) {
                if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) continue;
                for (long z = (long) cz - TRAIL_CORRIDOR_CHUNKS; z <= (long) cz + TRAIL_CORRIDOR_CHUNKS; z++) {
                    if (z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                    int directions = visited.get(key((int) x, (int) z));
                    if (directions == 0 || !parallel(directions, sector)) continue;
                    double forward = (x * 16.0 + 8 - fromX) * directionX + (z * 16.0 + 8 - fromZ) * directionZ;
                    if (forward > CELL_PROJECTION_GRACE) bins.set((int) (forward / 16));
                }
            }
            return true;
        });
        return bins.cardinality();
    }

    /**
     * Test every chunk crossed by the proposed segment for a nearby route flown in the same or the opposite
     * direction, spanning at least 32 blocks along it. The initial grace allows departing the cell just recorded
     * under the player. It does not exclude recent history elsewhere, so reversing or looping back along that
     * history still counts.
     */
    public boolean wouldRetrace(double fromX, double fromZ, double toX, double toZ,
                                int radiusChunks, double startIgnoreBlocks) {
        validatePosition(fromX, fromZ);
        validatePosition(toX, toZ);
        validateRadius(radiusChunks);
        if (!Double.isFinite(startIgnoreBlocks) || startIgnoreBlocks < 0)
            throw new IllegalArgumentException("start grace must be finite and nonnegative");
        double distance = Math.hypot(toX - fromX, toZ - fromZ);
        // Once capacity is reached, no route can be certified safe. Never forget older flights.
        if (isFull() || distance > MAX_QUERY_DISTANCE) return true;
        if (distance <= startIgnoreBlocks) return false;
        int sector = sector(toX - fromX, toZ - fromZ);
        double fraction = startIgnoreBlocks / distance;
        double startX = fromX + (toX - fromX) * fraction;
        double startZ = fromZ + (toZ - fromZ) * fraction;
        // The cell under the player can extend diagonally beyond the grace radius. Exempt
        // only that single cell, never the rest of the recently flown tail.
        int originX = chunk(fromX);
        int originZ = chunk(fromZ);
        long excluded = key(originX, originZ);
        double directionX = (toX - fromX) / distance, directionZ = (toZ - fromZ) / distance;
        var bins = new java.util.BitSet();
        return !traverse(startX, startZ, toX, toZ, (cx, cz, entry) -> {
            for (long x = (long) cx - radiusChunks; x <= (long) cx + radiusChunks; x++) {
                if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) continue;
                for (long z = (long) cz - radiusChunks; z <= (long) cz + radiusChunks; z++) {
                    if (z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
                    long candidate = key((int) x, (int) z);
                    int directions = candidate == excluded ? 0 : visited.get(candidate);
                    if (directions == 0 || !parallel(directions, sector)) continue;
                    double forward = (x * 16.0 + 8 - fromX) * directionX + (z * 16.0 + 8 - fromZ) * directionZ;
                    bins.set((int) Math.max(0, forward / 16));
                    if (bins.cardinality() >= MIN_DIRECT_BINS) return false;
                }
            }
            return true;
        });
    }

    private boolean nearRegion(int chunkX, int chunkZ, int radiusChunks) {
        long minX = ((long) chunkX - radiusChunks) >> REGION_SHIFT, maxX = ((long) chunkX + radiusChunks) >> REGION_SHIFT;
        long minZ = ((long) chunkZ - radiusChunks) >> REGION_SHIFT, maxZ = ((long) chunkZ + radiusChunks) >> REGION_SHIFT;
        for (long x = minX; x <= maxX; x++)
            for (long z = minZ; z <= maxZ; z++)
                if (regions.contains(x << 32 | z & 0xffffffffL)) return true;
        return false;
    }

    public int size() { return visited.size(); }
    public boolean isFull() { return visited.size() >= capacity; }
    public long revision() { return revision; }

    /** Capture on the flight thread; the detached immutable value can be saved by a worker. */
    public Snapshot snapshot() {
        long[] keys = new long[visited.size()];
        int[] directions = new int[keys.length];
        int i = 0;
        for (var entry : visited.long2IntEntrySet()) {
            keys[i] = entry.getLongKey();
            directions[i++] = entry.getIntValue() & ~THIS_SESSION;
        }
        return new Snapshot(revision, keys, directions);
    }

    public static final class Snapshot {
        private final long revision;
        private final long[] keys;
        private final int[] directions;

        private Snapshot(long revision, long[] keys, int[] directions) {
            this.revision = revision;
            this.keys = keys;
            this.directions = directions;
        }

        public long revision() { return revision; }
        public int size() { return keys.length; }
        long[] keys() { return keys; }
        int[] directions() { return directions; }
    }

    /** Version 1 saves have no directions: each cell then blocks travel in every direction, as before. */
    static TrailRouteMemory restore(long[] keys) {
        int[] directions = new int[keys.length];
        java.util.Arrays.fill(directions, UNKNOWN_DIRECTION);
        return restore(keys, directions);
    }

    static TrailRouteMemory restore(long[] keys, int[] directions) {
        if (keys.length != directions.length) throw new IllegalArgumentException("route cell count mismatch");
        TrailRouteMemory memory = new TrailRouteMemory();
        for (int i = 0; i < keys.length; i++) {
            int value = directions[i];
            if (value == 0 || (value & ~(DIRECTIONS | UNKNOWN_DIRECTION)) != 0)
                throw new IllegalArgumentException("invalid route directions");
            if (memory.visited.put(keys[i], value) != 0) throw new IllegalArgumentException("duplicate route cell");
            memory.regions.add(region(keys[i]));
        }
        memory.revision = keys.length;
        return memory;
    }

    private void remember(int cx, int cz, int direction) {
        long key = key(cx, cz);
        int previous = visited.get(key);
        if (previous == 0) {
            if (visited.size() >= capacity) return;
            visited.put(key, (direction == 0 ? UNKNOWN_DIRECTION : direction) | THIS_SESSION);
            regions.add(region(key));
            revision++;
        } else if (direction != 0 && (previous & direction) == 0) {
            // A known direction replaces "unknown" for single points; history is otherwise only added to.
            int updated = ((previous & DIRECTIONS) == 0 ? direction : previous | direction) | (previous & THIS_SESSION);
            visited.put(key, updated);
            revision++;
        }
    }

    /** The undirected sector, 0 to 15, of a direction: opposite directions share one sector. */
    static int sector(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        angle = ((angle % 180) + 180) % 180;
        return Math.min(SECTORS - 1, (int) (angle / (180.0 / SECTORS)));
    }

    private static boolean parallel(int directions, int sector) {
        if ((directions & DIRECTIONS) == 0) return true;
        for (int offset = -PARALLEL_SECTORS; offset <= PARALLEL_SECTORS; offset++)
            if ((directions & 1 << Math.floorMod(sector + offset, SECTORS)) != 0) return true;
        return false;
    }

    private static long key(int cx, int cz) { return (long) cx << 32 | cz & 0xffffffffL; }

    private static long region(long key) {
        long x = (int) (key >> 32) >> REGION_SHIFT, z = (int) key >> REGION_SHIFT;
        return x << 32 | z & 0xffffffffL;
    }

    private static int chunk(double coordinate) { return (int) Math.floor(coordinate / 16); }

    private static void validatePosition(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)
            || Math.floor(x / 16) < Integer.MIN_VALUE || Math.floor(x / 16) > Integer.MAX_VALUE
            || Math.floor(z / 16) < Integer.MIN_VALUE || Math.floor(z / 16) > Integer.MAX_VALUE)
            throw new IllegalArgumentException("position must have finite integer chunk coordinates");
    }

    private static void validateRadius(int radius) {
        if (radius < 0 || radius > 16)
            throw new IllegalArgumentException("corridor radius must be between 0 and 16 chunks");
    }

    @FunctionalInterface
    private interface CellVisitor { boolean visit(int x, int z, double entryFraction); }

    /** Supercover grid traversal: fast diagonal moves reserve the cells on both sides of corners. */
    private static boolean traverse(double fromX, double fromZ, double toX, double toZ, CellVisitor visitor) {
        int x = chunk(fromX);
        int z = chunk(fromZ);
        int endX = chunk(toX);
        int endZ = chunk(toZ);
        if (!visitor.visit(x, z, 0)) return false;
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        int stepX = Double.compare(dx, 0);
        int stepZ = Double.compare(dz, 0);
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16 / Math.abs(dx);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16 / Math.abs(dz);
        double nextX = stepX == 0 ? Double.POSITIVE_INFINITY
            : (((long) x + (stepX > 0 ? 1 : 0)) * 16.0 - fromX) / dx;
        double nextZ = stepZ == 0 ? Double.POSITIVE_INFINITY
            : (((long) z + (stepZ > 0 ? 1 : 0)) * 16.0 - fromZ) / dz;
        while (x != endX || z != endZ) {
            // Roundoff near the final boundary must not walk past the endpoint.
            if (x == endX) nextX = Double.POSITIVE_INFINITY;
            if (z == endZ) nextZ = Double.POSITIVE_INFINITY;
            if (Math.abs(nextX - nextZ) < 1e-12) {
                double entry = Math.min(nextX, nextZ);
                if (!visitor.visit(x + stepX, z, entry) || !visitor.visit(x, z + stepZ, entry)) return false;
                x += stepX;
                z += stepZ;
                if (!visitor.visit(x, z, entry)) return false;
                nextX += deltaX;
                nextZ += deltaZ;
            } else if (nextX < nextZ) {
                x += stepX;
                if (!visitor.visit(x, z, nextX)) return false;
                nextX += deltaX;
            } else {
                z += stepZ;
                if (!visitor.visit(x, z, nextZ)) return false;
                nextZ += deltaZ;
            }
        }
        return true;
    }
}
