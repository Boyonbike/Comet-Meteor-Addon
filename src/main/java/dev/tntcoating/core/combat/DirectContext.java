/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Everything the single dispatch path needs to revalidate a planned action. Built by the planner, carried
 * unchanged to {@code CombatRuntime.plan}; the runtime re-checks the policy before any packet leaves.
 */
public record DirectContext(
    TargetEstimate estimate,
    ExposureBound exposure,
    EffectiveHealth targetHealth,
    boolean targetOverride,
    boolean faceplace,
    int hotbarSlot,
    String requiredItemId,
    double confidence,
    /** Phase 4.3 hurt-adjusted estimate for ranking only; null when hurt-sync is off/unknown. */
    TargetEstimate hurtEstimate,
    /** Phase 4.3 server tick to wait for, or -1 to act now. */
    long deferUntilTick,
    /**
     * Phase 4.6: a defensive opportunity. Dispatch revalidates self/friend ceilings and the remaining
     * margin through {@link CombatDamagePolicy#allowsDefensive}, never the offence minimum-target floor.
     * The normal offence path always passes {@code false}.
     */
    boolean defensive
) {
    public DirectContext {
        if (estimate == null) throw new IllegalArgumentException("estimate");
        if (exposure == null) throw new IllegalArgumentException("exposure");
        if (targetHealth == null) throw new IllegalArgumentException("targetHealth");
        if (hotbarSlot < 0 || hotbarSlot > 8) throw new IllegalArgumentException("hotbarSlot");
        if (requiredItemId == null) throw new IllegalArgumentException("requiredItemId");
        if (!(confidence >= 0.0 && confidence <= 1.0)) {
            throw new IllegalArgumentException("confidence");
        }
        if (deferUntilTick < -1L) throw new IllegalArgumentException("deferUntilTick");
    }

    /** Phase 4.2-shaped context: no hurt-sync adjustment and no deferral; offence path. */
    public DirectContext(TargetEstimate estimate, ExposureBound exposure, EffectiveHealth targetHealth,
                         boolean targetOverride, boolean faceplace, int hotbarSlot,
                         String requiredItemId, double confidence) {
        this(estimate, exposure, targetHealth, targetOverride, faceplace, hotbarSlot, requiredItemId,
            confidence, null, -1L, false);
    }

    /** Phase 4.3-shaped context: no defensive marker, so every pre-4.6 call site keeps offence policy. */
    public DirectContext(TargetEstimate estimate, ExposureBound exposure, EffectiveHealth targetHealth,
                         boolean targetOverride, boolean faceplace, int hotbarSlot,
                         String requiredItemId, double confidence, TargetEstimate hurtEstimate,
                         long deferUntilTick) {
        this(estimate, exposure, targetHealth, targetOverride, faceplace, hotbarSlot, requiredItemId,
            confidence, hurtEstimate, deferUntilTick, false);
    }

    /**
     * Phase 3-shaped context: full confidence. The motion tactic replaces this with the predicted
     * confidence so low motion confidence can only reduce opportunity ranking, never a safety bound.
     */
    public DirectContext(TargetEstimate estimate, ExposureBound exposure, EffectiveHealth targetHealth,
                         boolean targetOverride, boolean faceplace, int hotbarSlot,
                         String requiredItemId) {
        this(estimate, exposure, targetHealth, targetOverride, faceplace, hotbarSlot, requiredItemId,
            1.0, null, -1L, false);
    }
}
