/*
 * Original Comet test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 6.4 stage 2 gate evidence: 1,000 seeded interleavings of direct, piston, defence and cleanup
 * through the real choice, hysteresis, cleanup hook and risk budget, with serialised offensive sends only.
 *
 * <p>These seeds test the logic of the serialised contract, not independent real encounters: the world
 * model is synthetic and the only safety it can prove is the one enforced by {@link CombatRiskBudget} plus
 * the invariants below. Concurrency is explicitly off: at most one offensive plan owns the slot, a
 * committed trap blocks every offensive alternative, defence is its own runner evaluated every tick,
 * cleanup keeps the next free offensive slot once due, and a callback for a superseded attempt is ignored
 * instead of applied.</p>
 */
final class HybridInterleavingTest {
    private static final ConnectionKey CONN = new ConnectionKey(1L);
    private static final EntityKey SELF = ChoiceFixtures.entity(99);
    private static final EntityKey ENEMY = ChoiceFixtures.entity(1);
    private static final Safety SAFETY = new Safety(4.0, 6.0, 2.0, 2.0);
    private static final int TICKS = 160;
    /** selfLower - minRemaining: the tightest bound the budget can ever leave outstanding. */
    private static final double RISK_CEILING = 20.0 - 2.0;

    @Test
    void thousandSeededInterleavingsKeepTheSerialisedContracts() {
        int defenceDuringTrap = 0;
        int cleanupDispatches = 0;
        int staleCallbacks = 0;
        for (long index = 0; index < 1_000; index++) {
            long seed = index;
            Sim result = run(seed);
            assertTrue(result.violations.isEmpty(), () -> "seed " + seed + ": " + result.violations);
            defenceDuringTrap += result.defenceDuringTrap;
            cleanupDispatches += result.cleanupDispatches;
            staleCallbacks += result.staleIgnored;
        }
        assertTrue(defenceDuringTrap > 0, "defensive plans must execute while a trap is committed");
        assertTrue(cleanupDispatches > 0, "a due cleanup must reach the next free slot");
        assertTrue(staleCallbacks > 0, "late callbacks for superseded attempts must be exercised");
    }

