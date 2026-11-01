/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.mixin.CombatLivingEntityDataAccessor;
import dev.tntcoating.mixin.CombatPlayerDataAccessor;

/**
 * The two synched entity-data ids combat needs: health (LivingEntity) and absorption (Player). Both are
 * private static accessors in 26.2 (audit C19), read through mixin accessors in production. Tests inject
 * synthetic ids because mixins are not applied in plain JUnit.
 */
public record EntityDataIds(int health, int absorption) {
    public EntityDataIds {
        if (health < 0 || absorption < 0) {
            throw new IllegalArgumentException("entity data ids must be non-negative");
        }
        if (health == absorption) {
            throw new IllegalArgumentException("health and absorption data ids must differ");
        }
    }

    /** Production ids, resolved once from the pinned 26.2 entity-data accessors. */
    public static EntityDataIds production() {
        return new EntityDataIds(
            CombatLivingEntityDataAccessor.comet$healthData().id(),
            CombatPlayerDataAccessor.comet$absorptionData().id());
    }
}
