/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.Vec;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Phase 4.5 engine path: the gap preference adds exactly one normal direct placement, never mining/support. */
class SurroundGapEngineTest {
    private static final Vec SELF = new Vec(1.5, -60.0, 0.5);
    private static final Vec TARGET = new Vec(4.5, -60.0, 0.5);
    private static final Vec GAP_CRYSTAL_CENTRE = new Vec(4.5, -60.0, 1.5);
    private static final Cell GAP_BASE = new Cell(4, -61, 1);
    private static final Cell HIGH_BASE = new Cell(2, -61, 3);

    private static EngineHarness harness(boolean surroundGap) {
        DirectSettings settings = DirectSettings.conservative(CombatDamagePolicy.conservativeSafety())
            .withSurroundGap(surroundGap);
        EngineHarness harness = EngineHarness.create(settings, SELF);
        harness.setBase(GAP_BASE);
        harness.setBase(HIGH_BASE);
        for (Cell wall : List.of(new Cell(3, -60, 0), new Cell(3, -59, 0),
            new Cell(5, -60, 0), new Cell(5, -59, 0),
            new Cell(4, -60, -1), new Cell(4, -59, -1))) {
            harness.setWall(wall);
        }
        harness.addTarget(TARGET);
        harness.setEstimateAt(centre -> GAP_CRYSTAL_CENTRE.equals(centre)
            ? new TargetEstimate(4.0, 5.0, 8.0)
            : new TargetEstimate(4.0, 9.0, 12.0));
        return harness;
    }

    @Test
    void gapPreferenceDispatchesExactlyOneNormalPlacementAndNeverMinesOrPlacesSupport() {
        EngineHarness harness = harness(true);
        harness.tick();

        assertEquals(1, harness.placeCount(), "one placement per tick through the normal dispatch path");
        assertEquals(1, harness.engine.placementsSent());
        assertEquals(0, harness.attackCount(), "nothing is attacked before a crystal exists");
        assertEquals(0, harness.sink.count(FakeSink.Kind.SUPPORT), "the gap path never places a support block");
        assertNotNull(harness.sink.last(FakeSink.Kind.PLACE));
        assertEquals(new BlockPos(4, -61, 1), harness.sink.last(FakeSink.Kind.PLACE).hit().getBlockPos(),
            "the placed base is the one under the single open surround column");
        assertFalse(harness.scheduler.miningSlotHeld(), "no mining reservation is taken");
        assertFalse(harness.world.mining, "the gap tactic does not mine");
    }

    @Test
    void offPathStillTakesTheHigherPricedNormalBase() {
        EngineHarness harness = harness(false);
        harness.tick();

        assertEquals(1, harness.placeCount());
        assertEquals(new BlockPos(2, -61, 3), harness.sink.last(FakeSink.Kind.PLACE).hit().getBlockPos(),
            "with the tactic off the normal higher-estimate base wins");
        assertFalse(harness.scheduler.miningSlotHeld());
    }
}
