/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/** Reasons the engine refuses to start new work. Sent risk and hazards are never cleared by a pause. */
public enum DirectPause {
    EATING,
    DRINKING,
    MINING,
    SCREEN,
    BARITONE,
    HEALTH,
    LAG,
    SUSPENDED,
    /** Phase 4.3: a hurt-sync window wait; no send and no state change. */
    HURT_SYNC;

    public String describe() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
