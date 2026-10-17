package dev.tntcoating.core;

/** Counts actual simulator ticks, including unlimited trajectories, across a complete rendered frame. */
public final class TrajectoryFrameBudget {
    private int remaining, candidateRemaining, pathRemaining, pathsRemaining;
    private boolean limited;
    private long rotation;

    public void beginFrame(int steps) {
        remaining = Math.max(0, steps);
        candidateRemaining = pathRemaining = pathsRemaining = 0;
        limited = false;
    }

    /** Reserve some work for remote entities even when the local player's path is unlimited. */
    public int allowance(boolean local, int candidatesLeft) {
        if (candidatesLeft <= 1) return remaining;
        return local ? Math.max(1, remaining / 2) : Math.max(1, remaining / candidatesLeft);
    }

    public void beginCandidate(int allowance, int paths) {
        candidateRemaining = Math.min(remaining, Math.max(0, allowance));
        pathsRemaining = Math.max(1, paths);
        pathRemaining = 0;
        limited = false;
    }

    /** Split a multishot candidate's allowance so all three arrows can have a visible prediction. */
    public void beginPath() {
        pathRemaining = pathsRemaining <= 1 ? candidateRemaining : candidateRemaining / pathsRemaining;
    }

    public void endPath() {
        pathsRemaining = Math.max(0, pathsRemaining - 1);
    }

    public boolean tryStep() {
        if (remaining <= 0 || candidateRemaining <= 0 || pathRemaining <= 0) {
            limited = true;
            return false;
        }
        remaining--;
        candidateRemaining--;
        pathRemaining--;
        return true;
    }

    public int remaining() { return remaining; }
    public boolean limited() { return limited; }

    /** Every remote candidate gets first access in turn, independently of its entity-list order. */
    public int remoteStart(int size) {
        return size <= 0 ? 0 : (int) Math.floorMod(rotation++, (long) size);
    }

    public void clear() {
        beginFrame(0);
        rotation = 0;
    }
}
