/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.DirectWorld;

/** Supplies one read-only client-thread world snapshot to the direct planner. */
public interface DirectWorldSource {
    DirectWorld read();
}
