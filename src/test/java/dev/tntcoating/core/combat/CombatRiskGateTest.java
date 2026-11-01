/*
 * Original Comet implementation by Boyonabike.
 * Independent: test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static dev.tntcoating.core.combat.RiskTestSupport.FRIEND;
import static dev.tntcoating.core.combat.RiskTestSupport.SELF;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Phase 6.1 gap closure: the Hybrid hard filter must price the same ceilings the reservation itself
 * prices. A self-only margin check silently admitted friend damage: every friend keeps its own cumulative
 * {@link Safety#maxFriend()} ceiling and, when its health is known, its own survival check. Unknown or
 * censored friend health stays ceiling-only - never a fabricated lower bound.
 */
final class CombatRiskGateTest {
    private static final Safety SAFETY = RiskTestSupport.SAFETY;
    private static final double SELF_HEALTH = 20.0;

    @Test
    void admitsWhenTheWholeEnvelopeFits() {
        CombatRiskBudget budget = budget();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(SELF_HEALTH, 0.0, 1L));

        assertTrue(CombatRiskGate.allows(budget, SELF, state, RiskTestSupport.exposure(1.0)));
    }

    @Test
    void rejectsAPlanWhoseSelfUpperExceedsTheSelfCeiling() {
        CombatRiskBudget budget = budget();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(SELF_HEALTH, 0.0, 1L));

        assertFalse(CombatRiskGate.allows(budget, SELF, state,
            RiskTestSupport.exposure(SAFETY.maxSelf() + 1.0)),
            "maxSelf is a reservation ceiling, not a remaining-health margin");
    }

    @Test
    void rejectsAFriendCumulativeOverTheFriendCeiling() {
        CombatRiskBudget budget = budget();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(SELF_HEALTH, 0.0, 1L),
            Map.of(FRIEND, RiskTestSupport.known(20.0, 0.0, 1L)));

        assertFalse(CombatRiskGate.allows(budget, SELF, state,
            RiskTestSupport.exposure(1.0, Map.of(FRIEND, SAFETY.maxFriend() + 1.0))),
            "a healthy friend still must not exceed its damage ceiling");
    }

    @Test
    void rejectsAFriendWhoseKnownHealthDoesNotSurviveTheBound() {
        CombatRiskBudget budget = budget();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(SELF_HEALTH, 0.0, 1L),
            Map.of(FRIEND, RiskTestSupport.known(1.0, 0.0, 1L)));

        assertFalse(CombatRiskGate.allows(budget, SELF, state,
            RiskTestSupport.exposure(1.0, Map.of(FRIEND, 2.0))),
            "one heart of friend health cannot survive a two-damage bound");
    }

    @Test
    void admitsAFriendWhoseKnownHealthSurvivesAndCeilingHolds() {
        CombatRiskBudget budget = budget();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(SELF_HEALTH, 0.0, 1L),
            Map.of(FRIEND, RiskTestSupport.known(10.0, 0.0, 1L)));

        assertTrue(CombatRiskGate.allows(budget, SELF, state,
            RiskTestSupport.exposure(1.0, Map.of(FRIEND, 2.0))));
    }

    @Test
    void anUnknownFriendHealthIsCeilingOnly() {
        CombatRiskBudget budget = budget();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(SELF_HEALTH, 0.0, 1L),
            Map.of(FRIEND, RiskTestSupport.unknown()));

        assertTrue(CombatRiskGate.allows(budget, SELF, state,
            RiskTestSupport.exposure(1.0, Map.of(FRIEND, SAFETY.maxFriend()))));
        assertFalse(CombatRiskGate.allows(budget, SELF, state,
            RiskTestSupport.exposure(1.0, Map.of(FRIEND, SAFETY.maxFriend() + 1.0))));
    }

    @Test
    void aCensoredFriendHealthIsAlsoCeilingOnly() {
        CombatRiskBudget budget = budget();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(SELF_HEALTH, 0.0, 1L),
            Map.of(FRIEND, RiskTestSupport.censored()));

        assertTrue(CombatRiskGate.allows(budget, SELF, state,
            RiskTestSupport.exposure(1.0, Map.of(FRIEND, 1.0))));
    }

    @Test
    void anUnknownSelfHealthCannotBePriced() {
        CombatRiskBudget budget = budget();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.unknown());

        assertFalse(CombatRiskGate.allows(budget, SELF, state, RiskTestSupport.exposure(1.0)));
    }

    @Test
    void aFriendEntryThatNamesTheSelfPlayerIsMalformed() {
        CombatRiskBudget budget = budget();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(SELF_HEALTH, 0.0, 1L));

        assertFalse(CombatRiskGate.allows(budget, SELF, state,
            RiskTestSupport.exposure(1.0, Map.of(SELF, 1.0))));
    }

    @Test
    void existingDebtIsCountedInBothCumulatives() {
        CombatRiskBudget budget = budget();
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(SELF_HEALTH, 0.0, 1L),
            Map.of(FRIEND, RiskTestSupport.known(4.0, 0.0, 1L)));
        assertTrue(budget.reserve(1L, RiskTestSupport.exposure(4.0, Map.of(FRIEND, 2.0)),
            new ServerTickInterval(0, 3), state).isPresent());

        assertFalse(CombatRiskGate.allows(budget, SELF, state,
            RiskTestSupport.exposure(SAFETY.maxSelf() - 1.0, Map.of(FRIEND, 1.0))),
            "maxSelf counts the existing reservation as well as the new upper bound");

        CombatRiskBudget friendDebt = budget();
        SafetyState friendState = RiskTestSupport.state(
            RiskTestSupport.known(SELF_HEALTH, 0.0, 1L),
            Map.of(FRIEND, RiskTestSupport.known(4.0, 0.0, 1L)));
        assertTrue(friendDebt.reserve(2L, RiskTestSupport.exposure(1.0, Map.of(FRIEND, 1.5)),
            new ServerTickInterval(0, 3), friendState).isPresent());
        assertFalse(CombatRiskGate.allows(friendDebt, SELF, friendState,
            RiskTestSupport.exposure(1.0, Map.of(FRIEND, 0.5 + SAFETY.maxFriend() - 1.5))));
    }

    @Test
    void aSuspendedBudgetAdmitsNothing() {
        CombatRiskBudget budget = budget();
        budget.suspend(SuspensionCause.WIDENING_UNAVAILABLE);
        SafetyState state = RiskTestSupport.state(RiskTestSupport.known(SELF_HEALTH, 0.0, 1L));

        assertFalse(CombatRiskGate.allows(budget, SELF, state, RiskTestSupport.exposure(0.0)));
    }

    @Test
    void combineAddsSelfAndEachFriendBound() {
        ExposureBound combined = CombatRiskGate.combine(
            RiskTestSupport.exposure(1.0, Map.of(FRIEND, 2.0)),
            RiskTestSupport.exposure(3.0, Map.of(FRIEND, 1.5)));

        assertTrue(Math.abs(combined.selfUpper().beforeAbsorption() - 4.0) < 1e-9);
        assertTrue(Math.abs(combined.friendUpper().get(FRIEND).beforeAbsorption() - 3.5) < 1e-9);
    }

    private static CombatRiskBudget budget() {
        return new CombatRiskBudget((envelope, current) -> current);
    }
}
