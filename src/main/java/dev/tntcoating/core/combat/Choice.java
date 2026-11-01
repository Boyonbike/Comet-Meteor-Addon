/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 6.1 decision record: the opportunity the engine follows and the reason every other candidate was
 * dropped. The choice itself never sends and never touches risk: it only answers "which safe plan, and
 * why not the others" so the metrics and the debug log can explain a hybrid tick.
 *
 * <p>Reasons are recorded per opportunity, never merged into a score. {@code LOWER_RANK} is the only
 * reason that means "safe but worse"; every other reason is a hard filter.</p>
 */
public record Choice(Optional<Opportunity> winner, List<Rejection> rejected, double utility,
                     boolean lethal) {
    public Choice {
        Objects.requireNonNull(winner, "winner");
        rejected = rejected == null
            ? List.of()
            : List.copyOf(rejected.stream().filter(Objects::nonNull).toList());
        if (!Double.isFinite(utility) || utility < 0.0) utility = 0.0;
    }

    public static Choice empty() {
        return new Choice(Optional.empty(), List.of(), 0.0, false);
    }

    /** One rejected candidate: the stable opportunity id, its strategy and the reason it lost. */
    public record Rejection(long opportunityId, Strategy strategy, Reason reason, String detail) {
        public Rejection {
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(reason, "reason");
        }

        public Rejection(long opportunityId, Strategy strategy, Reason reason) {
            this(opportunityId, strategy, reason, null);
        }
    }

    /** The hard filters of plan 6.1, in the order they are evaluated. */
    public enum Reason {
        /** A field needed for ranking is absent, non-finite or negative. Never guessed. */
        MISSING_DATA,
        /** Confidence is zero (LOST): the plan exists but nothing is known about it. */
        UNKNOWN_CONFIDENCE,
        /** The damage policy refused the exposure or the target floor. */
        POLICY,
        /** The committed risk plus this reservation would break the safety margin. */
        RISK,
        /** A ledger hazard, quarantined crystal, blocked cell or protected spawn excludes the plan. */
        LEDGER,
        /** A committed plan (serialised hybrid: a live trap) blocks this plan while it runs. */
        COMMITTED,
        /** Safe, but delivers no useful damage (nothing left to remove from the target). */
        NO_USEFUL_DAMAGE,
        /** Safe, but ranked below the winner. */
        LOWER_RANK
    }
}
