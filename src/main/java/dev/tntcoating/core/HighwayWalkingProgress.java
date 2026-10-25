package dev.tntcoating.core;

/** Detects stalled walking without requiring a detour to approach the destination every tick. */
public final class HighwayWalkingProgress {
    private Object path;
    private int furthestPosition;
    private int stalledTicks;

    public void reset() {
        path = null;
        furthestPosition = 0;
        stalledTicks = 0;
    }

    /** A replan establishes a baseline; only completing a new movement restarts the timeout. */
    public boolean stalled(Object currentPath, int position, int limitTicks) {
        if (limitTicks <= 0) throw new IllegalArgumentException("Walking timeout must be positive.");
        if (currentPath != null) {
            if (currentPath != path) {
                path = currentPath;
                furthestPosition = position;
            } else if (position > furthestPosition) {
                furthestPosition = position;
                stalledTicks = 0;
                return false;
            }
        }
        if (stalledTicks < Integer.MAX_VALUE) stalledTicks++;
        return stalledTicks >= limitTicks;
    }
}
