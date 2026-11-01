/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Generation of in-flight actions. Death, respawn, a config change and disable end it. It is deliberately
 * separate from {@link ConnectionKey}: crystals and structures placed before death can survive in the same
 * world and their hazards are kept and rescanned.
 */
public record ActionGeneration(long value) {
}