    private static Sim run(long seed) {
        Random random = new Random(seed);
        CombatRiskBudget budget = new CombatRiskBudget((envelope, current) -> current);
        SafetyState state = new SafetyState(CONN, new ActionGeneration(1), SELF,
            new EffectiveHealth(20, 0, Capability.KNOWN, 0), Map.of(), SAFETY);
        Hysteresis hysteresis = new Hysteresis();
        CleanupPriority cleanup = new CleanupPriority();
        CleanupPriority.Deadline deadline = new CleanupPriority.Deadline(CONN, new ActionGeneration(1));
        long nextId = 100;
        long committedTrap = -1;
        int defenceDuringTrap = 0;
        int cleanupDispatches = 0;
        int staleIgnored = 0;

        List<String> violations = new ArrayList<>();
        Set<Long> sentIntents = new HashSet<>();
        Map<Long, Integer> issuedSerial = new HashMap<>();
        Map<Long, Integer> abortedSerial = new HashMap<>();
        Map<Long, Integer> lastApplied = new HashMap<>();
        List<Callback> callbacks = new ArrayList<>();
        Map<Long, Integer> inFlight = new HashMap<>();

        for (int tick = 1; tick <= TICKS; tick++) {
            budget.tick(tick);
            if (random.nextInt(45) == 0 && !cleanup.state().waiting()) {
                cleanup.request(tick, deadline, ChoiceFixtures.entity(7));
            }
            cleanup.sample(tick);
            boolean cleanupDue = cleanup.state().due();
            List<Opportunity> produced = produce(random, tick, nextId, cleanupDue);
            nextId += produced.size() + 1L;

            // Cleanup owns the next free slot: nothing else may take it once the starvation deadline is met.
            if (cleanupDue && inFlight.isEmpty()) {
                cleanupDispatches += dispatchCleanup(cleanup, sentIntents, issuedSerial, callbacks,
                    budget, state, tick, violations);
                staleIgnored += expireCallbacks(tick, callbacks, issuedSerial, abortedSerial,
                    lastApplied, violations);
                continue;
            }

            // Defence is its own runner: evaluated every tick, including while a trap is committed. It has
            // its own risk reservation and never takes the offensive slot the trap owns.
            Optional<Opportunity> defence = produced.stream()
                .filter(plan -> plan.strategy() == Strategy.DEFEND).findFirst();
            if (defence.isPresent() && dispatchDefence(defence.get(), issuedSerial, callbacks, budget,
                state, tick)) {
                if (committedTrap >= 0) defenceDuringTrap++;
            }

            List<Opportunity> offensive = produced.stream()
                .filter(plan -> plan.strategy() != Strategy.DEFEND).toList();
            if (offensive.isEmpty()) {
                staleIgnored += expireCallbacks(tick, callbacks, issuedSerial, abortedSerial, lastApplied, violations);
                continue;
            }
            CombatChoice.Gate gate = gate(committedTrap);
            Choice choice = CombatChoice.choose(offensive, null, gate);
            List<CombatChoice.Ranked> ranked = CombatChoice.ranked(offensive, gate);
            Hysteresis.Decision decision = hysteresis.advance(tick, ranked, false);
            Optional<Opportunity> chosen = decision.plan().or(() -> choice.winner());
            if (chosen.isEmpty() || produced.stream().noneMatch(plan -> plan.id() == chosenId(chosen))) {
                // A held plan keeps its slot only while it is still produced; its owner, not this loop,
                // continues sending it. Without a fresh candidate there is nothing to dispatch.
                staleIgnored += expireCallbacks(tick, callbacks, issuedSerial, abortedSerial, lastApplied, violations);
                continue;
            }
            Opportunity plan = chosen.get();
            boolean offensivePlan = plan.strategy() == Strategy.DIRECT || plan.strategy() == Strategy.PISTON;
            if (committedTrap >= 0 && offensivePlan && plan.id() != committedTrap) {
                violations.add("offensive plan " + plan.id() + " chosen while trap " + committedTrap
                    + " is committed at tick " + tick);
            }
            if (!inFlight.isEmpty() && !inFlight.containsKey(plan.id())) {
                // Serialised: the slot belongs to the plan already running; the choice waits its turn.
                staleIgnored += expireCallbacks(tick, callbacks, issuedSerial, abortedSerial, lastApplied, violations);
                continue;
            }
            if (inFlight.size() > 1) {
                violations.add("double slot ownership: " + inFlight.keySet() + " at tick " + tick);
            }
            if (committedTrap >= 0 && plan.strategy() == Strategy.CLEANUP) {
                violations.add("cleanup took the trap's slot at tick " + tick);
            }

            Integer remaining = inFlight.get(plan.id());
            if (remaining == null) {
                remaining = Math.max(1, plan.actionCost());
                inFlight.put(plan.id(), remaining);
                if (plan.strategy() == Strategy.PISTON) committedTrap = plan.id();
                hysteresis.markLive();
            }
            int attemptId = issuedSerial.getOrDefault(plan.id(), 0);
            long intentId = plan.id() * 100_000L + attemptId * 100L + remaining;
            if (!sentIntents.add(intentId)) {
                violations.add("intent " + intentId + " was sent twice at tick " + tick);
            }
            Optional<RiskId> reserved = budget.reserve(intentId, plan.exposure(),
                new ServerTickInterval(tick + 1, tick + 3), state);
            if (reserved.isEmpty()) {
                // The budget refused the reservation: the attempt is cancelled and nothing was sent.
                abortAttempt(plan.id(), issuedSerial, abortedSerial, inFlight);
                if (committedTrap == plan.id()) committedTrap = -1;
                hysteresis.actionFinished();
                staleIgnored += expireCallbacks(tick, callbacks, issuedSerial, abortedSerial, lastApplied, violations);
                continue;
            }
            int serial = issuedSerial.merge(plan.id(), 1, Integer::sum);
            callbacks.add(new Callback(plan.id(), serial, tick + 2));
            if (remaining <= 1) {
                // A completed attempt keeps its final callback valid; only a cancelled attempt is aborted.
                inFlight.remove(plan.id());
                if (committedTrap == plan.id()) committedTrap = -1;
                hysteresis.actionFinished();
            } else {
                inFlight.put(plan.id(), remaining - 1);
            }

            if (random.nextInt(40) == 0 && !inFlight.isEmpty()) {
                // A live plan abort (deadline, failed confirmation, invalidation) leaves its callbacks
                // pending; they must arrive later and be dropped, never applied to the next attempt.
                long abortedPlan = inFlight.keySet().iterator().next();
                abortAttempt(abortedPlan, issuedSerial, abortedSerial, inFlight);
                if (committedTrap == abortedPlan) committedTrap = -1;
                hysteresis.actionFinished();
            }

            double bound = budget.current(SELF).value();
            if (bound > RISK_CEILING + 1.0e-9) {
                violations.add("risk bound grew past the margin at tick " + tick + ": " + bound);
            }
            staleIgnored += expireCallbacks(tick, callbacks, issuedSerial, abortedSerial, lastApplied, violations);
        }
        return new Sim(violations, defenceDuringTrap, cleanupDispatches, staleIgnored);
    }

