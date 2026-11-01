/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 6.1 pure choice. Given every planner proposal that already passed its own planner, this class
 * applies the plan's hard filters and ranking exactly:
 *
 * <ol>
 *   <li>hard filter: policy, risk budget, ledger exclusions and footprint conflicts with a committed
 *       plan;</li>
 *   <li>lethal first: the earliest credible lethal opportunity, where credible means confidence at least
 *       {@value #LETHAL_CONFIDENCE} <em>and</em> the guaranteed lower bound at least the target's
 *       effective-health upper bound. The expected value never claims lethality;</li>
 *   <li>otherwise rank by {@code confidence * usefulDamage20 / max(1, etaTicks)};</li>
 *   <li>tie-break lower self upper bound, then fewer actions, then the stable opportunity id.</li>
 * </ol>
 *
 * <p>Unknown values are never replaced with plausible numbers: a proposal with a missing/non-finite field
 * is filtered with {@link Choice.Reason#MISSING_DATA} and a proposal with zero confidence with
 * {@link Choice.Reason#UNKNOWN_CONFIDENCE}. The 20-tick damage is capped at the target's remaining
 * effective health so a huge expected value cannot outrank a lethal line by wasting damage the target no
 * longer has. When the target's health upper bound is unknown the cap is the plan's own value: that is
 * conservative because it can only rank a non-lethal plan too high, never claim a lethal one.</p>
 */
public final class CombatChoice {
    /** Plan 6.1: a lethal claim needs confidence at least this; a weak estimate ranks but never kills. */
    public static final double LETHAL_CONFIDENCE = 0.7;

    private CombatChoice() {
    }

    /**
     * Everything the choice is not allowed to decide for itself: the policy, the committed risk, the
     * ledger exclusions and the footprint conflict with a committed plan.
     *
     * <p>The four hard-filter dimensions are abstract on purpose: a gate that forgets one must fail to
     * compile rather than silently allow an unfiltered plan. Only the target-health upper bound has a
     * default, and unknown is positive infinity, which can never support a lethal claim.</p>
     */
    public interface Gate {
        boolean policyAllows(Opportunity opportunity);

        boolean riskAllows(Opportunity opportunity);

        boolean ledgerAllows(Opportunity opportunity);

        /** Serialised hybrid: a committed plan blocks every other plan while it runs. */
        boolean committedPlanAllows(Opportunity opportunity);

        /**
         * Target effective-health upper bound, or {@link Double#POSITIVE_INFINITY} when it is unknown.
         * Only a finite value can support a lethal claim.
         */
        default double targetHealthUpper(EntityKey target) {
            return Double.POSITIVE_INFINITY;
        }
    }

    /** A candidate together with the utility the ranking assigned it; hysteresis compares these. */
    public record Ranked(Opportunity opportunity, double utility) {
        public Ranked {
            Objects.requireNonNull(opportunity, "opportunity");
            if (!Double.isFinite(utility) || utility < 0.0) utility = 0.0;
        }

        public long id() {
            return opportunity.id();
        }
    }

    /** Plan 6.1 entry point. {@code snapshot} may be null; it only documents which revision was ranked. */
    public static Choice choose(List<Opportunity> all, Snapshot snapshot, Gate gate) {
        Objects.requireNonNull(gate, "gate");
        List<Choice.Rejection> rejected = new ArrayList<>();
        List<Ranked> candidates = new ArrayList<>();
        if (all == null) return new Choice(Optional.empty(), rejected, 0.0, false);

        for (Opportunity opportunity : all) {
            if (opportunity == null) continue;
            Choice.Reason missing = missingData(opportunity);
            if (missing != null) {
                rejected.add(new Choice.Rejection(opportunity.id(), opportunity.strategy(), missing));
                continue;
            }
            if (opportunity.confidence() <= 0.0) {
                rejected.add(new Choice.Rejection(opportunity.id(), opportunity.strategy(),
                    Choice.Reason.UNKNOWN_CONFIDENCE));
                continue;
            }
            if (!gate.policyAllows(opportunity)) {
                rejected.add(new Choice.Rejection(opportunity.id(), opportunity.strategy(),
                    Choice.Reason.POLICY));
                continue;
            }
            if (!gate.riskAllows(opportunity)) {
                rejected.add(new Choice.Rejection(opportunity.id(), opportunity.strategy(),
                    Choice.Reason.RISK, "risk budget"));
                continue;
            }
            if (!gate.ledgerAllows(opportunity)) {
                rejected.add(new Choice.Rejection(opportunity.id(), opportunity.strategy(),
                    Choice.Reason.LEDGER));
                continue;
            }
            if (!gate.committedPlanAllows(opportunity)) {
                rejected.add(new Choice.Rejection(opportunity.id(), opportunity.strategy(),
                    Choice.Reason.COMMITTED, "committed plan"));
                continue;
            }
            double useful = usefulDamage20(opportunity, gate.targetHealthUpper(opportunity.target()));
            if (useful <= 0.0 && opportunity.targetDamage().guaranteedLower() <= 0.0) {
                rejected.add(new Choice.Rejection(opportunity.id(), opportunity.strategy(),
                    Choice.Reason.NO_USEFUL_DAMAGE));
                continue;
            }
            candidates.add(new Ranked(opportunity, utility(opportunity, useful)));
        }

        if (candidates.isEmpty()) return new Choice(Optional.empty(), rejected, 0.0, false);

        Optional<Ranked> lethal = candidates.stream()
            .filter(candidate -> credibleLethal(candidate.opportunity(),
                gate.targetHealthUpper(candidate.opportunity().target())))
            .min(LETHAL_ORDER);
        if (lethal.isPresent()) {
            for (Ranked candidate : candidates) {
                if (candidate.id() != lethal.get().id()) {
                    rejected.add(new Choice.Rejection(candidate.id(),
                        candidate.opportunity().strategy(), Choice.Reason.LOWER_RANK, "lethal first"));
                }
            }
            return new Choice(Optional.of(lethal.get().opportunity()), rejected,
                lethal.get().utility(), true);
        }

        candidates.sort(RANKING);
        Ranked winner = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            Ranked candidate = candidates.get(i);
            rejected.add(new Choice.Rejection(candidate.id(), candidate.opportunity().strategy(),
                Choice.Reason.LOWER_RANK,
                "utility " + round(candidate.utility()) + " < " + round(winner.utility())));
        }
        return new Choice(Optional.of(winner.opportunity()), rejected, winner.utility(), false);
    }

    /** Same list, ranked, so hysteresis can compare the current plan with the challenger. */
    public static List<Ranked> ranked(List<Opportunity> candidates, Gate gate) {
        List<Ranked> ranked = new ArrayList<>();
        if (candidates == null) return ranked;
        for (Opportunity opportunity : candidates) {
            if (opportunity == null || missingData(opportunity) != null) continue;
            if (opportunity.confidence() <= 0.0) continue;
            if (!gate.policyAllows(opportunity) || !gate.riskAllows(opportunity)
                || !gate.ledgerAllows(opportunity) || !gate.committedPlanAllows(opportunity)) {
                continue;
            }
            double useful = usefulDamage20(opportunity, gate.targetHealthUpper(opportunity.target()));
            if (useful <= 0.0 && opportunity.targetDamage().guaranteedLower() <= 0.0) continue;
            ranked.add(new Ranked(opportunity, utility(opportunity, useful)));
        }
        ranked.sort(RANKING);
        return ranked;
    }

    /**
     * Ranking utility for an already safe proposal: {@code confidence * usefulDamage20 / max(1, eta)}.
     * Deterministic, no clock and no invented probability.
     */
    public static double utility(Opportunity opportunity, double usefulDamage20) {
        Objects.requireNonNull(opportunity, "opportunity");
        double eta = Math.max(1, opportunity.etaTicks());
        double value = opportunity.confidence() * usefulDamage20 / eta;
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
    }

    /** Damage the plan can still contribute: its 20-tick damage capped by remaining target health. */
    public static double usefulDamage20(Opportunity opportunity, double healthUpper) {
        double damage = opportunity.expectedDamage20();
        if (!Double.isFinite(damage) || damage <= 0.0) return 0.0;
        if (!Double.isFinite(healthUpper) || healthUpper < 0.0) return damage;
        return Math.min(damage, healthUpper);
    }

    /** Credible lethal: strong enough confidence and a guaranteed lower bound at least the health upper. */
    public static boolean credibleLethal(Opportunity opportunity, double healthUpper) {
        if (opportunity == null) return false;
        double confidence = opportunity.confidence();
        if (!Double.isFinite(confidence) || confidence < LETHAL_CONFIDENCE) return false;
        if (!Double.isFinite(healthUpper) || healthUpper <= 0.0) return false;
        double guaranteed = opportunity.targetDamage().guaranteedLower();
        return Double.isFinite(guaranteed) && guaranteed >= healthUpper;
    }

    private static Choice.Reason missingData(Opportunity opportunity) {
        if (opportunity.strategy() == null || opportunity.targetDamage() == null
            || opportunity.exposure() == null || opportunity.footprint() == null
            || opportunity.exposure().selfUpper() == null) {
            return Choice.Reason.MISSING_DATA;
        }
        double confidence = opportunity.confidence();
        if (!Double.isFinite(confidence) || confidence < 0.0) return Choice.Reason.MISSING_DATA;
        TargetEstimate estimate = opportunity.targetDamage();
        if (!finiteNonNegative(estimate.guaranteedLower()) || !finiteNonNegative(estimate.expected())
            || !finiteNonNegative(estimate.optimistic())) {
            return Choice.Reason.MISSING_DATA;
        }
        if (!finiteNonNegative(opportunity.expectedDamage20())) return Choice.Reason.MISSING_DATA;
        if (opportunity.etaTicks() < 0 || opportunity.actionCost() < 0) return Choice.Reason.MISSING_DATA;
        if (opportunity.exposure().friendUpper() == null) return Choice.Reason.MISSING_DATA;
        for (var friend : opportunity.exposure().friendUpper().entrySet()) {
            if (friend.getKey() == null || friend.getValue() == null) return Choice.Reason.MISSING_DATA;
            if (!finiteNonNegative(friend.getValue().beforeAbsorption())) {
                return Choice.Reason.MISSING_DATA;
            }
        }
        Set<EntityKey> bound = opportunity.footprint().bound();
        if (bound == null) return Choice.Reason.MISSING_DATA;
        for (EntityKey entity : bound) {
            if (entity == null) return Choice.Reason.MISSING_DATA;
        }
        return null;
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    /** Lethal first, then earliest arrival; the remaining keys only break exact ties. */
    private static final Comparator<Ranked> LETHAL_ORDER =
        Comparator.comparingInt((Ranked ranked) -> Math.max(1, ranked.opportunity().etaTicks()))
            .thenComparing(Comparator.comparingDouble(
                (Ranked ranked) -> ranked.opportunity().targetDamage().guaranteedLower()).reversed())
            .thenComparingDouble(CombatChoice::selfUpper)
            .thenComparingInt(ranked -> ranked.opportunity().actionCost())
            .thenComparingLong(Ranked::id);

    private static final Comparator<Ranked> RANKING =
        Comparator.comparingDouble(Ranked::utility).reversed()
            .thenComparingDouble(CombatChoice::selfUpper)
            .thenComparingInt(ranked -> ranked.opportunity().actionCost())
            .thenComparingLong(Ranked::id);

    private static double selfUpper(Ranked ranked) {
        return ranked.opportunity().exposure().selfUpper().beforeAbsorption();
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
