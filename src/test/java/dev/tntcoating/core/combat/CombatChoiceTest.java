/*
 * Original Comet test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 6.1: hard filters, lethal first, the utility ranking and the tie-breaks, exactly as planned. */
final class CombatChoiceTest {
    private static final CombatChoice.Gate OPEN = ChoiceFixtures.gate(20.0);

    @Test
    void lethalOpportunityWinsEvenWithLowerUtility() {
        Opportunity lethal = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 8, 20.0, 0.7, 20.0, 0.0, 2);
        Opportunity highUtility = ChoiceFixtures.opportunity(2, Strategy.PISTON, 1, 30.0, 1.0);

        Choice choice = CombatChoice.choose(List.of(lethal, highUtility), null, OPEN);

        assertTrue(choice.winner().isPresent());
        assertEquals(1, choice.winner().orElseThrow().id());
        assertTrue(choice.lethal());
    }

    @Test
    void aWeakEstimateRanksButNeverClaimsLethality() {
        Opportunity weak = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 2, 20.0, 0.5,
            40.0, 0.0, 2);
        Opportunity stronger = ChoiceFixtures.opportunity(2, Strategy.PISTON, 4, 20.0, 0.6);

        Choice choice = CombatChoice.choose(List.of(weak, stronger), null, OPEN);

        assertFalse(choice.lethal());
        assertEquals(1, choice.winner().orElseThrow().id(),
            "the weak line still ranks by confidence x damage / eta");
    }

    @Test
    void expectedDamageCannotClaimLethalityWithoutTheGuaranteedLowerBound() {
        Opportunity optimistic = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 1, 1000.0, 1.0,
            5.0, 0.0, 1);
        Opportunity other = ChoiceFixtures.opportunity(2, Strategy.PISTON, 3, 4.0, 1.0);

        Choice choice = CombatChoice.choose(List.of(optimistic, other), null, OPEN);

        assertFalse(choice.lethal(), "lethality needs guaranteedLower, never expected");
        assertEquals(1, choice.winner().orElseThrow().id(),
            "the huge expected value still ranks first once no line is lethal");
    }

    @Test
    void unknownTargetHealthNeverSupportsALethalClaim() {
        Opportunity maybeLethal = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 2, 20.0, 1.0,
            40.0, 0.0, 1);
        Opportunity other = ChoiceFixtures.opportunity(2, Strategy.PISTON, 1, 30.0, 1.0);

        Choice choice = CombatChoice.choose(List.of(maybeLethal, other), null,
            ChoiceFixtures.gate(Double.POSITIVE_INFINITY));

        assertFalse(choice.lethal(), "unknown health must not be replaced by a guess");
        assertEquals(2, choice.winner().orElseThrow().id());
    }

    @Test
    void rankUsesConfidenceTimesUsefulDamageOverEta() {
        Opportunity slow = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 2, 30.0, 1.0);
        Opportunity fast = ChoiceFixtures.opportunity(2, Strategy.PISTON, 1, 20.0, 0.9);

        Choice choice = CombatChoice.choose(List.of(slow, fast), null,
            ChoiceFixtures.gate(Double.POSITIVE_INFINITY));

        assertEquals(2, choice.winner().orElseThrow().id());
        assertEquals(18.0, choice.utility(), 1.0e-9);
        assertEquals(15.0, CombatChoice.utility(slow, 30.0), 1.0e-9);
        assertEquals(18.0, CombatChoice.utility(fast, 20.0), 1.0e-9);
    }

    @Test
    void higherUtilityDirectFaceplaceWinsOverASurroundedTrapCandidate() {
        Opportunity trap = ChoiceFixtures.opportunity(1, Strategy.PISTON, 4, 12.0, 0.35,
            12.0, 0.0, 4);
        Opportunity direct = ChoiceFixtures.opportunity(2, Strategy.DIRECT, 1, 8.0, 1.0,
            8.0, 0.0, 2);

        Choice choice = CombatChoice.choose(List.of(trap, direct), null, OPEN);

        assertEquals(2, choice.winner().orElseThrow().id());
        assertEquals(8.0, choice.utility(), 1.0e-9);
        assertEquals(Choice.Reason.LOWER_RANK, reasonOf(choice, 1));
    }

    @Test
    void usefulDamageIsCappedAtTheTargetsRemainingHealth() {
        Opportunity huge = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 1, 100.0, 1.0);

        assertEquals(10.0, CombatChoice.usefulDamage20(huge, 10.0), 1.0e-9);
        assertEquals(10.0, CombatChoice.utility(huge, 10.0), 1.0e-9);
        assertEquals(100.0, CombatChoice.usefulDamage20(huge, Double.POSITIVE_INFINITY), 1.0e-9);
    }

    @Test
    void tieBreaksLowerSelfUpperThenFewerActionsThenStableId() {
        Opportunity risky = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 1, 10.0, 1.0, 1.0, 4.0, 1);
        Opportunity safer = ChoiceFixtures.opportunity(2, Strategy.PISTON, 1, 10.0, 1.0, 1.0, 1.0, 1);
        assertEquals(2, CombatChoice.choose(List.of(risky, safer), null,
            ChoiceFixtures.gate(Double.POSITIVE_INFINITY)).winner().orElseThrow().id());

        Opportunity cheap = ChoiceFixtures.opportunity(3, Strategy.DIRECT, 1, 10.0, 1.0, 1.0, 0.0, 1);
        Opportunity expensive = ChoiceFixtures.opportunity(4, Strategy.PISTON, 1, 10.0, 1.0,
            1.0, 0.0, 3);
        assertEquals(3, CombatChoice.choose(List.of(expensive, cheap), null,
            ChoiceFixtures.gate(Double.POSITIVE_INFINITY)).winner().orElseThrow().id());

        Opportunity lowId = ChoiceFixtures.opportunity(5, Strategy.DIRECT, 1, 10.0, 1.0, 1.0, 0.0, 1);
        Opportunity highId = ChoiceFixtures.opportunity(6, Strategy.PISTON, 1, 10.0, 1.0, 1.0, 0.0, 1);
        assertEquals(5, CombatChoice.choose(List.of(highId, lowId), null,
            ChoiceFixtures.gate(Double.POSITIVE_INFINITY)).winner().orElseThrow().id());
    }

    @Test
    void everyHardFilterRejectsWithItsOwnReason() {
        Opportunity policy = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 1, 10.0, 1.0);
        Opportunity risk = ChoiceFixtures.opportunity(2, Strategy.PISTON, 1, 10.0, 1.0);
        Opportunity ledger = ChoiceFixtures.opportunity(3, Strategy.DEFEND, 1, 10.0, 1.0);
        Opportunity committed = ChoiceFixtures.opportunity(4, Strategy.CLEANUP, 1, 10.0, 1.0);

        CombatChoice.Gate gate = new CombatChoice.Gate() {
            @Override
            public boolean policyAllows(Opportunity opportunity) {
                return opportunity.id() != 1;
            }

            @Override
            public boolean riskAllows(Opportunity opportunity) {
                return opportunity.id() != 2;
            }

            @Override
            public boolean ledgerAllows(Opportunity opportunity) {
                return opportunity.id() != 3;
            }

            @Override
            public boolean committedPlanAllows(Opportunity opportunity) {
                return opportunity.id() != 4;
            }

            @Override
            public double targetHealthUpper(EntityKey target) {
                return 20.0;
            }
        };

        Choice choice = CombatChoice.choose(List.of(policy, risk, ledger, committed), null, gate);

        assertTrue(choice.winner().isEmpty());
        assertEquals(Set.of(Choice.Reason.POLICY, Choice.Reason.RISK, Choice.Reason.LEDGER,
            Choice.Reason.COMMITTED), reasons(choice));
    }

    @Test
    void aGateThatAllowsNothingCanNeverProduceAChoice() {
        Opportunity direct = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 1, 10.0, 1.0);
        Opportunity piston = ChoiceFixtures.opportunity(2, Strategy.PISTON, 1, 30.0, 1.0);
        List<Opportunity> all = List.of(direct, piston);

        assertTrue(CombatChoice.choose(all, null,
            ChoiceFixtures.gate(false, true, true, true, 20.0)).winner().isEmpty());
        assertTrue(CombatChoice.choose(all, null,
            ChoiceFixtures.gate(true, false, true, true, 20.0)).winner().isEmpty());
        assertTrue(CombatChoice.choose(all, null,
            ChoiceFixtures.gate(true, true, false, true, 20.0)).winner().isEmpty());
        assertTrue(CombatChoice.choose(all, null,
            ChoiceFixtures.gate(true, true, true, false, 20.0)).winner().isEmpty());
    }

    @Test
    void committedPlanBlocksEvenTheBestOpportunity() {
        Opportunity blocked = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 1, 100.0, 1.0);
        Opportunity allowed = ChoiceFixtures.opportunity(2, Strategy.PISTON, 1, 10.0, 1.0);
        CombatChoice.Gate gate = new CombatChoice.Gate() {
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
                return opportunity.strategy() == Strategy.PISTON;
            }

            @Override
            public double targetHealthUpper(EntityKey target) {
                return 20.0;
            }
        };

        Choice choice = CombatChoice.choose(List.of(blocked, allowed), null, gate);

        assertEquals(2, choice.winner().orElseThrow().id());
        assertEquals(1, choice.rejected().size());
        assertEquals(Choice.Reason.COMMITTED, choice.rejected().get(0).reason());
    }

    @Test
    void missingDataAndZeroConfidenceAreFilteredNotGuessed() {
        Opportunity nanDamage = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 1, Double.NaN, 1.0);
        Opportunity lost = ChoiceFixtures.opportunity(2, Strategy.PISTON, 1, 10.0, 0.0);
        Opportunity negativeEta = new Opportunity(3, Strategy.DIRECT, ChoiceFixtures.entity(1), null,
            new TargetEstimate(1.0, 1.0, 1.0), ChoiceFixtures.exposure(0.0), -2, 10.0, 1,
            List.of(), 1.0, ChoiceFixtures.footprint(3));

        Choice choice = CombatChoice.choose(List.of(nanDamage, lost, negativeEta), null, OPEN);

        assertTrue(choice.winner().isEmpty());
        assertEquals(Choice.Reason.MISSING_DATA, reasonOf(choice, 1));
        assertEquals(Choice.Reason.UNKNOWN_CONFIDENCE, reasonOf(choice, 2));
        assertEquals(Choice.Reason.MISSING_DATA, reasonOf(choice, 3));
    }

    @Test
    void aPlanWithNothingLeftToRemoveIsFiltered() {
        Opportunity exhausted = ChoiceFixtures.opportunity(1, Strategy.DIRECT, 1, 0.0, 1.0, 0.0, 0.0, 1);
        Opportunity useful = ChoiceFixtures.opportunity(2, Strategy.PISTON, 5, 4.0, 0.5);

        Choice choice = CombatChoice.choose(List.of(exhausted, useful), null, OPEN);

        assertEquals(2, choice.winner().orElseThrow().id());
        assertEquals(Choice.Reason.NO_USEFUL_DAMAGE, reasonOf(choice, 1));
    }

    @Test
    void emptyInputsProduceAnEmptyChoice() {
        assertTrue(CombatChoice.choose(null, null, OPEN).winner().isEmpty());
        assertTrue(CombatChoice.choose(List.of(), null, OPEN).winner().isEmpty());
        assertTrue(CombatChoice.choose(List.of(), null, OPEN).rejected().isEmpty());
    }

    private static Set<Choice.Reason> reasons(Choice choice) {
        return choice.rejected().stream().map(Choice.Rejection::reason)
            .collect(java.util.stream.Collectors.toSet());
    }

    private static Choice.Reason reasonOf(Choice choice, long id) {
        return choice.rejected().stream().filter(rejection -> rejection.opportunityId() == id)
            .map(Choice.Rejection::reason).findFirst().orElseThrow();
    }
}
