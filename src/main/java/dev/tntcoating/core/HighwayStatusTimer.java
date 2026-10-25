/*
 * Comet trip status cadence.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.tntcoating.core;

import java.time.Duration;

/** Monotonic-clock cadence for a running trip's status notifications. */
public final class HighwayStatusTimer {
    private static final long INTERVAL_NANOS = Duration.ofMinutes(30).toNanos();
    private boolean active;
    private long lastStatusNanos;

    /** Starts a fresh interval, so the first update waits for the full chosen interval. */
    public void reset(long nowNanos) {
        active = true;
        lastStatusNanos = nowNanos;
    }

    /** Consumes one elapsed interval; delayed polling never produces a burst of old updates. */
    public boolean due(long nowNanos) {
        return due(nowNanos, INTERVAL_NANOS);
    }

    /** Uses a positive configurable interval; reset when changing it to begin a fresh period. */
    public boolean due(long nowNanos, long intervalNanos) {
        if (intervalNanos <= 0) throw new IllegalArgumentException("Status interval must be positive.");
        if (!active || nowNanos - lastStatusNanos < intervalNanos) return false;
        lastStatusNanos = nowNanos;
        return true;
    }

    /** Stops status notifications until another trip starts. */
    public void clear() {
        active = false;
    }
}
