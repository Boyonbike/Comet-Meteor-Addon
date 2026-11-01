/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/** One packet-level step a plan can ask the runtime to perform. */
public enum Action {
    PLACE_BASE,
    PLACE_SUPPORT,
    PLACE_PISTON,
    PLACE_CRYSTAL,
    PLACE_POWER,
    ATTACK_CRYSTAL,
    MINE_POWER,
    MINE_PISTON,
    PLACE_BLOCKER
}
