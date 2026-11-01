/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatHurtModel;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.CrystalState;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Vec;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Module lifecycle: enable gating, tick admission, observation routing and disable semantics. */
final class CrystalCombatModuleTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);

    static CrystalCombatController controller(EngineHarness harness,
                                              CrystalCombatController.Readiness readiness) {
        DirectEngineHost host = new DirectEngineHost(harness.runtime, () -> harness.world,
            harness.engine, harness.gateway);
        CrystalCombatController.Stack stack = new CrystalCombatController.Stack(harness.runtime,
            harness.session, harness.crystals, harness.scheduler, harness.budget, harness.client,
            harness.intake, host, new CombatHurtModel());
        return new CrystalCombatController(readiness, () -> stack);
    }

    @Test
    void readinessRefusalPreventsEnableAndExplainsItself() {
        CrystalCombatController controller = new CrystalCombatController(() -> "no client", () -> {
            throw new AssertionError("stack must not be built");
        });
        assertFalse(controller.enable());
        assertEquals("no client", controller.infoString());
        assertEquals("no client", controller.refusalReason());
        assertFalse(controller.enabled());
    }

    @Test
    void enableThenDisableTracksTheModuleState() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = controller(harness, () -> null);
        assertTrue(controller.enable());
        assertTrue(controller.enabled());
        assertEquals("enabled", controller.infoString());
        controller.disable();
        assertFalse(controller.enabled());
        assertEquals("disabled", controller.infoString());
        assertTrue(controller.hasStack());
    }

    @Test
    void enableIsIdempotent() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = controller(harness, () -> null);
        assertTrue(controller.enable());
        assertTrue(controller.enable());
        assertEquals(0, harness.sink.attempts);
    }

    @Test
    void tickingAnEnabledControllerIsAdmitted() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = controller(harness, () -> null);
        controller.enable();
        controller.tick(3);
        assertTrue(controller.sessionOpen());
        assertEquals(0, harness.sink.attempts);
    }

    @Test
    void spawnObservationIsRecordedByTheLedger() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = controller(harness, () -> null);
        controller.enable();
        EntityKey crystal = new EntityKey(new ConnectionKey(1), 900, UUID.randomUUID());
        controller.observeSpawn(crystal, new Vec(1.5, -60.0, 0.5), "minecraft:end_crystal");
        assertEquals(CrystalState.OBSERVED, harness.crystals.state(crystal));
        assertEquals(0, harness.sink.attempts);
    }

    @Test
    void blockUpdatesAreForwardedWithoutSending() {
        EngineHarness harness = EngineHarness.create(SELF);
        CrystalCombatController controller = controller(harness, () -> null);
        controller.enable();
        controller.observeBlockUpdate(new Cell(2, -61, 0), "minecraft:obsidian",
            "minecraft:air");
        assertEquals(0, harness.sink.attempts);
    }
}
