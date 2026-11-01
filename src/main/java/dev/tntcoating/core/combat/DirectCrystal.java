/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * One end crystal visible to the planner. The ledger state decides whether it may be attacked; the age is
 * the client entity's tick count and {@code own} is the ledger's bound-spawn fact.
 */
public record DirectCrystal(
    EntityKey entity,
    Vec position,
    Box box,
    CrystalState state,
    boolean own,
    boolean quarantined,
    int ageTicks
) {
    public DirectCrystal {
        if (entity == null) throw new IllegalArgumentException("entity");
        if (position == null) throw new IllegalArgumentException("position");
        if (box == null) throw new IllegalArgumentException("box");
        if (state == null) throw new IllegalArgumentException("state");
        if (ageTicks < 0) throw new IllegalArgumentException("ageTicks");
    }
}