    private static boolean dispatchDefence(Opportunity plan, Map<Long, Integer> issuedSerial,
                                           List<Callback> callbacks, CombatRiskBudget budget,
                                           SafetyState state, int tick) {
        long intentId = plan.id() * 100_000L;
        Optional<RiskId> reserved = budget.reserve(intentId, plan.exposure(),
            new ServerTickInterval(tick + 1, tick + 3), state);
        if (reserved.isEmpty()) return false;
        int serial = issuedSerial.merge(plan.id(), 1, Integer::sum);
        callbacks.add(new Callback(plan.id(), serial, tick + 2));
        return true;
    }

    private static int dispatchCleanup(CleanupPriority cleanup, Set<Long> sentIntents,
                                       Map<Long, Integer> issuedSerial, List<Callback> callbacks,
                                       CombatRiskBudget budget, SafetyState state, int tick,
                                       List<String> violations) {
        Opportunity plan = opportunity(900_000L + tick, Strategy.CLEANUP, 1, 1.0, 1.0, 0.0, 1, tick);
        long intentId = plan.id() * 100_000L;
        if (!sentIntents.add(intentId)) {
            violations.add("cleanup intent sent twice at tick " + tick);
        }
        Optional<RiskId> reserved = budget.reserve(intentId, plan.exposure(),
            new ServerTickInterval(tick + 1, tick + 3), state);
        if (reserved.isEmpty()) {
            cleanup.clear();
            return 0;
        }
        int serial = issuedSerial.merge(plan.id(), 1, Integer::sum);
        callbacks.add(new Callback(plan.id(), serial, tick + 2));
        cleanup.clear();
        return 1;
    }

    private static void abortAttempt(long planId, Map<Long, Integer> issuedSerial,
                                     Map<Long, Integer> abortedSerial, Map<Long, Integer> inFlight) {
        abortedSerial.put(planId, issuedSerial.getOrDefault(planId, 0));
        inFlight.remove(planId);
    }

    /**
     * A due callback is applied only to the current attempt of a plan that was not aborted; one for a
     * superseded serial is dropped. Processing an aborted attempt is the stale-callback violation.
     */
    private static int expireCallbacks(int tick, List<Callback> callbacks,
                                       Map<Long, Integer> issuedSerial,
                                       Map<Long, Integer> abortedSerial,
                                       Map<Long, Integer> lastApplied, List<String> violations) {
        int ignored = 0;
        for (int i = callbacks.size() - 1; i >= 0; i--) {
            Callback callback = callbacks.get(i);
            if (callback.dueTick() > tick) continue;
            callbacks.remove(i);
            boolean current = callback.serial() == issuedSerial.getOrDefault(callback.planId(), 0)
                && callback.serial() > abortedSerial.getOrDefault(callback.planId(), -1);
            if (!current) {
                ignored++;
                continue;
            }
            int previous = lastApplied.getOrDefault(callback.planId(), 0);
            if (callback.serial() <= previous) {
                violations.add("callback " + callback.serial() + " for plan " + callback.planId()
                    + " was applied twice");
            }
            lastApplied.put(callback.planId(), callback.serial());
        }
        return ignored;
    }

