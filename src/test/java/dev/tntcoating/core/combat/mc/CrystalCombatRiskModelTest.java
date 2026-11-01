/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tntcoating.core.combat.RiskModel;
import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.Test;

/** The selected risk model is the budget's model, defaulting to the plan default. */
final class CrystalCombatRiskModelTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);

    @Test
    void withoutAStackTheFieldIsReported() {
        CrystalCombatController controller = new CrystalCombatController(() -> null, () -> {
            throw new AssertionError("not enabled");
        });
        assertEquals(RiskModel.defaultModel(), controller.riskModel());
    }

    @Test
    void theStackBudgetOwnsTheModelOnceEnabled() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.enable();
        assertSame(harness.budget.riskModel(), controller.riskModel());
        controller.setRiskModel(RiskModel.STRICT);
        assertSame(harness.budget.riskModel(), controller.riskModel());
        assertEquals(RiskModel.STRICT, harness.budget.riskModel());
        assertEquals(0, harness.sink.attempts);
    }

    @Test
    void eachModelHasAStableKey() {
        assertEquals("strict", RiskModel.STRICT.key());
        assertEquals("barrier", RiskModel.BARRIER.key());
        assertEquals("meteor-style", RiskModel.METEOR_STYLE.key());
    }
}
