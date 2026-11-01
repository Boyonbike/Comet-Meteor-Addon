/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Observation kinds the combat engine consumes. Kept identical to the plan's Core contracts enum so
 * Phase 0 traces replay through the Phase 1 models without translation.
 */
public enum ObservationKind {
    SPAWN,
    MOVE,
    REMOVED,
    TRACKING_REMOVED,
    BLOCK_UPDATE,
    EXPLOSION,
    HEALTH,
    ENTITY_DATA,
    EFFECT,
    EQUIPMENT,
    DAMAGE_EVENT,
    ENTITY_STATUS,
    TOTEM_POP,
    SETBACK,
    DEATH,
    RESPAWN,
    CONNECTION_CHANGE
}
