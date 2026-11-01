/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/** An integer block position. */
public record Cell(int x, int y, int z) {
    /** Centre of the block; the point used for box containment. */
    public Vec center() {
        return new Vec(x + 0.5, y + 0.5, z + 0.5);
    }

    public Cell above() {
        return new Cell(x, y + 1, z);
    }

    public Cell below() {
        return new Cell(x, y - 1, z);
    }
}
