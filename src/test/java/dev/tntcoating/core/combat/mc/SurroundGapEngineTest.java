/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.Test;

/** Surround-gap preference is a setting; the planner still sends exactly one placement. */
final class SurroundGapEngineTest {
    private static final Vec SELF = new Vec(1.5, -60.0, 0.5);
    private static final Vec TARGET = new Vec(4.5, -60.0, 0.5);

    private static EngineHarness harness(boolean surroundGap) {
        DirectSettings base = EngineHarness.create(SELF).settings;
        EngineHarness harness = EngineHarness.create(base.withSurroundGap(surroundGap), SELF);
        harness.setBase(EngineHarness.cellOf(new Vec(4.0, -61.0, 1.0)));
        harness.addTarget(TARGET);
        harness.setWall(new Cell(4, -60, 0));
        return harness;
    }

    @Test
    void surroundGapIsOffByDefault() {
        EngineHarness harness = EngineHarness.create(SELF);
        assertEquals(false, harness.engine.settings().surroundGap());
    }

    @Test
    void theGapPreferenceStillSendsASinglePlacement() {
        EngineHarness harness = harness(true);
        harness.tick();
        assertEquals(1, harness.placeCount());
    }

    @Test
    void disabledPreferenceKeepsSendingTheOrdinaryPlacement() {
        EngineHarness harness = harness(false);
        harness.tick();
        assertEquals(1, harness.placeCount());
        assertTrue(harness.sink.sends.stream().anyMatch(send ->
            send.kind() == CombatTestFakes.FakeSink.Kind.PLACE));
    }

    @Test
    void theHarnessWorldDoesNotProduceSupportPacketsForAnExistingBase() {
        EngineHarness harness = harness(true);
        harness.tick();
        assertTrue(harness.sink.sends.stream().noneMatch(send ->
            send.kind() == CombatTestFakes.FakeSink.Kind.SUPPORT));
    }
}
