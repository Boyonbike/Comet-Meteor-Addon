package dev.tntcoating.core;

/**
 * The eight highways from spawn: both axes and both diagonals. Their old chunks are wide, endless and long since
 * searched, so a trail that joins one is treated as a gap rather than followed outward along it.
 */
public final class TrailHighways {
    /** Half-width in blocks: an old highway band is about a 1.12 view distance either side. */
    public static final double HALF_WIDTH = 192;
    /** Spawn itself is not a highway. */
    private static final double SPAWN_RADIUS = 1_000;

    private TrailHighways() {}

    /** Whether the chunk's centre lies within HALF_WIDTH of an axis or diagonal, away from spawn. */
    public static boolean near(int chunkX, int chunkZ) {
        double x = chunkX * 16.0 + 8, z = chunkZ * 16.0 + 8;
        if (Math.hypot(x, z) < SPAWN_RADIUS) return false;
        double ax = Math.abs(x), az = Math.abs(z);
        return ax <= HALF_WIDTH || az <= HALF_WIDTH || Math.abs(ax - az) / Math.sqrt(2) <= HALF_WIDTH;
    }
}
