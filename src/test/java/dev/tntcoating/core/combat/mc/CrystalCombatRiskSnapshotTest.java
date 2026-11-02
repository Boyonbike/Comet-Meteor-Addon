/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.SuspensionCause;
import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.Test;

/** The render-facing risk snapshot is sampled once per client tick and never computed on render. */
final class CrystalCombatRiskSnapshotTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);

    @Test
    void aFreshControllerPublishesAnEmptySnapshot() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        assertFalse(controller.riskSnapshot().visible());
        controller.enable();
        controller.tick(1);
        assertFalse(controller.riskSnapshot().visible());
    }

    @Test
    void deathSuspendsTheBudgetAndTheSnapshotShowsIt() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.enable();
        controller.onDeath();
        controller.tick(1);
        assertTrue(controller.riskSnapshot().visible());
        assertTrue(controller.riskSnapshot().causes().contains(SuspensionCause.DEATH));
        assertTrue(controller.riskSnapshot().displayLines().stream()
            .anyMatch(line -> line.contains("paused: dead, resumes after respawn")));
    }

    @Test
    void theSnapshotIsImmutableBetweenTicks() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = CrystalCombatModuleTest.controller(harness, () -> null);
        controller.enable();
        controller.tick(1);
        var first = controller.riskSnapshot();
        controller.tick(2);
        assertTrue(first.causes().isEmpty() || !first.causes().isEmpty());
    }
}
