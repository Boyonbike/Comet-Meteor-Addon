/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Phase 4.5: pure one-column hole detection around a target, with no placement policy in the analyzer. */
class SurroundGapTest {
    private static final ConnectionKey CONN = new ConnectionKey(51L);
    private static final MitigationState NO_ARMOUR =
        new MitigationState(CombatDifficulty.NORMAL, 0.0, 0.0, 0.0, 0, false, false, false);
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Test
    void detectsTheSingleOpenColumnAtFeetAndHeadOnAllFourSides() {
        for (int[] side : SIDES) {
            SurroundGap gap = SurroundGap.detect(hole(side[0], side[1]), target()).orElseThrow();
            assertEquals(new Cell(0, 64, 0), gap.feet());
            assertEquals(new Cell(0, 65, 0), gap.head());
            assertEquals(List.of(new Cell(side[0], 64, side[1]), new Cell(side[0], 65, side[1])),
                gap.gapCells(), "both air cells of the open column are gap cells");
            assertTrue(gap.feetGap());
            assertTrue(gap.headGap());
        }
    }

    @Test
    void detectsAHeadOnlyAndAFeetOnlyGap() {
        DirectPlannerTest.Phase3FakeWorld headOnly = hole(1, 0);
        headOnly.blocks.ids.put(new Cell(1, 64, 0), "minecraft:stone");
        SurroundGap headGap = SurroundGap.detect(headOnly, target()).orElseThrow();
        assertFalse(headGap.feetGap(), "the feet cell of the open column is solid");
        assertTrue(headGap.headGap());
        assertEquals(List.of(new Cell(1, 65, 0)), headGap.gapCells());

        DirectPlannerTest.Phase3FakeWorld feetOnly = hole(1, 0);
        feetOnly.blocks.ids.put(new Cell(1, 65, 0), "minecraft:stone");
        SurroundGap feetGap = SurroundGap.detect(feetOnly, target()).orElseThrow();
        assertTrue(feetGap.feetGap());
        assertFalse(feetGap.headGap(), "the head cell of the open column is solid");
        assertEquals(List.of(new Cell(1, 64, 0)), feetGap.gapCells());
    }

    @Test
    void rejectsOpenGroundAndCorners() {
        DirectPlannerTest.Phase3FakeWorld open = new DirectPlannerTest.Phase3FakeWorld();
        open.targets.add(target());
        assertFalse(SurroundGap.detect(open, target()).isPresent(), "no surround is not a hole");

        DirectPlannerTest.Phase3FakeWorld corner = hole(1, 0);
        corner.blocks.ids.remove(new Cell(0, 64, 1));
        corner.blocks.ids.remove(new Cell(0, 65, 1));
        assertFalse(SurroundGap.detect(corner, target()).isPresent(),
            "two open columns are open ground or a corner, not a one-column hole");
    }

    @Test
    void rejectsAFullSolidSurround() {
        DirectPlannerTest.Phase3FakeWorld full = new DirectPlannerTest.Phase3FakeWorld();
        full.targets.add(target());
        for (int[] side : SIDES) {
            full.blocks.ids.put(new Cell(side[0], 64, side[1]), "minecraft:stone");
            full.blocks.ids.put(new Cell(side[0], 65, side[1]), "minecraft:stone");
        }
        assertFalse(SurroundGap.detect(full, target()).isPresent(),
            "no gap exists when every horizontal column is solid");
    }

    @Test
    void rejectsATargetWhoseOwnCellsAreNotOpen() {
        DirectPlannerTest.Phase3FakeWorld blocked = hole(1, 0);
        blocked.blocks.blocked.add(new Cell(0, 64, 0));
        assertFalse(SurroundGap.detect(blocked, target()).isPresent());

        DirectPlannerTest.Phase3FakeWorld occupied = hole(1, 0);
        occupied.blocks.crystalCells.add(new Cell(0, 65, 0));
        assertFalse(SurroundGap.detect(occupied, target()).isPresent());

        DirectPlannerTest.Phase3FakeWorld solidFeet = hole(1, 0);
        solidFeet.blocks.ids.put(new Cell(0, 64, 0), "minecraft:stone");
        assertFalse(SurroundGap.detect(solidFeet, target()).isPresent());

        DirectPlannerTest.Phase3FakeWorld solidHead = hole(1, 0);
        solidHead.blocks.ids.put(new Cell(0, 65, 0), "minecraft:stone");
        assertFalse(SurroundGap.detect(solidHead, target()).isPresent());
    }

    @Test
    void aReplaceableOpenColumnIsReportedButHasNoAirGapCells() {
        DirectPlannerTest.Phase3FakeWorld grass = hole(1, 0);
        grass.blocks.ids.put(new Cell(1, 64, 0), "minecraft:short_grass");
        grass.blocks.ids.put(new Cell(1, 65, 0), "minecraft:short_grass");

        SurroundGap gap = SurroundGap.detect(grass, target()).orElseThrow();
        assertTrue(gap.gapCells().isEmpty(), "a replaceable block is not a strict-air placement cell");
        assertFalse(gap.feetGap());
        assertFalse(gap.headGap());
    }

    private static DirectPlannerTest.Phase3FakeWorld hole(int gapDx, int gapDz) {
        DirectPlannerTest.Phase3FakeWorld world = new DirectPlannerTest.Phase3FakeWorld();
        world.targets.add(target());
        for (int[] side : SIDES) {
            if (side[0] == gapDx && side[1] == gapDz) continue;
            world.blocks.ids.put(new Cell(side[0], 64, side[1]), "minecraft:stone");
            world.blocks.ids.put(new Cell(side[0], 65, side[1]), "minecraft:stone");
        }
        return world;
    }

    private static DirectTarget target() {
        double x = 0.5;
        double y = 64.0;
        double z = 0.5;
        return new DirectTarget(new EntityKey(CONN, 1, new UUID(0L, 1L)), new Vec(x, y, z),
            new Box(new Vec(x - 0.3, y, z - 0.3), new Vec(x + 0.3, y + 1.8, z + 0.3)),
            new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 1L), MitigationView.of(NO_ARMOUR),
            false, false, false, false, false);
    }
}
