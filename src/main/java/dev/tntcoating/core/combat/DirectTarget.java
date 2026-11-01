/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * One candidate victim as the planner sees it. {@code health} is an effective-health estimate (health plus
 * absorption) with its capability; {@code mitigation} is the view used to price a crystal hit.
 */
public record DirectTarget(
    EntityKey entity,
    Vec position,
    Box box,
    EffectiveHealth health,
    MitigationView mitigation,
    boolean dead,
    boolean creative,
    boolean spectator,
    boolean friend,
    boolean armourPressure
) {
    public DirectTarget {
        if (entity == null) throw new IllegalArgumentException("entity");
        if (position == null) throw new IllegalArgumentException("position");
        if (box == null) throw new IllegalArgumentException("box");
        if (health == null) throw new IllegalArgumentException("health");
        if (mitigation == null) throw new IllegalArgumentException("mitigation");
    }
}
