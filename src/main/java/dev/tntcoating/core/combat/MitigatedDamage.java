/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Damage after armour, toughness, protection enchantments and Resistance, before absorption. Absorption is
 * applied only in {@link EffectiveHealth}, so it is never credited twice.
 */
public record MitigatedDamage(double beforeAbsorption) {
}
