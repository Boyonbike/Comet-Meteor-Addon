/*
 * Original Comet implementation by Boyonabike.
 * Independent: test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Phase 6.2 "safety invalidation overrides hysteresis immediately": the 20% / three-tick hold must not
 * keep an unsafe plan eligible, and it must stop applying before the plan reaches its first irreversible
 * action. Only evidence inverts the hold; nothing here retires risk on elapsed time.
 */
final class HybridSafetyInvalidationTest {
    private static final DirectSettings SETTINGS =
        DirectSettings.conservative(CombatProfiles.CONSERVATIVE_SAFETY);

    @Test
    void policyInvalidationAppliesBeforeTheFirstIrreversibleAction() {
        CombatEngine engine = engine();
        Opportunity planned = ChoiceFixtures.opportunity(11L, Strategy.DIRECT, 1, 10.0, 1.0);
        adopt(engine, planned);

        assertFalse(engine.hysteresis().irreversible(),
            "a direct plan that has sent nothing is still reversible");
        assertFalse(engine.safetyInvalidated(ChoiceFixtures.gate(true, true, true, true,
            Double.POSITIVE_INFINITY)), "no evidence means no invalidation");
        assertTrue(engine.safetyInvalidated(ChoiceFixtures.gate(false, true, true, true,
            Double.POSITIVE_INFINITY)), "a policy failure must void the hold immediately");
    }

    @Test
    void ledgerInvalidationAppliesBeforeTheFirstIrreversibleAction() {
        CombatEngine engine = engine();
        Opportunity planned = ChoiceFixtures.opportunity(12L, Strategy.DIRECT, 1, 10.0, 1.0);
        adopt(engine, planned);

        assertTrue(engine.safetyInvalidated(ChoiceFixtures.gate(true, true, false, true,
            Double.POSITIVE_INFINITY)), "a ledger exclusion must void the hold immediately");
    }

    /** An invalidated plan is dropped by the very next decision; it is never held out. */
    @Test
    void anInvalidatedHoldIsReplacedOnTheSameTick() {
        CombatEngine engine = engine();
        Opportunity planned = ChoiceFixtures.opportunity(13L, Strategy.DIRECT, 1, 10.0, 1.0);
        adopt(engine, planned);
        Opportunity safe = ChoiceFixtures.opportunity(14L, Strategy.DIRECT, 1, 12.0, 1.0);

        Hysteresis.Decision decision = engine.hysteresis().advance(2,
            List.of(new CombatChoice.Ranked(safe, 12.0), new CombatChoice.Ranked(planned, 10.0)), true);

        assertTrue(decision.invalidated());
        assertSame(safe, decision.plan().orElseThrow(),
            "safety invalidation adopts the best safe plan without waiting for the margin");
    }

    /** The trap plan is not tested here: its owner invalidates it and keeps its hazard/risk reservations. */
    @Test
    void aTrapPlanIsNeverInvalidatedByThisEngine() {
        CombatEngine engine = engine();
        Opportunity trap = ChoiceFixtures.opportunity(15L, Strategy.PISTON, 4, 12.0, 0.5);
        adopt(engine, trap);

        assertFalse(engine.safetyInvalidated(ChoiceFixtures.gate(false, false, false, false,
            Double.POSITIVE_INFINITY)), "the trap owner owns its own invalidation");
    }

    private static void adopt(CombatEngine engine, Opportunity plan) {
        engine.setMode(Mode.HYBRID);
        Hysteresis.Decision decision = engine.hysteresis().advance(1,
            List.of(new CombatChoice.Ranked(plan, 10.0)), false);
        assertTrue(decision.adopted());
        assertSame(plan, engine.hysteresis().current().orElseThrow());
    }

    private static CombatEngine engine() {
        CombatSession session = new CombatSession();
        session.open();
        CombatRiskBudget risk = new CombatRiskBudget((envelope, current) -> current);
        CombatScheduler scheduler = new CombatScheduler(session, risk, PacketLedger.conservative());
        return new CombatEngine(session, scheduler, risk, new CrystalLedger(), SETTINGS,
            new DirectPlanner(SETTINGS));
    }
}
