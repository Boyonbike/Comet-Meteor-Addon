/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 6.1/6.2 hard-filter risk predicate shared by {@code CombatEngine.HybridGate} and the Phase 6.3
 * concurrency path.
 *
 * <p>It mirrors {@link CombatRiskBudget#reserve} exactly as far as the live budget can be queried:
 * the self bound must stay inside {@link Safety#maxSelf()} and the remaining margin, and every friend
 * bound is checked against its own cumulative {@link Safety#maxFriend()} ceiling. A friend whose
 * effective health is KNOWN must also survive its cumulative bound; an unknown or censored friend gets
 * the ceiling check only and never a fabricated health value.</p>
 *
 * <p>This is deliberately a read-only predicate: it never reserves, mutates, or retires a risk.</p>
 */
public final class CombatRiskGate {
    private CombatRiskGate() {
    }

    /**
     * True when one exposure bound fits on top of the budget's current debt. This is the exact predicate
     * the serialised reservation would apply, minus the envelope/connection/generation checks the engine
     * already performs against the live snapshot.
     */
    public static boolean allows(CombatRiskBudget risk, EntityKey self, SafetyState state,
                                 ExposureBound exposure) {
        if (risk == null || risk.suspended() || state == null || exposure == null) return false;
        if (state.self() == null || state.selfHealth() == null || state.safety() == null) return false;
        if (state.selfHealth().cap() != Capability.KNOWN) return false;
        Safety safety = state.safety();
        if (!valid(safety)) return false;
        double newSelf = exposure.selfUpper() == null ? Double.NaN : exposure.selfUpper().beforeAbsorption();
        if (!validBound(newSelf) || newSelf > safety.maxSelf()) return false;
        double selfLower = healthLower(state.selfHealth());
        if (Double.isNaN(selfLower)) return false;
        double selfCumulative = risk.current(state.self()).value() + newSelf;
        if (!(selfLower - selfCumulative > safety.minRemaining())) return false;

        if (exposure.friendUpper() == null) return false;
        for (Map.Entry<EntityKey, MitigatedDamage> entry : exposure.friendUpper().entrySet()) {
            EntityKey friend = entry.getKey();
            if (friend == null || entry.getValue() == null || friend.equals(state.self())) return false;
            double bound = entry.getValue().beforeAbsorption();
            if (!validBound(bound)) return false;
            double cumulative = risk.current(friend).value() + bound;
            if (cumulative > safety.maxFriend()) return false;
            EffectiveHealth health = state.friends() == null ? null : state.friends().get(friend);
            if (health != null && health.cap() == Capability.KNOWN) {
                double lower = healthLower(health);
                if (Double.isNaN(lower)) return false;
                if (!(lower - cumulative > 0.0)) return false;
            }
        }
        return true;
    }

    /** Adds two exposure bounds for the concurrency risk row: the reservations are simultaneous. */
    public static ExposureBound combine(ExposureBound first, ExposureBound second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        double self = upper(first.selfUpper()) + upper(second.selfUpper());
        Map<EntityKey, MitigatedDamage> friends = new LinkedHashMap<>();
        mergeFriends(friends, first.friendUpper());
        mergeFriends(friends, second.friendUpper());
        return new ExposureBound(new MitigatedDamage(self), Map.copyOf(friends), first.envelope());
    }

    private static void mergeFriends(Map<EntityKey, MitigatedDamage> target,
                                     Map<EntityKey, MitigatedDamage> source) {
        if (source == null) return;
        for (Map.Entry<EntityKey, MitigatedDamage> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            target.merge(entry.getKey(), entry.getValue(),
                (left, right) -> new MitigatedDamage(left.beforeAbsorption() + right.beforeAbsorption()));
        }
    }

    private static double upper(MitigatedDamage damage) {
        return damage == null ? Double.NaN : damage.beforeAbsorption();
    }

    /** Same lower-bound rule as {@link CombatRiskBudget}: invalid components are unknown, not clamped. */
    private static double healthLower(EffectiveHealth health) {
        if (health == null || health.cap() != Capability.KNOWN) return Double.NaN;
        if (!validBound(health.health()) || !validBound(health.absorption())) return Double.NaN;
        return health.value();
    }

    private static boolean valid(Safety safety) {
        return validBound(safety.minTarget()) && validBound(safety.maxSelf())
            && validBound(safety.minRemaining()) && validBound(safety.maxFriend());
    }

    private static boolean validBound(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }
}
