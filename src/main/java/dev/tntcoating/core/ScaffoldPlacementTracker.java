package dev.tntcoating.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Client-thread retry state. An accepted local prediction is deliberately not a server confirmation. */
public final class ScaffoldPlacementTracker {
    private static final int MAX_TRACKED = 256;
    private static final int RETAIN_TICKS = 160;
    private final Map<Long, Attempt> pending = new LinkedHashMap<>();

    public boolean canAttempt(long position, long tick, boolean underfoot) {
        Attempt attempt = pending.get(position);
        if (attempt == null || tick - attempt.tick >= RETAIN_TICKS) return true;
        // A new bridge cell never inherits a delay. Rejected underfoot cells receive quick retries.
        int delay = underfoot ? Math.min(2, attempt.count) : Math.min(8, 1 << Math.min(3, attempt.count));
        return tick - attempt.tick >= delay;
    }

    public void attempted(long position, long tick) {
        Attempt previous = pending.get(position);
        int count = previous == null || tick - previous.tick >= RETAIN_TICKS ? 1 : Math.min(4, previous.count + 1);
        pending.put(position, new Attempt(tick, count));
        if (pending.size() > MAX_TRACKED) pending.remove(pending.keySet().iterator().next());
    }

    /** Call only after an authoritative update has been applied to the loaded client world. */
    public void serverUpdate(long position, boolean serverReplaceable, boolean loadedReplaceable) {
        if (!serverReplaceable && !loadedReplaceable) pending.remove(position);
    }

    public void expire(long tick) {
        pending.values().removeIf(attempt -> tick - attempt.tick >= RETAIN_TICKS);
    }

    public void clear() { pending.clear(); }
    public int size() { return pending.size(); }

    private record Attempt(long tick, int count) {}
}
