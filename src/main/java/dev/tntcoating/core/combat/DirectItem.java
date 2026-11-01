/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * A hotbar item relevant to breaking a crystal under Weakness. {@code breaksUnderWeakness} is the pinned
 * attack-damage fact for the current target, computed by the Minecraft-facing reader.
 */
public record DirectItem(int slot, String id, boolean breaksUnderWeakness) {
    public DirectItem {
        if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot");
        if (id == null) throw new IllegalArgumentException("id");
    }
}
