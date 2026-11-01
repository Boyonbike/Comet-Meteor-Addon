package dev.tntcoating.core;

/** Bounded client-side observation of inventory changes; stability is not a server acknowledgement. */
public final class ElytraSwapRecovery {
    public enum Action { CONTINUE, WAIT, REPLACE, CONFIRMED, LOGOUT }
    private int elapsed, blockedTicks, sinceRequest, stableTicks, attempts, healthyTicks;
    private boolean pending;

    public void requested() {
        pending = true;
        sinceRequest = stableTicks = 0;
        attempts++;
    }

    public boolean pending() { return pending; }

    public Action tick(int remaining, int threshold, boolean swap, boolean inventorySafe,
                       boolean needsWings, boolean grounded) {
        if (!needsWings) {
            reset();
            return Action.CONTINUE;
        }
        boolean healthy = remaining > Math.max(2, threshold);
        if (!pending && healthy) {
            // Keep the recent retry budget through late server corrections after provisional stability.
            if (++healthyTicks >= 100) reset();
            return Action.CONTINUE;
        }
        healthyTicks = 0;
        if (!swap || ++elapsed > 60) return Action.LOGOUT;
        // A first immediate replacement is allowed, but never wait in the air on failed wings.
        if (!grounded && remaining <= 2 && (pending || !inventorySafe)) return Action.LOGOUT;
        if (pending) sinceRequest++;
        if (!inventorySafe) {
            stableTicks = 0;
            return ++blockedTicks > 20 ? Action.LOGOUT : Action.WAIT;
        }
        blockedTicks = 0;
        if (pending && healthy) {
            if (++stableTicks < 10) return Action.WAIT;
            pending = false;
            sinceRequest = stableTicks = 0;
            return Action.CONFIRMED;
        }
        stableTicks = 0;
        if (pending && sinceRequest < 10) return Action.WAIT;
        return attempts >= 3 ? Action.LOGOUT : Action.REPLACE;
    }

    public void reset() {
        elapsed = blockedTicks = sinceRequest = stableTicks = attempts = healthyTicks = 0;
        pending = false;
    }
}
