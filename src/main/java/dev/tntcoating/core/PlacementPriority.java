package dev.tntcoating.core;

/** Holds coating while a higher-priority interaction is queued, pending, or settling. */
public final class PlacementPriority {
    private boolean reserved;
    private int resumeAt;
    public void reserve() { reserved = true; }
    public void resolve(int tick, int settleTicks) { reserved = false; resumeAt = tick + settleTicks; }
    public boolean blocks(int tick) { return reserved || tick < resumeAt; }
    public void reset() { reserved = false; resumeAt = 0; }
}
