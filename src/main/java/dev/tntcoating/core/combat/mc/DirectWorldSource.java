/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.DirectWorld;

/** Supplies one read-only world view per planning pass; production reads Minecraft, tests feed traces. */
public interface DirectWorldSource {
    /** The current view, or null when there is no local player/level/connection yet. */
    DirectWorld read();
}
