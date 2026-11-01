/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Map;
import java.util.Objects;

/**
 * One-opportunity damage acceptance. Conservative worst case only: the expected target value must clear the
 * minimum, the self and every friend upper bound must stay inside their limits, and the effective-health
 * lower bound minus self damage must stay strictly above the margin (a remainder of exactly the margin is
 * rejected).
 *
 * <p>Lethal (faceplace) override relaxes only the minimum target damage, and only when the target's
 * effective-health upper bound is known: {@code guaranteedLower} must reach it. {@code expected} never
 * qualifies. The target bound is supplied at construction because the Phase 1 {@link TargetEstimate} record
 * carries damage only; the caller has the coherent target-health snapshot when the opportunity exists.</p>
 *
 * <p>Cumulative limits are not this class's job; that is {@code CombatRiskBudget} in Phase 2.</p>
 */
public final class CombatDamagePolicy {
    /** Plan defaults: minimum acceptable expected target damage. */
    public static final double MIN_TARGET_DAMAGE = 4.0;
    /** Plan defaults: maximum acceptable self upper bound. */
    public static final double MAX_SELF_DAMAGE = 6.0;
    /** Plan defaults: required effective-health remainder after self damage (strictly greater). */
    public static final double MIN_REMAINING_HEALTH = 2.0;
    /** Plan defaults: maximum acceptable friend upper bound. */
    public static final double MAX_FRIEND_DAMAGE = 2.0;
    /**
     * Relaxed minimum expected target damage for a faceplace pulse. Faceplace is armour pressure, never a
     * damage-gain claim: it only lowers the target-damage floor, and only when the caller has already
     * verified the faceplace condition (low health or a nearly broken armour piece).
     */
    public static final double FACE_PLACE_MIN_TARGET = 1.5;

    public static Safety conservativeSafety() {
        return new Safety(MIN_TARGET_DAMAGE, MAX_SELF_DAMAGE, MIN_REMAINING_HEALTH, MAX_FRIEND_DAMAGE);
    }

    private final EffectiveHealth selfHealth;
    private final EffectiveHealth targetHealth;

    /**
     * @param selfHealth   the coherent self effective-health snapshot
     * @param targetHealth the coherent target effective-health snapshot; only a {@link Capability#KNOWN}
     *                     reading is usable as an upper bound
     */
    public CombatDamagePolicy(EffectiveHealth selfHealth, EffectiveHealth targetHealth) {
        this.selfHealth = Objects.requireNonNull(selfHealth, "selfHealth");
        this.targetHealth = Objects.requireNonNull(targetHealth, "targetHealth");
    }

    public boolean allows(TargetEstimate target, ExposureBound exposure, Safety safety) {
        return allows(target, exposure, safety, false);
    }

    public boolean allows(TargetEstimate target, ExposureBound exposure, Safety safety,
                          boolean targetOverride) {
        return allows(target, exposure, safety, targetOverride, false);
    }

    /**
     * @param faceplace lowers only the minimum expected target damage to
     *                  {@code min(safety.minTarget(), FACE_PLACE_MIN_TARGET)}; self, friend and remaining
     *                  limits are unchanged and the lethal override still needs a known target upper bound
     */
    public boolean allows(TargetEstimate target, ExposureBound exposure, Safety safety,
                          boolean targetOverride, boolean faceplace) {
        if (target == null || exposure == null || safety == null) return false;
        if (!validEstimate(target) || !validExposure(exposure) || !validSafety(safety)) return false;

        if (targetOverride) {
            if (!lethalGuaranteed(target)) return false;
        } else {
            double minimum = faceplace ? Math.min(safety.minTarget(), FACE_PLACE_MIN_TARGET)
                : safety.minTarget();
            if (!(target.expected() >= minimum)) return false;
        }

        return withinCeilings(exposure, safety);
    }

    /**
     * Phase 4.6 defensive acceptance: breaking a crystal that is about to detonate on us has no target
     * damage to claim, so the offence minimum-target floor does not apply. The self ceiling, every friend
     * ceiling, the remaining-margin rule and the validity rules are the same as {@link #allows}. The normal
     * offence path never uses this method.
     */
    public boolean allowsDefensive(ExposureBound exposure, Safety safety) {
        if (exposure == null || safety == null) return false;
        if (!validExposure(exposure) || !validSafety(safety)) return false;
        return withinCeilings(exposure, safety);
    }

    private boolean withinCeilings(ExposureBound exposure, Safety safety) {
        if (exposure.selfUpper().beforeAbsorption() > safety.maxSelf()) return false;
        for (Map.Entry<EntityKey, MitigatedDamage> friend : exposure.friendUpper().entrySet()) {
            Objects.requireNonNull(friend.getKey(), "friend key");
            if (friend.getValue() == null) return false;
            if (friend.getValue().beforeAbsorption() > safety.maxFriend()) return false;
        }
        double remaining = selfHealthLowerBound() - exposure.selfUpper().beforeAbsorption();
        return remaining > safety.minRemaining();
    }

    private boolean lethalGuaranteed(TargetEstimate target) {
        if (targetHealth.cap() != Capability.KNOWN) return false;
        double upperBound = targetHealth.value();
        if (!Double.isFinite(upperBound) || upperBound < 0.0) return false;
        return target.guaranteedLower() >= upperBound;
    }

    private double selfHealthLowerBound() {
        // Unknown or censored health has a known lower bound of zero health plus zero absorption.
        return selfHealth.cap() == Capability.KNOWN ? selfHealth.value() : 0.0;
    }

    private static boolean validEstimate(TargetEstimate target) {
        return finiteNonNegative(target.guaranteedLower())
            && finiteNonNegative(target.expected())
            && finiteNonNegative(target.optimistic())
            && target.guaranteedLower() <= target.optimistic();
    }

    private static boolean validExposure(ExposureBound exposure) {
        if (exposure.selfUpper() == null || exposure.envelope() == null) return false;
        if (!finiteNonNegative(exposure.selfUpper().beforeAbsorption())) return false;
        for (Map.Entry<EntityKey, MitigatedDamage> friend : exposure.friendUpper().entrySet()) {
            if (friend.getKey() == null || friend.getValue() == null) return false;
            if (!finiteNonNegative(friend.getValue().beforeAbsorption())) return false;
        }
        return true;
    }

    private static boolean validSafety(Safety safety) {
        return finiteNonNegative(safety.minTarget())
            && finiteNonNegative(safety.maxSelf())
            && finiteNonNegative(safety.minRemaining())
            && finiteNonNegative(safety.maxFriend());
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }
}
