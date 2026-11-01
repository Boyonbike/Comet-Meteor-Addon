/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The victim-side raw-increment immunity model, in server ticks.
 *
 * <p>Vanilla records the raw amount of the last hurt and, while {@code invulnerableTime > 10}, refuses a
 * follow-up raw hit of equal or lower value and applies only {@code incoming - lastHurt} for a stronger one.
 * This class applies that rule to raw values and arrival intervals: it never subtracts absorption and never
 * batches hits into a single packet.</p>
 *
 * <p>Observations must be replayed in causal order. A recorded hit is one that landed; without a record the
 * prior hurt is unknown, so the guaranteed lower bound is zero. Grouping several hits is Phase 5; this model
 * never lowers a self or friend upper bound.</p>
 */
public final class CombatHurtModel {
    private record Observation(Arrival arrival, double lastHurt) {
    }

    private final Map<EntityKey, Observation> observations = new HashMap<>();

    /**
     * Records a landed raw hit so later estimates know the victim's last hurt. Out-of-order or overlapping
     * observations are merged to the wider arrival interval and the larger last hurt, which is the
     * conservative direction for a guaranteed lower bound.
     */
    public void observeHit(EntityKey entity, double rawDamage, Arrival arrival) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(arrival, "arrival");
        if (!Double.isFinite(rawDamage) || rawDamage < 0.0) {
            throw new IllegalArgumentException("rawDamage must be finite and non-negative: " + rawDamage);
        }
        Observation prior = observations.get(entity);
        if (prior == null) {
            observations.put(entity, new Observation(arrival, rawDamage));
            return;
        }
        if (arrival.earliest() > prior.arrival().latest()) {
            observations.put(entity, new Observation(arrival, rawDamage));
            return;
        }
        Arrival merged = prior.arrival().union(arrival);
        observations.put(entity, new Observation(merged, Math.max(prior.lastHurt(), rawDamage)));
    }

    public HurtEstimate effectiveRaw(EntityKey entity, double incoming, Arrival arrival) {
        return effectiveRaw(entity, incoming, arrival, false);
    }

    /**
     * The raw increase a follow-up hit with {@code incoming} produces before mitigation.
     *
     * @param bypassesCooldown true only when the damage type is known to bypass the invulnerability cooldown
     */
    public HurtEstimate effectiveRaw(EntityKey entity, double incoming, Arrival arrival,
                                     boolean bypassesCooldown) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(arrival, "arrival");
        if (!Double.isFinite(incoming) || incoming < 0.0) {
            throw new IllegalArgumentException("incoming must be finite and non-negative: " + incoming);
        }
        if (bypassesCooldown) return new HurtEstimate(incoming, incoming);

        Observation prior = observations.get(entity);
        if (prior == null) return HurtEstimate.unknownPrior(incoming);

        double insideIncrement = incoming > prior.lastHurt() ? incoming - prior.lastHurt() : 0.0;
        // The window must be open only if the hit is at or after the latest possible prior hit and before
        // the earliest possible window end.
        long lastInsideTick = prior.arrival().earliest() + WindowState.WINDOW_TICKS - 1L;
        long firstExpiredTick = prior.arrival().latest() + WindowState.WINDOW_TICKS;

        if (arrival.earliest() >= firstExpiredTick) {
            return new HurtEstimate(incoming, incoming);
        }
        if (arrival.earliest() >= prior.arrival().latest()
            && arrival.latest() <= lastInsideTick) {
            return new HurtEstimate(insideIncrement, insideIncrement);
        }
        if (arrival.latest() < prior.arrival().earliest()) {
            // The follow-up arrived before the recorded hit; the record cannot constrain it, so do not
            // invent certainty.
            return HurtEstimate.unknownPrior(incoming);
        }

        // The interval straddles the window edge: the result is a range, never a point.
        double priorTick = (prior.arrival().earliest() + prior.arrival().latest()) / 2.0;
        double insideFraction = overlapFraction(arrival, priorTick,
            priorTick + WindowState.WINDOW_TICKS);
        double expected = insideIncrement + (incoming - insideIncrement) * (1.0 - insideFraction);
        return new HurtEstimate(Math.min(insideIncrement, expected), expected);
    }

    public WindowState windowState(EntityKey entity) {
        Objects.requireNonNull(entity, "entity");
        Observation observation = observations.get(entity);
        if (observation == null) return WindowState.unknown();
        return WindowState.of(observation.arrival(), observation.lastHurt());
    }

    private static double overlapFraction(Arrival arrival, double windowStart, double windowEndExclusive) {
        double from = Math.max(arrival.earliest(), windowStart);
        double to = Math.min(arrival.latest() + 1.0, windowEndExclusive);
        double overlap = Math.max(0.0, to - from);
        double length = arrival.latest() - arrival.earliest() + 1.0;
        return length <= 0.0 ? 0.0 : overlap / length;
    }
}
