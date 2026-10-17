package dev.tntcoating.core;

import java.util.Objects;

/**
 * Trail Hunter's per-tick decision loop, without Minecraft classes, so whole flights can be simulated.
 * Chunk classifications go in; one heading comes out each tick. Exploration reads a map of classifications;
 * with exploration off, the arrival-based tracker follows a single trail instead.
 */
public final class TrailNavigator {
    /** Extra per-location heading rejections, such as Nether paths already found unsafe. */
    public interface HeadingFilter { boolean blocked(double x, double z, float yaw); }

    private final boolean explore;
    private final TrailExplorer explorer;
    private final TrailTracker tracker;
    private TrailTracker.Snapshot snapshot;
    private TrailExplorer.Decision decision;

    public TrailNavigator(TrailRouteMemory memory, TrailTracker.Config trackerConfig,
                          TrailExplorer.Config explorerConfig, boolean explore, HeadingFilter filter) {
        Objects.requireNonNull(memory);
        Objects.requireNonNull(filter);
        this.explore = explore;
        tracker = explore ? null : new TrailTracker(trackerConfig);
        explorer = !explore ? null : new TrailExplorer(new TrailExplorer.RouteAccess() {
            @Override public boolean wouldRevisit(double x, double z, float yaw, double lookAhead) {
                if (filter.blocked(x, z, yaw)) return true;
                double radians = Math.toRadians(yaw);
                return memory.wouldRetrace(x, z, x - Math.sin(radians) * lookAhead, z + Math.cos(radians) * lookAhead);
            }

            @Override public boolean flownNear(int chunkX, int chunkZ, int radiusChunks) {
                return memory.isNearEarlierFlight(chunkX, chunkZ, radiusChunks);
            }

            @Override public int overlap(double x, double z, float yaw, double lookAhead) {
                double radians = Math.toRadians(yaw);
                int overlap = memory.parallelOverlap(x, z, x - Math.sin(radians) * lookAhead, z + Math.cos(radians) * lookAhead);
                return filter.blocked(x, z, yaw) && overlap < Integer.MAX_VALUE ? overlap + 1_000 : overlap;
            }
        }, explorerConfig);
    }

    public boolean exploring() { return explore; }

    public void setPreferOutbound(boolean preferOutbound) {
        if (explorer != null) explorer.setPreferOutbound(preferOutbound);
    }

    public void setPattern(TrailExplorer.Pattern pattern, double spiralSpacing) {
        if (explorer != null) explorer.setPattern(pattern, spiralSpacing);
    }

    /**
     * Cached knowledge, not an arrival. A chunk that is neither trail nor explicit frontier is unknown and
     * forgets any earlier entry.
     */
    public void observe(int chunkX, int chunkZ, boolean trail, boolean frontier) {
        if (explorer == null) return;
        if (trail || frontier) explorer.observe(chunkX, chunkZ, trail);
        else explorer.forget(chunkX, chunkZ);
    }

    /** A newly received chunk. Exploration maps it; the single-trail tracker counts trail arrivals. */
    public void arrive(int chunkX, int chunkZ, boolean trail, boolean frontier, long now, long receivedAtMillis,
                       double x, double z, float travelYaw) {
        observe(chunkX, chunkZ, trail, frontier);
        if (tracker != null && trail) tracker.onTrailChunk(chunkX, chunkZ, now, receivedAtMillis, x, z, travelYaw);
    }

    /** Single-trail mode: advance the arrival tracker. */
    public TrailTracker.Snapshot track(long now, double x, double z, float travelYaw) {
        if (tracker == null) throw new IllegalStateException("tracking is off while exploring");
        snapshot = tracker.update(now, x, z, travelYaw);
        return snapshot;
    }

    /** Exploration: choose this tick's heading. A blocked decision means every checked exit repeats a flown route. */
    public TrailExplorer.Decision explore(long now, double x, double z, float travelYaw) {
        if (explorer == null) throw new IllegalStateException("exploration is off");
        decision = explorer.next(now, x, z, travelYaw);
        return decision;
    }

    public TrailTracker.Snapshot snapshot() { return snapshot; }
    public TrailExplorer.Decision decision() { return decision; }
    public double trailBlocks() { return explorer == null ? 0 : explorer.trailBlocks(); }
    public boolean relocating() { return explorer != null && explorer.relocating(); }
    TrailExplorer explorer() { return explorer; }
}
