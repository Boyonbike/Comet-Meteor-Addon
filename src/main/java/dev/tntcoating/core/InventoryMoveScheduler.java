package dev.tntcoating.core;

import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/** One container transfer at a time, with monotonic deadlines and no worker-thread inventory access. */
public final class InventoryMoveScheduler<T> {
    public static final int MAX_ACTIONS_PER_TICK = 8;
    /**
     * An action handed to a deferred callback (a rotation) that has not completed by then is abandoned, so a callback
     * that never runs cannot stall the job. A late completion of an abandoned action is ignored.
     */
    public static final long IN_FLIGHT_TIMEOUT_NANOS = 1_000_000_000L;

    /** Identity is intentional: a reused container id cannot make an old request valid again. */
    public record Context(Object player, Object world, Object screen, Object menu) {
        public boolean same(Context other) {
            return other != null && player == other.player && world == other.world
                && screen == other.screen && menu == other.menu;
        }
    }

    public record Action<T>(long generation, int slot, T snapshot, boolean steal) {}

    private Context context;
    private boolean steal;
    private int next, end;
    private boolean first;
    private long generation;
    private long readyAt, deadline;
    private T pending;
    private Action<T> inFlight;
    private long inFlightSince;
    private long budgetTick = Long.MIN_VALUE;
    private int actionsThisTick;

    /** Repeated content packets coalesce rather than restarting the delay or creating duplicate jobs. */
    public boolean start(Context requested, int start, int end, boolean steal, long now) {
        if (context != null && context.same(requested) && this.steal == steal) return false;
        cancel();
        if (requested == null || start < 0 || end <= start) return false;
        context = requested;
        this.steal = steal;
        next = start;
        this.end = end;
        first = true;
        readyAt = now;
        return true;
    }

    /** Null captures skip empty or filtered slots; the caller must revalidate a snapshot before clicking. */
    public Action<T> poll(Context current, long now, long clientTick, int initialDelayMillis,
                          IntSupplier normalDelayMillis, IntFunction<T> capture) {
        if (!isValid(current)) {
            cancel();
            return null;
        }
        if (clientTick != budgetTick) {
            budgetTick = clientTick;
            actionsThisTick = 0;
        }
        if (inFlight != null && now - inFlightSince >= IN_FLIGHT_TIMEOUT_NANOS) {
            inFlight = null;
            readyAt = now;
        }
        if (inFlight != null || actionsThisTick >= MAX_ACTIONS_PER_TICK) return null;

        while (next < end) {
            if (pending == null) {
                pending = capture.apply(next);
                if (pending == null) {
                    next++;
                    continue;
                }
                int delay = first && initialDelayMillis != 0 ? initialDelayMillis : normalDelayMillis.getAsInt();
                first = false;
                deadline = readyAt + Math.max(0L, delay) * 1_000_000L;
            }
            if (now - deadline < 0) return null;
            inFlight = new Action<>(generation, next++, pending, steal);
            inFlightSince = now;
            pending = null;
            actionsThisTick++;
            return inFlight;
        }
        cancel();
        return null;
    }

    /** Also use inside delayed rotation callbacks: cancellation invalidates every outstanding action. */
    public boolean isCurrent(Action<T> action, Context current) {
        return action != null && action == inFlight && action.generation == generation && isValid(current);
    }

    public void complete(Action<T> action, long now) {
        if (action != inFlight || action.generation != generation) return;
        inFlight = null;
        readyAt = now;
        if (next >= end) cancel();
    }

    public boolean isValid(Context current) {
        return context != null && context.same(current);
    }

    public boolean hasWork() {
        return context != null;
    }

    public void cancel() {
        generation++;
        context = null;
        pending = null;
        inFlight = null;
    }
}
