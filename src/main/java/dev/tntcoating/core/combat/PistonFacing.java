/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Piston direction in pure combat terms: one unit step on exactly one axis. The Minecraft reader maps
 * {@code DirectionalBlock.FACING} onto this record; no Minecraft type crosses into this package.
 */
public record PistonFacing(int dx, int dy, int dz) {
    public PistonFacing {
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || Math.abs(dz) > 1) {
            throw new IllegalArgumentException("piston facing must be a unit step");
        }
        int nonZero = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        if (nonZero != 1) throw new IllegalArgumentException("piston facing must name one axis");
    }

    /** The cell the piston head extends into: the explicit Phase 4.6 push-path front cell. */
    public Cell front(Cell piston) {
        return new Cell(piston.x() + dx, piston.y() + dy, piston.z() + dz);
    }

    public boolean vertical() {
        return dy != 0;
    }

    public boolean horizontal() {
        return dy == 0;
    }
}
