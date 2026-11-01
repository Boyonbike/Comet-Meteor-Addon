/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * What the hurt model knows about one entity's vanilla invulnerability window, in server ticks, as an arrival
 * interval rather than a point.
 *
 * <p>Vanilla sets {@code invulnerableTime = 20} and only takes the weak-follow-up branch while it is greater
 * than 10; at one decrement per server tick that window covers exactly the ten ticks
 * {@code [lastHurtTick, lastHurtTick + 10)}. When the last hurt's tick is only known to lie in
 * {@code [earliest, latest]}, the window may start anywhere in that interval.</p>
 */
public record WindowState(boolean known, Arrival lastArrival, double lastHurt) {
    /** Vanilla weak-follow-up window in ticks, W = 10. */
    public static final int WINDOW_TICKS = 10;

    public WindowState {
        if (known) {
            if (lastArrival == null) throw new IllegalArgumentException("lastArrival is required");
        }
        if (!Double.isFinite(lastHurt) || lastHurt < 0.0) {
            throw new IllegalArgumentException("lastHurt must be finite and non-negative: " + lastHurt);
        }
    }

    public static WindowState unknown() {
        return new WindowState(false, Arrival.at(0L), 0.0);
    }

    public static WindowState of(Arrival lastArrival, double lastHurt) {
        return new WindowState(true, lastArrival, lastHurt);
    }

    /** True when the window may still be open at {@code tick} under some possible prior tick. */
    public boolean possiblyInWindowAt(long tick) {
        return known
            && tick >= lastArrival.earliest()
            && tick < lastArrival.latest() + WINDOW_TICKS;
    }

    /** True when the window must still be open at {@code tick} under every possible prior tick. */
    public boolean certainlyInWindowAt(long tick) {
        return known
            && tick >= lastArrival.latest()
            && tick < lastArrival.earliest() + WINDOW_TICKS;
    }

    /** True when the window has expired under every possible prior tick. */
    public boolean mustHaveExpiredAt(long tick) {
        return !known || tick >= lastArrival.latest() + WINDOW_TICKS;
    }
}
