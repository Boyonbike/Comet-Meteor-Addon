/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Deterministic seeded random sequences over the reserve/dispatch/observe/cancel surface. Two invariants:
 *
 * <ul>
 *   <li>an accepted reservation never pushes the summed outstanding self bound past the margin;
 *   <li>no risk reaches retirement without rule-1 evidence (an associated explosion revision followed
 *       by a coherent self-health snapshot with a strictly greater applied revision).
 * </ul>
 *
 * The grouped-bound path is disabled here on purpose; grouping has its own fallback tests.
 */
class CombatRiskPropertyTest {
    private static final double SELF_LOWER = 20.0;
    private static final double MARGIN = 2.0;

    @Test
    void acceptedReservationsRespectTheMarginAndRetirementNeedsEvidence() {
        for (long seed : new long[] {1L, 7L, 42L, 97L, 2_024L}) {
            runSequence(seed);
        }
    }

    private void runSequence(long seed) {
        Random random = new Random(seed);
        CombatRiskBudget budget = RiskTestSupport.conservative();
        Map<RiskId, RiskModel> risks = new HashMap<>();
        // Risk id -> the health revision whose snapshot retired it.
        Map<RiskId, Long> retirementRevision = new HashMap<>();

        int iterations = 400 + random.nextInt(400);
        for (int iteration = 0; iteration < iterations; iteration++) {
            int action = random.nextInt(10);
            if (action <= 1) {
                reserve(random, budget, risks);
            } else if (action <= 3) {
                dispatch(random, budget, risks);
            } else if (action == 4) {
                budget.tick(random.nextInt(500));
            } else if (action <= 6) {
                long revision = random.nextInt(60);
                explode(budget, risks, revision);
                verifyRetirements(budget, risks, retirementRevision, -1L);
            } else if (action <= 8) {
                long revision = random.nextInt(80);
                budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, SELF_LOWER, 0.0,
                    revision, revision + 2));
                verifyRetirements(budget, risks, retirementRevision, revision);
            } else {
                cancel(random, budget, risks);
            }

            double outstanding = budget.current(RiskTestSupport.SELF).value();
            assertTrue(outstanding < SELF_LOWER - MARGIN,
                "seed " + seed + " iteration " + iteration + ": margin " + outstanding);
            assertTrue(outstanding >= 0.0);
            assertEquals(outstandingCount(budget, risks), budget.outstanding());
        }

        for (Map.Entry<RiskId, Long> entry : retirementRevision.entrySet()) {
            RiskModel model = risks.get(entry.getKey());
            if (model == null || model.gone) continue;
            assertTrue(model.evidenceRevision >= 0L,
                "seed " + seed + ": retirement without evidence");
            assertTrue(entry.getValue() > model.evidenceRevision,
                "seed " + seed + ": retirement revision must be causally later");
        }
        for (RiskModel model : risks.values()) {
            assertFalse(model.bound > RiskTestSupport.SAFETY.maxSelf());
        }
    }

    private void reserve(Random random, CombatRiskBudget budget, Map<RiskId, RiskModel> risks) {
        double selfBound = random.nextInt(7);
        Optional<RiskId> reserved = budget.reserve(random.nextInt(100_000),
            RiskTestSupport.exposure(selfBound), RiskTestSupport.arrival(),
            RiskTestSupport.healthySelf(), RiskTestSupport.CRYSTAL,
            RiskTestSupport.CRYSTAL_POSITION);
        reserved.ifPresent(id -> risks.put(id, new RiskModel(selfBound)));
    }

    private void dispatch(Random random, CombatRiskBudget budget, Map<RiskId, RiskModel> risks) {
        List<RiskId> live = live(risks);
        if (live.isEmpty()) return;
        RiskId chosen = live.get(random.nextInt(live.size()));
        CombatRiskBudget.State state = budget.state(chosen);
        if (state == null || state == CombatRiskBudget.State.RETIRED) return;
        budget.dispatching(chosen);
        if (random.nextBoolean()) {
            budget.mayHaveLeft(chosen);
            risks.get(chosen).sent = true;
        }
    }

    private void explode(CombatRiskBudget budget, Map<RiskId, RiskModel> risks, long revision) {
        List<RiskId> sentBefore = new ArrayList<>();
        for (Map.Entry<RiskId, RiskModel> entry : risks.entrySet()) {
            if (entry.getValue().sent
                && budget.state(entry.getKey()) == CombatRiskBudget.State.SENT) {
                sentBefore.add(entry.getKey());
            }
        }
        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, revision,
            revision + 1));
        for (RiskId id : sentBefore) {
            RiskModel model = risks.get(id);
            model.evidenceRevision = Math.max(model.evidenceRevision, revision);
        }
    }

    private void cancel(Random random, CombatRiskBudget budget, Map<RiskId, RiskModel> risks) {
        List<RiskId> live = live(risks);
        if (live.isEmpty()) return;
        RiskId chosen = live.get(random.nextInt(live.size()));
        RiskModel model = risks.get(chosen);
        CombatRiskBudget.State state = budget.state(chosen);
        if (state == null || state == CombatRiskBudget.State.RETIRED) return;
        budget.notTransmitted(chosen);
        if (model.sent) {
            // Irreversible: only retirement may release a sent risk.
            assertEquals(CombatRiskBudget.State.SENT, budget.state(chosen));
        } else {
            model.gone = true;
        }
    }

    private void verifyRetirements(CombatRiskBudget budget, Map<RiskId, RiskModel> risks,
                                   Map<RiskId, Long> retirementRevision, long snapshotRevision) {
        for (Map.Entry<RiskId, RiskModel> entry : risks.entrySet()) {
            RiskId id = entry.getKey();
            RiskModel model = entry.getValue();
            if (model.gone || retirementRevision.containsKey(id)) continue;
            if (budget.state(id) != CombatRiskBudget.State.RETIRED) continue;
            // Rule 1 only: a positive bound, a recorded associated explosion, and a causally later
            // coherent self-health snapshot.
            assertTrue(model.bound > 0.0, "zero-bound risk retired");
            assertTrue(model.sent, "unsent risk retired");
            assertTrue(model.evidenceRevision >= 0L, "retired without an associated explosion");
            assertTrue(snapshotRevision > model.evidenceRevision,
                "retirement snapshot must be causally later than the explosion");
            retirementRevision.put(id, snapshotRevision);
        }
    }

    private static List<RiskId> live(Map<RiskId, RiskModel> risks) {
        List<RiskId> live = new ArrayList<>();
        for (Map.Entry<RiskId, RiskModel> entry : risks.entrySet()) {
            if (!entry.getValue().gone) live.add(entry.getKey());
        }
        return live;
    }

    private static int outstandingCount(CombatRiskBudget budget, Map<RiskId, RiskModel> risks) {
        int count = 0;
        for (RiskId id : risks.keySet()) {
            CombatRiskBudget.State state = budget.state(id);
            if (state != null && state != CombatRiskBudget.State.RETIRED) count++;
        }
        return count;
    }

    private static final class RiskModel {
        final double bound;
        long evidenceRevision = -1L;
        boolean sent;
        boolean gone;

        RiskModel(double bound) {
            this.bound = bound;
        }
    }
}
