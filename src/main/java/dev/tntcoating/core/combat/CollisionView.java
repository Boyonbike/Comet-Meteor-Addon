/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * The solidity the motion model sees. Implemented by the adapter from the real world, and by tests from
 * hand-written cells; the model never touches a {@code Level}.
 */
public interface CollisionView {
    /** True when the block at this cell has a non-empty collision shape. */
    boolean blocks(Cell cell);

    /** An empty world, useful for free-fall and open-air predictions. */
    static CollisionView open() {
        return cell -> false;
    }
}
