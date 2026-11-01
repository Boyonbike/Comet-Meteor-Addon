/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 4.5 pure surround-gap detector. It answers one geometric question: is this target standing in a
 * one-block-wide hole whose head/feet cells are open, three horizontal columns fully solid, and exactly one
 * horizontal column open? The open column's air cells are the gap cells a crystal could occupy.
 *
 * <p>Detection is deliberately geometric. It does not decide whether a placement is legal: hazards,
 * crystals, entities, reach, visibility and every damage bound stay in {@link DirectPlanner#placePlan}.
 * It never mines a surround block and never proposes a support block.</p>
 */
public record SurroundGap(Cell feet, Cell head, Cell gapFeet, List<Cell> gapCells,
                          boolean feetGap, boolean headGap) {
    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public SurroundGap {
        if (feet == null) throw new IllegalArgumentException("feet");
        if (head == null || !head.equals(feet.above())) throw new IllegalArgumentException("head");
        if (gapFeet == null) throw new IllegalArgumentException("gapFeet");
        gapCells = List.copyOf(gapCells);
    }

    /**
     * Detects a 1x1 hole around {@code target}. Returns empty for open ground (fewer than three solid
     * columns), a full surround (four solid columns), or a target whose own feet/head cell is not open.
     */
    public static Optional<SurroundGap> detect(DirectWorld world, DirectTarget target) {
        if (world == null || target == null) return Optional.empty();
        DirectBlocks blocks = world.blocks();
        if (blocks == null) return Optional.empty();

        Cell feet = DirectPlanner.cellOf(target.position());
        Cell head = feet.above();
        if (!open(blocks, feet) || !open(blocks, head)) return Optional.empty();

        int solid = 0;
        Cell gapFeet = null;
        for (int[] offset : HORIZONTAL) {
            Cell sideFeet = new Cell(feet.x() + offset[0], feet.y(), feet.z() + offset[1]);
            Cell sideHead = sideFeet.above();
            if (solid(blocks, sideFeet) && solid(blocks, sideHead)) {
                solid++;
            } else if (gapFeet != null) {
                // Two open columns is open ground or a corner, not a one-column hole.
                return Optional.empty();
            } else {
                gapFeet = sideFeet;
            }
        }
        if (solid != 3) return Optional.empty();

        Cell gapHead = gapFeet.above();
        List<Cell> gapCells = new ArrayList<>(2);
        if (blocks.isAir(gapFeet)) gapCells.add(gapFeet);
        if (blocks.isAir(gapHead)) gapCells.add(gapHead);
        return Optional.of(new SurroundGap(feet, head, gapFeet, gapCells,
            gapCells.contains(gapFeet), gapCells.contains(gapHead)));
    }

    /** True when the target's own cell is empty and not already claimed by a hazard or crystal. */
    private static boolean open(DirectBlocks blocks, Cell cell) {
        return blocks.isAir(cell) && !blocks.blocked(cell) && !blocks.crystalPresent(cell);
    }

    /** True only for a full block column: neither air nor a replaceable block such as grass. */
    private static boolean solid(DirectBlocks blocks, Cell cell) {
        return !blocks.isAir(cell) && !blocks.isReplaceable(cell);
    }
}
