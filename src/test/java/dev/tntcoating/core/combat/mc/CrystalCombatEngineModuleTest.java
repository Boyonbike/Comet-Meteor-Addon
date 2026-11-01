/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.tntcoating.core.combat.CombatHurtModel;
import dev.tntcoating.core.combat.Mode;
import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.Test;

/** The controller is the only tick source for the direct engine, and PISTON_ONLY parks it. */
final class CrystalCombatEngineModuleTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);
    private static final Vec BASE = new Vec(2.0, -61.0, 0.0);
    private static final Vec TARGET = new Vec(2.5, -60.0, 3.0);

    private static EngineHarness laidOut() {
        EngineHarness harness = EngineHarness.create(SELF);
        harness.setBase(EngineHarness.cellOf(BASE));
        harness.addTarget(TARGET);
        return harness;
    }

    @Test
    void anEnabledControllerTicksTheEngine() {
        EngineHarness harness = laidOut();
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.enable();
        controller.tick(1);
        assertEquals(1, harness.placeCount());
    }

    @Test
    void aDisabledControllerNeverTicksTheEngine() {
        EngineHarness harness = laidOut();
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.tick(1);
        assertEquals(0, harness.placeCount());
    }

    @Test
    void pistonOnlyModeDoesNotRunTheDirectEngine() {
        EngineHarness harness = laidOut();
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.setMode(Mode.PISTON_ONLY);
        controller.enable();
        controller.tick(1);
        assertEquals(0, harness.placeCount());
        assertEquals(Mode.PISTON_ONLY, controller.mode());
    }

    @Test
    void aRepeatedServerTickIsIgnored() {
        EngineHarness harness = laidOut();
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.enable();
        controller.tick(5);
        controller.tick(5);
        assertEquals(1, harness.placeCount());
    }

    @Test
    void disablingStopsFurtherSends() {
        EngineHarness harness = laidOut();
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.enable();
        controller.disable();
        controller.tick(1);
        assertEquals(0, harness.placeCount());
        assertFalse(controller.hasStack() && controller.enabled());
    }
}
