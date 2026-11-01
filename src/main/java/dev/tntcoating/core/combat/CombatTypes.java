/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * The plan's "CombatTypes" contract set. Java allows one public top-level type per file, so each contract
 * is a sibling file in this package with exactly the plan's name: {@link ConnectionKey},
 * {@link ActionGeneration}, {@link EntityKey}, {@link Cell}, {@link Vec}, {@link Box}, {@link Ticket},
 * {@link ServerTickInterval}, {@link Mode}, {@link Strategy}, {@link Action}, {@link ObservationKind},
 * {@link Capability}, {@link Ownership}, {@link Observation}, {@link RawDamage}, {@link MitigatedDamage},
 * {@link EffectiveHealth}, {@link TargetEstimate}, {@link ExposureBound}, {@link Safety}, {@link Intent},
 * {@link Opportunity} and {@link Footprint}. This holder exists only as the package's index.
 */
public final class CombatTypes {
    private CombatTypes() {}
}
