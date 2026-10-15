package dev.tntcoating.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Bookkeeping between sending a placement and the server's authoritative block update: reservations,
 * confirmation, timeouts, later disappearance and retry backoff. Positions and blocks are generic so
 * the rules are testable without the game. Pending, unconfirmed and watched state is read from the
 * network thread; everything else belongs to the client thread.
 */
public final class PlacementTracker<P, B> {
    /** Sent placements are observed this long for a later authoritative disappearance. */
    public static final int WATCH_TICKS = 200;
    /** A reservation whose deferred interaction has not run by then is dropped. */
    public static final int UNSENT_TICKS = 2;

    public interface Listener<P> {
        void accepted(P pos, int latencyTicks);
        /** Failure is false for placements the server ignored during a position correction. */
        void retryScheduled(P pos, boolean failure);
        void retryReady(P pos);
    }

    public static final class Attempt<B> {
        public final int reserved;
        public final B block;
        private volatile boolean sent;
        private volatile boolean lostToSetback;
        private int sentTick;
        private Attempt(int reserved, B block) { this.reserved = reserved; this.block = block; }
        public boolean sent() { return sent; }
    }

    private final Listener<P> listener;
    private final Map<P, Attempt<B>> pending = new ConcurrentHashMap<>();
    private final Set<P> unconfirmed = ConcurrentHashMap.newKeySet();
    private final Map<P, Integer> watched = new ConcurrentHashMap<>();
    private final Map<P, Integer> retryAfter = new HashMap<>();
    private final Map<P, Integer> failures = new HashMap<>();

    public PlacementTracker(Listener<P> listener) { this.listener = listener; }

    public Attempt<B> reserve(P pos, int tick, B block) {
        Attempt<B> attempt = new Attempt<>(tick, block);
        pending.put(pos, attempt);
        return attempt;
    }

    public Attempt<B> attempt(P pos) { return pending.get(pos); }
    public boolean isPending(P pos) { return pending.containsKey(pos); }
    public boolean current(P pos, Attempt<B> attempt) { return pending.get(pos) == attempt; }
    public int pendingCount() { return pending.size(); }
    public Set<P> pendingPositions() { return pending.keySet(); }
    public boolean unconfirmed(P pos) { return unconfirmed.contains(pos); }
    public boolean retryBlocked(P pos, int tick) { return retryAfter.getOrDefault(pos, 0) > tick; }

    /** Release a reservation that will not be sent. */
    public void cancel(P pos, Attempt<B> attempt) { pending.remove(pos, attempt); }

    public void markSent(P pos, Attempt<B> attempt, int tick) {
        attempt.sentTick = tick;
        watched.put(pos, tick + WATCH_TICKS);
        unconfirmed.add(pos);
        attempt.sent = true;
    }

    /** Whether a block update concerns a placement. Ignores packets arriving before the interaction was sent. */
    public boolean concerns(P pos, Attempt<B> attempt) {
        return (attempt != null && attempt.sent) || unconfirmed.contains(pos) || watched.containsKey(pos);
    }

    public void discardUnsent() { pending.values().removeIf(attempt -> !attempt.sent); }

    /** The server ignores placements arriving while its position correction is unacknowledged. */
    public void setback() {
        discardUnsent();
        for (Attempt<B> attempt : pending.values()) attempt.lostToSetback = true;
    }

    /** Apply an authoritative block update; attempt is the one pending when the packet arrived. */
    public void update(P pos, boolean matchesBlock, boolean replaceable, Attempt<B> attempt, int tick, int retryDelay) {
        unconfirmed.remove(pos);
        boolean resolved = attempt != null && attempt.sent && pending.remove(pos, attempt);
        if (matchesBlock) {
            failures.remove(pos); retryAfter.remove(pos);
            if (resolved) listener.accepted(pos, tick - attempt.sentTick);
        } else if (replaceable && (resolved || watched.containsKey(pos))) {
            // Includes a later authoritative rollback after an earlier confirmation.
            watched.remove(pos);
            scheduleRetry(pos, tick, retryDelay, !(resolved && attempt.lostToSetback));
        } else if (!replaceable) {
            watched.remove(pos); failures.remove(pos); retryAfter.remove(pos);
        }
    }

    /** Time out sent attempts, drop stale reservations, release due retries and forget positions out of range. */
    public void expire(int tick, int timeout, int retryDelay, Predicate<P> inRange) {
        pending.entrySet().removeIf(e -> {
            Attempt<B> attempt = e.getValue();
            if (!attempt.sent) return tick - attempt.reserved > UNSENT_TICKS;
            if (tick - attempt.sentTick < timeout) return false;
            scheduleRetry(e.getKey(), tick, retryDelay, !attempt.lostToSetback);
            return true;
        });
        retryAfter.entrySet().removeIf(e -> {
            if (tick < e.getValue()) return false;
            listener.retryReady(e.getKey());
            return true;
        });
        watched.entrySet().removeIf(e -> tick > e.getValue() || !inRange.test(e.getKey()));
        failures.keySet().removeIf(pos -> !inRange.test(pos));
    }

    public void pruneUnconfirmed(Predicate<P> inRange) {
        unconfirmed.removeIf(pos -> !inRange.test(pos) && !pending.containsKey(pos));
    }

    public void clear() {
        pending.clear(); unconfirmed.clear(); watched.clear(); retryAfter.clear(); failures.clear();
    }

    private void scheduleRetry(P pos, int tick, int base, boolean failure) {
        int count = failure ? failures.merge(pos, 1, Integer::sum) : failures.getOrDefault(pos, 0);
        retryAfter.put(pos, tick + RetrySchedule.delay(base, count));
        listener.retryScheduled(pos, failure);
    }
}
