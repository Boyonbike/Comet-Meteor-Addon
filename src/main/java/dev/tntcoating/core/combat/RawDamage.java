/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Explosion damage as the hurt path receives it, before armour. The immunity rule subtracts the previous
 * raw hit at this stage, before mitigation. Never mixed with {@link MitigatedDamage}.
 */
public record RawDamage(double value) {
}
