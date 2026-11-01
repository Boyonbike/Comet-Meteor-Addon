/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Optional;

/**
 * Phase 6.4 stage 1: the ranking-only view of a piston trap plan.
 *
 * <p>A committed trap stays owned by its controller: this class never sends, never reserves and never
 * touches the trap's hazards, leases or risk. It only turns a {@link PistonPlanner.Plan} into the
 * {@link Opportunity} shape the choice understands, so Hybrid can rank a trap against a direct plan. A
 * field that cannot be established from the plan is not guessed: the whole opportunity is absent and the
 * caller reports that a piston opportunity was not offered.</p>
 *
 * <p>Two fields are deliberately derived rather than measured:</p>
 * <ul>
 *   <li>{@code etaTicks} is the serialised lower bound: one send per tick, so a plan can never land faster
 *       than its action count. Every stage additionally waits for its confirmation, which this layer cannot
 *       observe, so the bound is optimistic and the confidence below is lowered for it.</li>
 *   <li>{@code expectedDamage20} carries the plan's established target damage. The plan pays that damage at
 *       {@code eta}, not inside a literal 20-tick window, and the ratio already prices the longer horizon.
 *       Filling the field with a literal 20-tick window would price every trap at zero (a trap cannot land
 *       inside 20 ticks), which would make Hybrid identical to DirectOnly by construction rather than by
 *       choice. This is recorded as an open plan question, not as a measured probability.</li>
 * </ul>
 */
public final class PistonOpportunity {
    /** Ranking ids are namespaced per planner so a trap plan id can never alias a direct intent id. */
    public static final long ID_BASE = 0x3000_0000_0000_0000L;

    /**
     * An unconfirmed trap is priced at the same WEAK factor the rest of the layer uses for a predicted,
     * not observed, position. The pushed-position damage is an audited estimate, not an observation.
     */
    private static final double CONFIDENCE = Confidence.WEAK.factor();

    private PistonOpportunity() {
    }

    /**
     * @return the ranking view, or empty when any field the choice needs is missing (no target, no action
     *     list, no footprint, or a target damage that is not a finite positive number)
     */
    public static Optional<Opportunity> of(PistonPlanner.Plan plan, MitigationEnvelope envelope) {
        if (plan == null || envelope == null) return Optional.empty();
        if (plan.target() == null || plan.intents().isEmpty() || plan.footprint() == null) {
            return Optional.empty();
        }
        double damage = plan.targetDamage();
        if (!Double.isFinite(damage) || damage <= 0.0) return Optional.empty();
        int eta = Math.max(1, plan.intents().size());
        return Optional.of(new Opportunity(
            ID_BASE + plan.planId(),
            Strategy.PISTON,
            plan.target(),
            plan.expectedPushed(),
            plan.targetEstimate(),
            plan.exposure(envelope),
            eta,
            damage,
            plan.intents().size(),
            plan.intents(),
            CONFIDENCE,
            plan.footprint()));
    }
}
