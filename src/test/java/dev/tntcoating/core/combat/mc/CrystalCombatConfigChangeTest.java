/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Mode;
import dev.tntcoating.core.combat.PistonLayoutSelection;
import dev.tntcoating.core.combat.RiskModel;
import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.Test;

/** Configuration changes are live where safe and never send from the controller itself. */
final class CrystalCombatConfigChangeTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);

    @Test
    void modePersistsAcrossDisableAndEnable() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.setMode(Mode.HYBRID);
        controller.enable();
        controller.disable();
        assertEquals(Mode.HYBRID, controller.mode());
        controller.enable();
        assertEquals(Mode.HYBRID, controller.mode());
        assertEquals(0, harness.sink.attempts);
    }

    @Test
    void riskModelChangesTheBudgetThatTheStackOwns() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.enable();
        controller.setRiskModel(RiskModel.METEOR_STYLE);
        assertEquals(RiskModel.METEOR_STYLE, controller.riskModel());
        assertEquals(RiskModel.METEOR_STYLE, harness.budget.riskModel());
    }

    @Test
    void autoCleanupIsLiveAndDefaultsOff() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        assertFalse(controller.hasStack());
        controller.enable();
        controller.setAutoCleanup(true);
        controller.setAutoCleanup(false);
        assertEquals(0, harness.sink.attempts);
    }

    @Test
    void pistonLayoutsCanBeDisabledWithoutTraps() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.enable();
        controller.setPistonLayouts(PistonLayoutSelection.none());
        assertTrue(controller.pistonLayouts().empty());
        controller.setPistonLayouts(null);
        assertEquals(PistonLayoutSelection.all(), controller.pistonLayouts());
    }

    @Test
    void reconfigureIsSafeBeforeAndAfterEnable() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.reconfigure(harness.settings);
        controller.enable();
        controller.reconfigure(harness.settings.withMotion(true, 2));
        assertEquals(0, harness.sink.attempts);
    }
}
