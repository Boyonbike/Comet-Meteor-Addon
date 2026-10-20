package dev.tntcoating.core;

/** Lets the server establish a new elytra glide before Sprint can apply its boost. */
public final class ElytraBoostDelay {
    /** A quarter of a second at the normal client tick rate. */
    public static final int SETTLE_TICKS = 5;
    private static final long NOT_GLIDING = Long.MIN_VALUE;

    private boolean observed, wearingElytra, delayed;
    private long glideStarted = NOT_GLIDING;

    /** Arms immediately after an equipment swap, including one made between movement ticks. */
    public void swapped() {
        delayed = true;
        glideStarted = NOT_GLIDING;
    }

    /**
     * Observes actual equipment and flight, also covering swaps made manually or by another module.
     * May run more than once in a tick without shortening the delay. Time spent waiting for takeoff
     * does not count: the delay starts with the first gliding tick after the swap.
     */
    public void observe(long tick, boolean elytraEquipped, boolean gliding) {
        if (observed && elytraEquipped && !wearingElytra) swapped();
        observed = true;
        wearingElytra = elytraEquipped;
        if (!elytraEquipped) {
            delayed = false;
            glideStarted = NOT_GLIDING;
            return;
        }
        if (!delayed) return;
        if (!gliding) {
            glideStarted = NOT_GLIDING;
            return;
        }
        if (glideStarted == NOT_GLIDING) glideStarted = tick;
        if (tick - glideStarted >= SETTLE_TICKS) {
            delayed = false;
            glideStarted = NOT_GLIDING;
        }
    }

    /** Whether either held or toggled Sprint may boost this movement tick. */
    public boolean ready() { return !delayed; }

    /** Existing flight is allowed immediately when this module or world first becomes active. */
    public void reset() {
        observed = wearingElytra = delayed = false;
        glideStarted = NOT_GLIDING;
    }
}
