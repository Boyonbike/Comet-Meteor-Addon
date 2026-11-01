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
 * Phase 6.2 hysteresis. It answers "may the hybrid engine follow a better-looking plan than the one it is
 * already following" and nothing else: it never sends, never reserves and never retires risk.
 *
 * <p>Rules, in order:</p>
 * <ol>
 *   <li>the first admissible plan is adopted immediately (there is nothing to protect yet);</li>
 *   <li>once a plan is current, a different safe plan replaces it only when its utility exceeds the
 *       current plan's utility by {@value #SWITCH_MARGIN_FACTOR}x for {@value #SWITCH_TICKS} consecutive
 *       server ticks;</li>
 *   <li>after an irreversible action (a crystal or power placement/push is live), the current plan is
 *       never abandoned for a score. The caller ends that state only when the action finishes or aborts
 *       safely, or when safety evidence invalidates the plan;</li>
 *   <li>safety invalidation overrides hysteresis immediately, in the same tick;</li>
 *   <li>a current plan that is not among this tick's admissible candidates is never replaced on the
 *       spot for a score: while it is live the engine keeps it (live structures are never abandoned for
 *       a score), and before it is live the engine tolerates its absence for the same
 *       {@value #SWITCH_TICKS} ticks before adopting the best admissible plan, so a dropped cache entry
 *       or an unsent proposal cannot cause an immediate switch;</li>
 *   <li>when the target, the strategy or the plan signature changes, the per-plan state is rebuilt, so
 *       challenge counts never leak between plans.</li>
 * </ol>
 *
 * <p>There is no clock: only consecutive calls for strictly increasing server ticks count, so a stalled
 * tick loop can never age a plan over the switch threshold. This class cannot retire risk and has no API
 * that would.</p>
 */
public final class Hysteresis {
    /** Plan 6.2: switching needs a 20% utility advantage, not a 20% damage advantage. */
    public static final double SWITCH_MARGIN_FACTOR = 1.20;
    /** Plan 6.2: the advantage must hold for three consecutive server ticks. */
    public static final int SWITCH_TICKS = 3;

    /** A plan's identity for hysteresis: target, strategy and the intent-set signature. */
    public record Key(EntityKey target, Strategy strategy, long signature) {
        public static Key of(Opportunity opportunity) {
            Objects.requireNonNull(opportunity, "opportunity");
            long signature = 0xcbf29ce484222325L;
            signature = mix(signature, opportunity.actionCost());
            List<Intent> plan = opportunity.plan();
            if (plan != null) {
                for (Intent intent : plan) {
                    if (intent == null) {
                        signature = mix(signature, 0x9e3779b97f4a7c15L);
                        continue;
                    }
                    signature = mix(signature, intent.action() == null ? -1 : intent.action().ordinal());
                    signature = mix(signature, intent.cell() == null ? Long.MIN_VALUE : intent.cell().hashCode());
                    signature = mix(signature, intent.entity() == null ? Long.MIN_VALUE
                        : intent.entity().id());
                }
            }
            return new Key(opportunity.target(), opportunity.strategy(), signature);
        }

        private static long mix(long hash, long value) {
            return (hash ^ value) * 0x100000001b3L;
        }
    }

    /**
     * The plan the engine should follow this tick and what happened to it.
     *
     * @param plan           the plan to follow, empty only when safety invalidation left no safe
     *                       alternative and nothing irreversible is live
     * @param adopted        this tick started following a new plan
     * @param held           a different plan was better by score but the current plan keeps running
     * @param invalidated    safety invalidation replaced the current plan in this tick
     * @param challengeTicks consecutive ticks the challenger has held the margin (0 when none)
     */
    public record Decision(Optional<Opportunity> plan, boolean adopted, boolean held,
                           boolean invalidated, int challengeTicks) {
        public Decision {
            Objects.requireNonNull(plan, "plan");
            if (challengeTicks < 0) challengeTicks = 0;
        }

        static Decision none() {
            return new Decision(Optional.empty(), false, false, false, 0);
        }
    }

    private Opportunity current;
    private Key currentKey;
    private double currentUtility;
    /** The plan's owner started acting: the trap committed or a crystal placement is in flight. */
    private boolean live;
    private boolean irreversible;
    private int absentTicks;
    private Key challengeKey;
    private int challengeTicks;
    private int lastTick = -1;
    private Decision lastDecision = Decision.none();

    /** Ends the per-plan state. Generation, target and strategy changes must call this. */
    public synchronized void reset() {
        current = null;
        currentKey = null;
        currentUtility = 0.0;
        live = false;
        irreversible = false;
        absentTicks = 0;
        challengeKey = null;
        challengeTicks = 0;
        lastTick = -1;
        lastDecision = Decision.none();
    }

    /**
     * Marks the current plan as owning a live irreversible action (a sent crystal or power action). A
     * held plan cannot be scored away from afterwards; only a finish/abort or safety invalidation clears
     * this, never elapsed time.
     */
    public synchronized void markLive() {
        live = true;
    }

    public synchronized void irreversibleActionSent() {
        live = true;
        irreversible = true;
    }

    /** The crystal/power action finished or aborted safely; switching by score is allowed again. */
    public synchronized void actionFinished() {
        live = false;
        irreversible = false;
        absentTicks = 0;
    }

    public synchronized boolean irreversible() {
        return irreversible;
    }

    public synchronized boolean live() {
        return live;
    }

    public synchronized Optional<Opportunity> current() {
        return Optional.ofNullable(current);
    }

    /**
     * One hysteresis step for one server tick.
     *
     * @param serverTick        non-decreasing server tick; a repeated tick is ignored, a backwards tick
     *                          resets the state (a new generation started)
     * @param candidates        admissible, already ranked candidates; the first one is the tick's best
     * @param safetyInvalidated true when the current plan's safety evidence changed; the current plan is
     *                          dropped in this same tick
     */
    public synchronized Decision advance(int serverTick, List<CombatChoice.Ranked> candidates,
                                         boolean safetyInvalidated) {
        if (serverTick < lastTick) reset();
        if (serverTick == lastTick) return lastDecision;
        lastTick = serverTick;
        List<CombatChoice.Ranked> ranked = candidates == null ? List.of() : candidates;
        Decision decision = step(ranked, safetyInvalidated);
        lastDecision = decision;
        return decision;
    }

    private Decision step(List<CombatChoice.Ranked> ranked, boolean safetyInvalidated) {
        CombatChoice.Ranked best = ranked.isEmpty() ? null : ranked.get(0);
        if (safetyInvalidated) {
            live = false;
            irreversible = false;
            absentTicks = 0;
            challengeKey = null;
            challengeTicks = 0;
            if (best == null) {
                current = null;
                currentKey = null;
                currentUtility = 0.0;
                return new Decision(Optional.empty(), true, false, true, 0);
            }
            adopt(best);
            return new Decision(Optional.of(current), true, false, true, 0);
        }

        if (current == null) {
            if (best == null) return Decision.none();
            adopt(best);
            return new Decision(Optional.of(current), true, false, false, 0);
        }

        CombatChoice.Ranked samePlan = find(ranked, currentKey);
        if (samePlan != null) {
            currentUtility = samePlan.utility();
            absentTicks = 0;
        }
        boolean bestIsCurrent = best != null && Key.of(best.opportunity()).equals(currentKey);

        if (best == null) {
            // Nothing admissible this tick. A live plan still owns the field; otherwise the current
            // proposal disappears with the candidates and the next admissible plan may be adopted.
            if (live) return new Decision(Optional.of(current), false, true, false, 0);
            current = null;
            currentKey = null;
            currentUtility = 0.0;
            absentTicks = 0;
            challengeKey = null;
            challengeTicks = 0;
            return Decision.none();
        }

        if (bestIsCurrent) {
            challengeKey = null;
            challengeTicks = 0;
            currentUtility = best.utility();
            return new Decision(Optional.of(current), false, false, false, 0);
        }

        if (samePlan == null && !live) {
            // The current proposal was not re-proposed and nothing depends on it. This is still not an
            // immediate switch: the plan gets the same three-tick tolerance, so a dropped cache entry or
            // a one-tick planning gap cannot flip the decision.
            absentTicks++;
            if (absentTicks >= SWITCH_TICKS) {
                challengeKey = null;
                challengeTicks = 0;
                adopt(best);
                return new Decision(Optional.of(current), true, false, false, 0);
            }
            return new Decision(Optional.of(current), false, true, false, absentTicks);
        }

        if (irreversible || live) {
            challengeKey = null;
            challengeTicks = 0;
            return new Decision(Optional.of(current), false, true, false, 0);
        }

        Key challenger = Key.of(best.opportunity());
        boolean margin = best.utility() > currentUtility * SWITCH_MARGIN_FACTOR;
        if (margin) {
            if (challenger.equals(challengeKey)) {
                challengeTicks++;
            } else {
                challengeKey = challenger;
                challengeTicks = 1;
            }
        } else {
            challengeKey = null;
            challengeTicks = 0;
        }

        if (challengeTicks >= SWITCH_TICKS) {
            challengeKey = null;
            challengeTicks = 0;
            adopt(best);
            return new Decision(Optional.of(current), true, false, false, 0);
        }
        return new Decision(Optional.of(current), false, true, false, challengeTicks);
    }

    private void adopt(CombatChoice.Ranked ranked) {
        current = ranked.opportunity();
        currentKey = Key.of(current);
        currentUtility = ranked.utility();
        live = false;
        irreversible = false;
        absentTicks = 0;
    }

    private static CombatChoice.Ranked find(List<CombatChoice.Ranked> ranked, Key key) {
        if (key == null) return null;
        for (CombatChoice.Ranked candidate : ranked) {
            if (key.equals(Key.of(candidate.opportunity()))) return candidate;
        }
        return null;
    }

    /** Debug text for the log header: current plan, utility and live challenge. */
    public synchronized String describe() {
        if (current == null) return "none";
        StringBuilder text = new StringBuilder();
        text.append(current.strategy()).append('#').append(current.id());
        text.append(" utility=")
            .append(String.format(java.util.Locale.ROOT, "%.3f", currentUtility));
        if (irreversible) text.append(" irreversible");
        if (challengeTicks > 0) {
            text.append(" challenge=").append(challengeTicks).append('/').append(SWITCH_TICKS);
        }
        return text.toString();
    }
}
