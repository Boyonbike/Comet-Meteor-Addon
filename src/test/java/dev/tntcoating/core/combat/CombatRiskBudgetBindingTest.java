/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 integration: a placement risk is reserved before the crystal exists, so the budget needs one
 * controlled way to bind the spawn later. Binding must never shorten the risk's life by itself.
 */
final class CombatRiskBudgetBindingTest {
    @Test
    void bindsAnUnboundPlacementRiskAndThenRuleOneCanRetireIt() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveNoCrystal(budget, 1L);
        budget.dispatching(id);
        budget.mayHaveLeft(id);

        assertTrue(budget.boundCrystal(id).isEmpty());
        assertTrue(budget.boundPosition(id).isEmpty());
        assertTrue(budget.bindCrystal(id, RiskTestSupport.CRYSTAL, RiskTestSupport.CRYSTAL_POSITION));
        assertEquals(Optional.of(RiskTestSupport.CRYSTAL), budget.boundCrystal(id));
        assertEquals(Optional.of(RiskTestSupport.CRYSTAL_POSITION), budget.boundPosition(id));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id), "binding is not a transition");

        budget.observe(RiskTestSupport.explosion(RiskTestSupport.CRYSTAL_POSITION, 3L, 1L));
        assertEquals(CombatRiskBudget.State.SENT, budget.state(id), "an explosion alone never retires");
        assertEquals(1, budget.debt(RiskTestSupport.SELF).count());

        budget.observe(RiskTestSupport.health(RiskTestSupport.SELF, 20.0, 0.0, 4L, 2L));
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(id));
        assertEquals(0, budget.debt(RiskTestSupport.SELF).count());
    }

    @Test
    void bindingTwiceIsRefusedWithoutMovingTheEvidence() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveNoCrystal(budget, 1L);
        assertTrue(budget.bindCrystal(id, RiskTestSupport.CRYSTAL, RiskTestSupport.CRYSTAL_POSITION));
        assertFalse(budget.bindCrystal(id, RiskTestSupport.CRYSTAL_TWO,
            RiskTestSupport.CRYSTAL_TWO_POSITION));
        assertEquals(Optional.of(RiskTestSupport.CRYSTAL), budget.boundCrystal(id));
        assertEquals(Optional.of(RiskTestSupport.CRYSTAL_POSITION), budget.boundPosition(id));
    }

    @Test
    void refusesACrystalFromAnotherConnection() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveNoCrystal(budget, 1L);
        EntityKey foreign = new EntityKey(RiskTestSupport.CONN_TWO, 7, null);
        assertFalse(budget.bindCrystal(id, foreign, RiskTestSupport.CRYSTAL_POSITION));
        assertTrue(budget.boundCrystal(id).isEmpty());
    }

    @Test
    void refusesNullAndNonFinitePositions() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveNoCrystal(budget, 1L);
        assertFalse(budget.bindCrystal(id, null, RiskTestSupport.CRYSTAL_POSITION));
        assertFalse(budget.bindCrystal(id, RiskTestSupport.CRYSTAL, null));
        assertFalse(budget.bindCrystal(id, RiskTestSupport.CRYSTAL, new Vec(Double.NaN, 0.0, 0.0)));
        assertTrue(budget.boundCrystal(id).isEmpty());
    }

    @Test
    void refusesCrystalBoundRiskAndRetiredRisk() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId boundAtReserve = RiskTestSupport.sent(budget, 1L);
        assertFalse(budget.bindCrystal(boundAtReserve, RiskTestSupport.CRYSTAL_TWO,
            RiskTestSupport.CRYSTAL_TWO_POSITION), "a risk reserved with a crystal is final");

        RiskId retired = RiskTestSupport.reserveNoCrystal(budget, 2L);
        budget.notTransmitted(retired);
        assertEquals(CombatRiskBudget.State.RETIRED, budget.state(retired));
        assertFalse(budget.bindCrystal(retired, RiskTestSupport.CRYSTAL_TWO,
            RiskTestSupport.CRYSTAL_TWO_POSITION));
    }

    @Test
    void bindingBeforeSendDoesNotRetireAndKeepsTheBound() {
        CombatRiskBudget budget = RiskTestSupport.conservative();
        RiskId id = RiskTestSupport.reserveNoCrystal(budget, 1L);
        assertTrue(budget.bindCrystal(id, RiskTestSupport.CRYSTAL, RiskTestSupport.CRYSTAL_POSITION));
        assertEquals(CombatRiskBudget.State.RESERVED, budget.state(id));
        assertFalse(budget.bindCrystal(id, RiskTestSupport.CRYSTAL, RiskTestSupport.CRYSTAL_POSITION),
            "even rebinding the same pair is a second binding");
    }
}
