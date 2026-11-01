/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.mixin.CombatLivingEntityDataAccessor;
import dev.tntcoating.mixin.CombatPlayerDataAccessor;

/**
 * The two private metadata ids the health/absorption observation needs. Production resolves them once from
 * the accessor mixins; no numeric literal may appear anywhere else. Tests may construct explicit ids.
 */
public record EntityDataIds(int health, int absorption) {
    public EntityDataIds {
        if (health < 0) throw new IllegalArgumentException("health id must be non-negative");
        if (absorption < 0) throw new IllegalArgumentException("absorption id must be non-negative");
        if (health == absorption) {
            throw new IllegalArgumentException("health and absorption ids must differ");
        }
    }

    /** Resolved through the accessors, never through a hard-coded index. */
    public static EntityDataIds production() {
        return new EntityDataIds(CombatLivingEntityDataAccessor.comet$healthData().id(),
            CombatPlayerDataAccessor.comet$absorptionData().id());
    }
}
