package dev.tntcoating.core;

/** Limits a configured item's pause until release or an enabled use restarts it. */
public final class HeldItemPause {
    private boolean holding, expired;
    private long startedNanos;

    /** A zero timeout pauses indefinitely; positive timeouts use a monotonic clock. */
    public boolean paused(boolean held, long nowNanos, long timeoutNanos) {
        if (!held) {
            reset();
            return false;
        }
        if (!holding) {
            holding = true;
            startedNanos = nowNanos;
        }
        if (expired) return false;
        // Subtracting times also handles nanoTime wrapping across Long.MAX_VALUE.
        if (timeoutNanos > 0 && nowNanos - startedNanos >= timeoutNanos) {
            expired = true;
            return false;
        }
        return true;
    }

    /** Restarts the pause for an actual use of a configured item, including after expiry. */
    public void itemUsed(boolean restartEnabled, boolean pauseItem, long nowNanos) {
        if (!restartEnabled || !pauseItem) return;
        holding = true;
        expired = false;
        startedNanos = nowNanos;
    }

    public void reset() {
        holding = expired = false;
        startedNanos = 0;
    }
}
