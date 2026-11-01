/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * The four vanilla difficulties, as far as explosion damage scaling is concerned. Explosion damage uses
 * {@code DamageScaling.ALWAYS}, so the world difficulty multiplies every point of a crystal explosion.
 */
public enum CombatDifficulty {
    PEACEFUL,
    EASY,
    NORMAL,
    HARD
}
