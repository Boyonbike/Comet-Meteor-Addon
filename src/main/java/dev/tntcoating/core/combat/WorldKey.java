/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * What {@link CombatSession#open()} returns: the connection epoch plus the world generation opened with
 * it. Entity identity is still carried by the connection key, so a world generation is informational.
 */
public record WorldKey(ConnectionKey connection, long world) {
    public WorldKey {
        Objects.requireNonNull(connection, "connection");
    }
}
