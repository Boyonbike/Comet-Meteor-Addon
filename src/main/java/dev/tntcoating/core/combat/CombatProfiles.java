/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Production safety profile pins. The budget and the policy stay permissive enough for unit tests and for
 * future harder profiles, but the runtime that actually spends risk must never be constructed with a
 * profile weaker than the conservative values the plan pins. This is the single place that decides which
 * {@link Safety} and which budget features production code is allowed to use.
 */
public final class CombatProfiles {
    /** Conservative production safety: accept at least 4 target damage, at most 6 self, margin > 2. */
    public static final Safety CONSERVATIVE_SAFETY = CombatDamagePolicy.conservativeSafety();

    private static final double MIN_TARGET = CombatDamagePolicy.MIN_TARGET_DAMAGE;
    private static final double MAX_SELF = CombatDamagePolicy.MAX_SELF_DAMAGE;
    private static final double MIN_REMAINING = CombatDamagePolicy.MIN_REMAINING_HEALTH;
    private static final double MAX_FRIEND = CombatDamagePolicy.MAX_FRIEND_DAMAGE;

    private CombatProfiles() {
    }

    /**
     * Returns {@code safety} unchanged when it is at least as strict as the conservative profile, otherwise
     * throws. Rejected: any non-finite or negative value, a target floor below four, a self ceiling above
     * six, a remaining-health margin below two, or a friend ceiling above two. Stricter profiles are
     * accepted: they can only reduce what the budget will allow.
     */
    public static Safety productionSafety(Safety safety) {
        if (safety == null) throw new IllegalArgumentException("safety is required");
        requireFiniteNonNegative(safety.minTarget(), "minTarget");
        requireFiniteNonNegative(safety.maxSelf(), "maxSelf");
        requireFiniteNonNegative(safety.minRemaining(), "minRemaining");
        requireFiniteNonNegative(safety.maxFriend(), "maxFriend");
        if (safety.minTarget() < MIN_TARGET) {
            throw new IllegalArgumentException("minTarget " + safety.minTarget()
                + " is weaker than the conservative floor " + MIN_TARGET);
        }
        if (safety.maxSelf() > MAX_SELF) {
            throw new IllegalArgumentException("maxSelf " + safety.maxSelf()
                + " is weaker than the conservative ceiling " + MAX_SELF);
        }
        if (safety.minRemaining() < MIN_REMAINING) {
            throw new IllegalArgumentException("minRemaining " + safety.minRemaining()
                + " is weaker than the conservative margin " + MIN_REMAINING);
        }
        if (safety.maxFriend() > MAX_FRIEND) {
            throw new IllegalArgumentException("maxFriend " + safety.maxFriend()
                + " is weaker than the conservative ceiling " + MAX_FRIEND);
        }
        return safety;
    }

    /**
     * Production budget: grouped bounds and the statistical quiet window are experiments, so production
     * refuses budgets that enable either of them through this factory.
     */
    public static CombatRiskBudget conservativeBudget(MitigationWidener widener) {
        return new CombatRiskBudget(widener);
    }

    /**
     * Experimental budget factory for tests and labelled Phase 7 profiles. Refuses any budget whose grouped
     * bound or statistical quiet window is enabled: those features have no proof data wired in production
     * and would quietly change retirement semantics.
     */
    public static CombatRiskBudget profileBudget(MitigationWidener widener, GroupedBound groupedBound,
                                                 AnarchyQuietWindow quietWindow) {
        if (groupedBound == null) throw new IllegalArgumentException("groupedBound is required");
        if (quietWindow == null) throw new IllegalArgumentException("quietWindow is required");
        if (groupedBound.enabled()) {
            throw new IllegalArgumentException("GroupedBound must stay disabled in production");
        }
        if (quietWindow.enabled()) {
            throw new IllegalArgumentException("AnarchyQuietWindow must stay disabled in production");
        }
        return new CombatRiskBudget(widener, groupedBound, quietWindow);
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative: " + value);
        }
    }
}
