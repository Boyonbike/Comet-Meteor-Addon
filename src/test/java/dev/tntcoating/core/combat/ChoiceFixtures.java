/*
 * Original Comet test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared builders for the Phase 6 choice, hysteresis, cleanup and interleaving tests. */
final class ChoiceFixtures {
    static final ConnectionKey CONN = new ConnectionKey(1L);
    static final long PLAN = 9L;
    static final Strategy DIRECT = Strategy.DIRECT;

    private ChoiceFixtures() {
    }

    static EntityKey entity(int id) {
        return new EntityKey(CONN, id, new UUID(0L, id));
    }

    static ExposureBound exposure(double selfUpper) {
        return new ExposureBound(new MitigatedDamage(selfUpper), Map.of(), new MitigationEnvelope(1L));
    }

    static Footprint footprint(int x) {
        Cell cell = new Cell(x, 64, 0);
        return new Footprint(Set.of(cell), Box.fromCell(cell), Box.fromCell(cell), Set.of());
    }

    static Intent intent(long id, Strategy strategy, Action action, int x) {
        return new Intent(id, PLAN, strategy, action, new Cell(x, 64, 0), null,
            Set.of(), Set.of(), Set.of(), 1_000);
    }

    static Opportunity opportunity(long id, Strategy strategy, int eta, double damage20,
                                   double confidence, double guaranteedLower, double selfUpper,
                                   int actionCost) {
        Action action = switch (strategy) {
            case DIRECT -> Action.PLACE_CRYSTAL;
            case PISTON -> Action.PLACE_PISTON;
            case DEFEND -> Action.ATTACK_CRYSTAL;
            case CLEANUP -> Action.MINE_POWER;
        };
        return new Opportunity(id, strategy, entity(1), new Vec(0.5, 65.0, 0.5),
            new TargetEstimate(guaranteedLower, damage20, damage20), exposure(selfUpper),
            eta, damage20, actionCost,
            List.of(intent(id, strategy, action, (int) id)), confidence, footprint((int) id));
    }

    static Opportunity opportunity(long id, Strategy strategy, int eta, double damage20,
                                   double confidence) {
        return opportunity(id, strategy, eta, damage20, confidence, 1.0, 0.0, 1);
    }

    static CombatChoice.Gate gate(double healthUpper) {
        return gate(true, true, true, true, healthUpper);
    }

    static CombatChoice.Gate gate(boolean policy, boolean risk, boolean ledger, boolean committed,
                                  double healthUpper) {
        return new CombatChoice.Gate() {
            @Override
            public boolean policyAllows(Opportunity opportunity) {
                return policy;
            }

            @Override
            public boolean riskAllows(Opportunity opportunity) {
                return risk;
            }

            @Override
            public boolean ledgerAllows(Opportunity opportunity) {
                return ledger;
            }

            @Override
            public boolean committedPlanAllows(Opportunity opportunity) {
                return committed;
            }

            @Override
            public double targetHealthUpper(EntityKey target) {
                return healthUpper;
            }
        };
    }

    static List<CombatChoice.Ranked> ranked(Opportunity... opportunities) {
        return CombatChoice.ranked(List.of(opportunities), gate(Double.POSITIVE_INFINITY));
    }
}
