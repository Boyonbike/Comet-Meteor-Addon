/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.Test;

/** Defence is opt-in, and a dangerous crystal is dealt with before offence is allowed to proceed. */
final class DefenseEngineTest {
    private static final Vec SELF = new Vec(0.5, -60.0, 0.5);
    private static final Vec CRYSTAL = new Vec(1.5, -60.0, 0.5);

    @Test
    void dangerousCrystalBreakIsOffByDefault() {
        EngineHarness harness = EngineHarness.create(SELF);
        assertEquals(false, harness.engine.settings().dangerousCrystalBreak());
        assertEquals(false, harness.engine.settings().antiPiston());
    }

    @Test
    void anImminentDangerousCrystalIsChosenOverOffence() {
        EngineHarness harness = EngineHarness.create(
            EngineHarness.create(SELF).settings.withDangerousCrystalBreak(true), SELF);
        harness.spawnCrystal(CRYSTAL);
        harness.world.selfUpperWorst = new dev.tntcoating.core.combat.MitigatedDamage(20.0);
        var state = new dev.tntcoating.core.combat.DirectPlanState(
            dev.tntcoating.core.combat.CombatEngine.DIRECT_PLAN, 1L, 0, null,
            java.util.Map.of(), -1);
        var choice = new dev.tntcoating.core.combat.DefensePlanner(harness.engine.settings())
            .choose(harness.world, state, 1.0);
        assertTrue(choice.isPresent(), "expected a defensive choice for the enemy crystal");
        assertEquals(dev.tntcoating.core.combat.Action.ATTACK_CRYSTAL,
            choice.get().plan().action());
    }

    @Test
    void antiPistonIsASeparateOptInSwitch() {
        EngineHarness harness = EngineHarness.create(
            EngineHarness.create(SELF).settings.withAntiPiston(true), SELF);
        assertTrue(harness.engine.settings().antiPiston());
        assertEquals(false, harness.engine.settings().dangerousCrystalBreak());
    }

    @Test
    void aCrystalFarAwayDoesNotTriggerADefensiveBreak() {
        EngineHarness harness = EngineHarness.create(
            EngineHarness.create(SELF).settings.withDangerousCrystalBreak(true), SELF);
        harness.spawnCrystal(new Vec(20.5, -60.0, 0.5));
        harness.tick();
        assertTrue(harness.sink.sends.stream().noneMatch(send ->
            send.kind() == CombatTestFakes.FakeSink.Kind.BREAK));
    }
}
