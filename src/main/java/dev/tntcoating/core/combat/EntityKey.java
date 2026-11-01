/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;
import java.util.UUID;

/**
 * A verified entity identity inside one connection. The UUID may be {@code null} only on an unresolved
 * receipt; every identity used for an attack carries both the numeric ID and the UUID.
 */
public record EntityKey(ConnectionKey conn, int id, UUID uuid) {
    public EntityKey {
        Objects.requireNonNull(conn, "conn");
        if (id < 0) throw new IllegalArgumentException("entity id must be non-negative");
    }
}
