/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Collection;
import java.util.Map;

/**
 * Damage pricing seam. The Minecraft-facing implementation wraps the audited {@code CombatDamageAdapter};
 * tests supply fixed numbers. No method mutates the world.
 */
public interface DirectDamage {
    /** Worst mitigation envelope the estimates were priced against. */
    MitigationEnvelope envelope();

    /** Guaranteed-lower, expected and optimistic damage for one target from a crystal at the centre. */
    TargetEstimate targetEstimate(DirectTarget target, Vec crystalCentre, Collection<Cell> overlays);

    /**
     * Phase 4.3 hurt seam: the Phase 3 estimate with the target's immunity result applied to the raw
     * damage before mitigation. The default ignores the arrival and returns the Phase 3 estimate, so
     * worlds without a hurt model keep the exact Phase 3 numbers.
     */
    default TargetEstimate targetEstimate(DirectTarget target, Vec crystalCentre,
                                          Collection<Cell> overlays, Arrival arrival) {
        return targetEstimate(target, crystalCentre, overlays);
    }

    /**
     * Phase 4.3 timing seam: a server tick to wait for (or {@link HurtSync#NO_DEFER}). The default never
     * defers, so worlds without hurt pricing keep the exact Phase 3 timing.
     */
    default long hurtSyncDeferUntil(DirectTarget target, Vec crystalCentre, Collection<Cell> overlays,
                                    Arrival immediateArrival, int serverTick,
                                    TargetEstimate immediate) {
        return HurtSync.NO_DEFER;
    }

    /** Upper bound on damage to self from a crystal at the centre. */
    MitigatedDamage selfUpper(Vec crystalCentre, Collection<Cell> overlays);

    /** Upper bound on damage to each tracked friend from a crystal at the centre. */
    Map<EntityKey, MitigatedDamage> friendUppers(Vec crystalCentre, Collection<Cell> overlays);

    /**
     * Phase 4.2 motion seam: upper bound on damage to self with the self feet at {@code selfFeet}. The
     * default ignores the position and returns the Phase 3 bound, so worlds without motion pricing keep
     * the exact Phase 3 number.
     */
    default MitigatedDamage selfUpperAt(Vec selfFeet, Vec crystalCentre, Collection<Cell> overlays) {
        return selfUpper(crystalCentre, overlays);
    }

    /**
     * Phase 4.2 motion seam: upper bound on damage to one friend with its feet at {@code friendFeet}, or
     * {@code null} when that friend cannot be priced at the position. The default returns the Phase 3
     * current-position bound.
     */
    default MitigatedDamage friendUpperAt(EntityKey friend, Vec friendFeet, Vec crystalCentre,
                                          Collection<Cell> overlays) {
        return friendUppers(crystalCentre, overlays).get(friend);
    }

    /**
     * Phase 4.2 safety seam: a bound covering the whole predicted self hull, not one sampled point. The
     * production reader prices the true closest point of the translated body box to the crystal at full
     * exposure (cover ignored) and pushes it through the weakest mitigation envelope, so the value can
     * never fall below the damage at any reachable point. Worlds without hull pricing delegate to
     * {@link #selfUpperAt}, which keeps their Phase 3 number.
     */
    default MitigatedDamage selfUpperWorst(Vec selfFeet, Vec crystalCentre, Collection<Cell> overlays) {
        return selfUpperAt(selfFeet, crystalCentre, overlays);
    }

    /** Friend analogue of {@link #selfUpperWorst}; {@code null} when the friend cannot be priced. */
    default MitigatedDamage friendUpperWorst(EntityKey friend, Vec friendFeet, Vec crystalCentre,
                                             Collection<Cell> overlays) {
        return friendUpperAt(friend, friendFeet, crystalCentre, overlays);
    }
}
