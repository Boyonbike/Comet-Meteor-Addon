/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Read-only block and occupation facts for placement planning. Implemented by the Minecraft-facing reader;
 * the pure planner applies the vanilla placement rule itself.
 */
public interface DirectBlocks {
    /** Registry id of the block in this cell, or an empty string when it cannot be read. */
    String blockId(Cell cell);

    boolean isAir(Cell cell);

    /** Obsidian or bedrock, the only legal crystal bases. */
    boolean isBaseBlock(Cell cell);

    /** Air (or a replaceable block) that can become a support base. */
    boolean isReplaceable(Cell cell);

    /** True when the cell below is a full-support block, so a support block could be placed here. */
    boolean hasSupportBelow(Cell cell);

    /** True when the ledger still treats the cell as an unresolved hazard or quarantine. */
    boolean blocked(Cell cell);

    /** No non-spectator entity hitbox intersects the box. */
    boolean entityFree(Box box);

    /** Full support cube under a piston/structural cell; conservative false when unknown. */
    default boolean fullSupport(Cell cell) {
        return false;
    }

    /** A live crystal is present in this cell, or the ledger has not observed its removal yet. */
    boolean crystalPresent(Cell cell);

    /** The base's top face is reachable and visible from the eye under interaction rules. */
    boolean faceVisible(Vec eye, Cell base);

    /** Phase 4.6: the cell holds a piston base block. Default keeps older fakes source-compatible. */
    default boolean isPiston(Cell cell) {
        return false;
    }

    /** Phase 4.6: the piston's facing, or {@code null} when the cell is not a readable piston base. */
    default PistonFacing pistonFacing(Cell cell) {
        return null;
    }

    /** Phase 4.6: the piston base currently has a redstone neighbour signal (arming estimate only). */
    default boolean pistonPowered(Cell cell) {
        return false;
    }

    /** Phase 4.6: the piston base state is already extended; an extended head blocks the front cell. */
    default boolean pistonExtended(Cell cell) {
        return false;
    }
}