    private static CombatChoice.Gate gate(long committedTrap) {
        return new CombatChoice.Gate() {
            @Override
            public boolean policyAllows(Opportunity opportunity) {
                return true;
            }

            @Override
            public boolean riskAllows(Opportunity opportunity) {
                return true;
            }

            @Override
            public boolean ledgerAllows(Opportunity opportunity) {
                return true;
            }

            @Override
            public boolean committedPlanAllows(Opportunity opportunity) {
                if (committedTrap < 0 || opportunity.id() == committedTrap) return true;
                return opportunity.strategy() == Strategy.CLEANUP;
            }

            @Override
            public double targetHealthUpper(EntityKey target) {
                return 20.0;
            }
        };
    }

    private static List<Opportunity> produce(Random random, int tick, long nextId, boolean cleanupDue) {
        List<Opportunity> plans = new ArrayList<>();
        long id = nextId;
        if (random.nextInt(4) > 0) {
            double damage = 2.0 + random.nextInt(110) / 10.0;
            double selfUpper = random.nextInt(30) / 10.0;
            double confidence = random.nextBoolean() ? 1.0 : Confidence.WEAK.factor();
            plans.add(opportunity(id++, Strategy.DIRECT, 1 + random.nextInt(3), damage, confidence,
                selfUpper, 1, tick));
        }
        if (random.nextInt(5) > 0) {
            double damage = 6.0 + random.nextInt(150) / 10.0;
            double selfUpper = random.nextInt(30) / 10.0;
            int actions = 3 + random.nextInt(4);
            plans.add(opportunity(id++, Strategy.PISTON, actions, damage, Confidence.WEAK.factor(),
                selfUpper, actions, tick));
        }
        if (random.nextInt(9) == 0) {
            double damage = 1.0 + random.nextInt(60) / 10.0;
            plans.add(opportunity(id++, Strategy.DEFEND, 1 + random.nextInt(2), damage, 1.0,
                random.nextInt(20) / 10.0, 1, tick));
        }
        if (cleanupDue) {
            plans.add(opportunity(id++, Strategy.CLEANUP, 1, 1.0, 1.0, 0.0, 1, tick));
        }
        return plans;
    }

    private static Opportunity opportunity(long id, Strategy strategy, int actions, double damage,
                                           double confidence, double selfUpper, int actionCost,
                                           int tick) {
        List<Intent> intents = new ArrayList<>();
        for (int i = 0; i < actions; i++) {
            intents.add(new Intent(id * 100 + i, id, strategy, actionFor(strategy),
                new Cell(i, 64, 0), null, Set.of(), Set.of(), Set.of(), tick + 20));
        }
        return new Opportunity(id, strategy, ENEMY, new Vec(0.5, 65.0, 0.5),
            new TargetEstimate(damage, damage, damage), ChoiceFixtures.exposure(selfUpper),
            1 + actions, damage, actionCost, intents, confidence,
            new Footprint(Set.of(new Cell((int) id % 32, 64, 0)), Box.fromCell(new Cell(0, 64, 0)),
                Box.fromCell(new Cell(0, 64, 0)), Set.of()));
    }

    private static long chosenId(Optional<Opportunity> chosen) {
        return chosen.map(Opportunity::id).orElse(-1L);
    }

    private static Action actionFor(Strategy strategy) {
        return switch (strategy) {
            case DIRECT -> Action.PLACE_CRYSTAL;
            case PISTON -> Action.PLACE_PISTON;
            case DEFEND -> Action.ATTACK_CRYSTAL;
            case CLEANUP -> Action.MINE_POWER;
        };
    }

    private record Callback(long planId, int serial, int dueTick) {
    }

    private record Sim(List<String> violations, int defenceDuringTrap, int cleanupDispatches,
                       int staleIgnored) {
    }
}
