/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Planner family. There is deliberately no speculative strategy: an attack always names a verified
 * entity identity.
 */
public enum Strategy {
    DIRECT,
    PISTON,
    DEFEND,
    CLEANUP
}
