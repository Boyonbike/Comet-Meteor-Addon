/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.List;
import java.util.Objects;

/**
 * A planner proposal. Planners never send; the engine filters, chooses and hands an intent to the
 * scheduler, and only the runtime dispatches.
 */
public record Opportunity(long id, Strategy strategy, EntityKey target, Vec explosion,
                          TargetEstimate targetDamage, ExposureBound exposure, int etaTicks,
                          double expectedDamage20, int actionCost, List<Intent> plan, double confidence,
                          Footprint footprint) {
    public Opportunity {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(targetDamage, "targetDamage");
        Objects.requireNonNull(exposure, "exposure");
        Objects.requireNonNull(footprint, "footprint");
        plan = plan == null ? List.of() : List.copyOf(plan);
    }
}
