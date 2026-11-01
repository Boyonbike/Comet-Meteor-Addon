/*
 * Original Comet implementation by Boyonabike.
 * Independent: written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * Phase 4.3 pure hurt-sync arithmetic: turn the target's observed hurt/immunity window into a
 * hurt-adjusted {@link TargetEstimate} and decide whether waiting for the window to expire is worth
 * it.
 *
 * <p>This class never observes anything itself. It consumes a {@link CombatHurtModel} that is fed by
 * the client-side seam; with no observation the window is unknown and the result is deliberately
 * conservative: the expected/optimistic values keep the Phase 3 damage and the guaranteed lower
 * bound is zero.</p>
 *
 * <p>All times are server ticks. {@link Arrival} intervals describe when the explosion may land.</p>
 */
public final class HurtSync {
    /** The plan's decision horizon: never defer more than this many server ticks. */
    public static final int HORIZON_TICKS = 20;

    /** Width of the dispatch arrival interval: a send at tick {@code t} lands in [t+1, t+3]. */
    public static final int ARRIVAL_SPAN_TICKS = 2;

    /** Sentinel meaning "attack now, do not defer". */
    public static final long NO_DEFER = -1L;

    private static final double EPSILON = 1e-9;

    private HurtSync() {
    }

    /** Arrival interval for an action planned at {@code serverTick}. */
    public static Arrival immediateArrival(long serverTick) {
        return new Arrival(serverTick + 1L, serverTick + 1L + ARRIVAL_SPAN_TICKS);
    }

    /** Arrival interval for an action sent at {@code sendTick}. */
    public static Arrival arrivalFrom(long sendTick) {
        return new Arrival(sendTick + 1L, sendTick + 1L + ARRIVAL_SPAN_TICKS);
    }

    /** First server tick at which no observation of the prior hurt can keep the window open. */
    public static long expiryTick(WindowState window) {
        Objects.requireNonNull(window, "window");
        if (!window.known()) {
            return Long.MAX_VALUE;
        }
        return window.lastArrival().latest() + WindowState.WINDOW_TICKS;
    }

    /**
     * Hurt-adjust the three raw damage values (guaranteed lower, expected, optimistic) before the
     * existing mitigation pipeline. The three raw inputs keep the Phase 3 exposure semantics; only
     * the target immunity result is applied here.
     *
     * <p>The guaranteed lower bound is positive only when the arrival is wholly inside the window
     * after the prior hit has definitely applied, or wholly after the window has expired. An unknown
     * window, a straddling arrival, or a prior hit larger than the incoming hit all leave it at
     * zero. It is also never allowed to exceed the unadjusted Phase 3 lower bound.</p>
     */
    public static TargetEstimate estimate(CombatHurtModel model, EntityKey target,
                                          RawDamage guaranteedLowerRaw, RawDamage expectedRaw,
                                          RawDamage optimisticRaw, MitigationState current,
                                          MitigationState weakest, Arrival arrival) {
        Objects.requireNonNull(guaranteedLowerRaw, "guaranteedLowerRaw");
        Objects.requireNonNull(expectedRaw, "expectedRaw");
        Objects.requireNonNull(optimisticRaw, "optimisticRaw");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(weakest, "weakest");
        Objects.requireNonNull(arrival, "arrival");

        TargetEstimate baseline = CombatDamage.targetEstimate(guaranteedLowerRaw, expectedRaw,
            optimisticRaw, current, weakest);
        if (model == null || target == null) {
            return baseline;
        }
        WindowState window = model.windowState(target);
        HurtEstimate lower = model.effectiveRaw(target, guaranteedLowerRaw.value(), arrival);
        HurtEstimate expected = model.effectiveRaw(target, expectedRaw.value(), arrival);
        HurtEstimate optimistic = model.effectiveRaw(target, optimisticRaw.value(), arrival);

        double guaranteed = 0.0;
        if (opensWindowForSure(window, arrival)) {
            double adjusted = CombatDamage.mitigated(new RawDamage(lower.guaranteedLower()), current)
                .beforeAbsorption();
            guaranteed = Math.min(adjusted, baseline.guaranteedLower());
        }
        double expectedValue = CombatDamage.mitigated(new RawDamage(expected.expected()), current)
            .beforeAbsorption();
        double optimisticValue = Math.max(expectedValue,
            CombatDamage.mitigated(new RawDamage(optimistic.expected()), weakest).beforeAbsorption());
        return new TargetEstimate(Math.max(0.0, guaranteed), expectedValue,
            Math.max(expectedValue, optimisticValue));
    }

    /**
     * Decide the tick to attack on. Returns {@link #NO_DEFER} (attack now) unless all of the
     * following hold:
     *
     * <ul>
     *   <li>the target's window is known and still active for the immediate arrival;</li>
     *   <li>the window expires within {@link #HORIZON_TICKS} server ticks;</li>
     *   <li>attacking immediately is not already lethal against the target's effective-health upper
     *       bound;</li>
     *   <li>the expected damage from landing just after expiry is strictly better than attacking
     *       immediately.</li>
     * </ul>
     */
    public static long deferUntil(CombatHurtModel model, EntityKey target,
                                  RawDamage guaranteedLowerRaw, RawDamage expectedRaw,
                                  RawDamage optimisticRaw, MitigationState current,
                                  MitigationState weakest, Arrival immediateArrival,
                                  long serverTick, TargetEstimate immediate,
                                  EffectiveHealth targetHealth) {
        Objects.requireNonNull(immediateArrival, "immediateArrival");
        Objects.requireNonNull(immediate, "immediate");
        if (model == null || target == null) {
            return NO_DEFER;
        }
        WindowState window = model.windowState(target);
        if (!window.known()) {
            return NO_DEFER;
        }
        long expiry = expiryTick(window);
        if (immediateArrival.earliest() >= expiry) {
            return NO_DEFER;
        }
        long deferUntilTick = expiry - 1L;
        long waitTicks = deferUntilTick - serverTick;
        if (waitTicks <= 0L || waitTicks > HORIZON_TICKS) {
            return NO_DEFER;
        }
        if (targetHealth != null && targetHealth.cap() == Capability.KNOWN
            && immediate.guaranteedLower() >= targetHealth.value()) {
            return NO_DEFER;
        }
        Arrival delayedArrival = new Arrival(expiry, expiry + ARRIVAL_SPAN_TICKS);
        TargetEstimate delayed = estimate(model, target, guaranteedLowerRaw, expectedRaw,
            optimisticRaw, current, weakest, delayedArrival);
        if (delayed.expected() <= immediate.expected() + EPSILON) {
            return NO_DEFER;
        }
        return deferUntilTick;
    }

    /**
     * True when {@link CombatHurtModel#effectiveRaw} is in one of its unambiguous branches for the
     * guaranteed increment: either the arrival is wholly after the prior hit (and before the window
     * edge), or wholly after the window has expired. Any other interval straddles the edge.
     */
    static boolean opensWindowForSure(WindowState window, Arrival arrival) {
        if (!window.known()) {
            return false;
        }
        Arrival prior = window.lastArrival();
        boolean expired = arrival.earliest() >= prior.latest() + WindowState.WINDOW_TICKS;
        boolean certainlyInside = arrival.earliest() >= prior.latest()
            && arrival.latest() <= prior.earliest() + WindowState.WINDOW_TICKS - 1L;
        return expired || certainlyInside;
    }
}
