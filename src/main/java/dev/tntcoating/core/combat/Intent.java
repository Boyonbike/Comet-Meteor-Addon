/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;
import java.util.Set;

/**
 * One schedulable step. Locks are cell/entity sets the scheduler must acquire atomically; dependencies are
 * earlier intents that must have completed. Collections are defensively copied.
 */
public record Intent(long id, long plan, Strategy strategy, Action action, Cell cell, EntityKey entity,
                     Set<Cell> blockLocks, Set<EntityKey> entityLocks, Set<Intent> dependsOn,
                     int expiresTick) {
    public Intent {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(action, "action");
        blockLocks = blockLocks == null ? Set.of() : Set.copyOf(blockLocks);
        entityLocks = entityLocks == null ? Set.of() : Set.copyOf(entityLocks);
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
    }
}
