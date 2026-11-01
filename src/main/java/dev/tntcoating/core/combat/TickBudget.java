/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Phase 4.1 shared per-tick work budget. One deadline plus one work counter covers every combat phase that
 * runs inside a client tick: receipt queue drain, applied-revision stamping, snapshot capture, event-triggered
 * passes, every planner/search pass and the before-send validation path.
 *
 * <p>The budget is deliberately passive: {@link #spend(Phase)} records work and reports whether anything is
 * left, but nothing is aborted when it runs out. Reconciliation and observation application must always run;
 * only optional planning/search work is deferred to the next tick, with its cursor carried forward. This is
 * the plan 9.3 rule: a busy tick may do less optional work, never less applied work.</p>
 *
 * <p>Both limits are injectable so tests can drive the budget with a fake nano clock or a plain work counter:
 * {@link #afterNanos(long, LongSupplier)} for time, {@link #workLimited(long)} for deterministic work
 * accounting, {@link #of(LongSupplier, long, long)} for both. Production uses
 * {@link #afterMillis(double, LongSupplier)} with the plan default of one millisecond per tick.</p>
 */
public final class TickBudget {
    /** Plan 9.3 production default: all optional combat work in one tick gets one millisecond. */
    public static final double DEFAULT_MILLIS = 1.0;

    /** The phases plan 9.3 names explicitly. Counters are kept per phase so tests can prove consumption. */
    public enum Phase {
        QUEUE_DRAIN,
        REVISION_STAMPING,
        SNAPSHOT_CAPTURE,
        EVENT_PASS,
        PLANNING,
        VALIDATION,
        RECONCILIATION
    }

    private static final long UNLIMITED = Long.MAX_VALUE;

    private final LongSupplier clock;
    private final long startNanos;
    private final long durationNanos;
    private final long workLimit;
    private final long[] phaseWork = new long[Phase.values().length];

    private long work;
    private long deferrals;

    private TickBudget(LongSupplier clock, long durationNanos, long workLimit) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (durationNanos <= 0) throw new IllegalArgumentException("duration must be positive");
        if (workLimit <= 0) throw new IllegalArgumentException("work limit must be positive");
        this.startNanos = clock.getAsLong();
        this.durationNanos = durationNanos;
        this.workLimit = workLimit;
    }

    /** A budget of {@code millis} milliseconds measured on {@code clock}; work is unlimited. */
    public static TickBudget afterMillis(double millis, LongSupplier clock) {
        if (!(millis > 0.0) || !Double.isFinite(millis)) {
            throw new IllegalArgumentException("millis must be a positive finite number");
        }
        return afterNanos((long) Math.ceil(millis * 1_000_000.0), clock);
    }

    /** A budget of {@code nanos} nanoseconds measured on {@code clock}; work is unlimited. */
    public static TickBudget afterNanos(long nanos, LongSupplier clock) {
        return new TickBudget(clock, nanos, UNLIMITED);
    }

    /**
     * A budget limited by both a clock and a maximum number of work units. Tests use this to prove the budget
     * is consumed and to force deferral without depending on wall-clock time.
     */
    public static TickBudget of(LongSupplier clock, long durationNanos, long workLimit) {
        return new TickBudget(clock, durationNanos, workLimit);
    }

    /** A deterministic work-only budget: unlimited time, exhausted after {@code workLimit} units. */
    public static TickBudget workLimited(long workLimit) {
        return new TickBudget(() -> 0L, UNLIMITED, workLimit);
    }

    /** Never exhausted. Used by the pre-Phase-4.1 entry points so their exact behaviour is unchanged. */
    public static TickBudget unlimited() {
        return new TickBudget(() -> 0L, UNLIMITED, UNLIMITED);
    }

    /** Records one unit of work in {@code phase}; returns {@code true} while budget remains. */
    public boolean spend(Phase phase) {
        return spend(phase, 1L);
    }

    /**
     * Records {@code units} of work in {@code phase}; returns {@code true} while budget remains. The work is
     * always recorded first: a caller that overshoots still observes the overshoot in the counters.
     */
    public boolean spend(Phase phase, long units) {
        Objects.requireNonNull(phase, "phase");
        if (units < 0) throw new IllegalArgumentException("units must not be negative");
        work += units;
        phaseWork[phase.ordinal()] += units;
        return !exhausted();
    }

    /** Records that optional work was deferred because the budget ran out. */
    public void defer() {
        deferrals++;
    }

    /** True once either the deadline passed or the work limit was reached. */
    public boolean exhausted() {
        if (work >= workLimit) return true;
        if (durationNanos == UNLIMITED) return false;
        return clock.getAsLong() - startNanos >= durationNanos;
    }

    public long work() {
        return work;
    }

    public long phaseWork(Phase phase) {
        Objects.requireNonNull(phase, "phase");
        return phaseWork[phase.ordinal()];
    }

    public long deferrals() {
        return deferrals;
    }

    public long startNanos() {
        return startNanos;
    }

    /** Nanoseconds left on the deadline, or {@link Long#MAX_VALUE} when the budget is work-only/unlimited. */
    public long remainingNanos() {
        if (durationNanos == UNLIMITED) return UNLIMITED;
        long remaining = durationNanos - (clock.getAsLong() - startNanos);
        return Math.max(0L, remaining);
    }
}
