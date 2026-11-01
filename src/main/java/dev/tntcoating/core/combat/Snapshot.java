/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.List;
import java.util.Objects;

/**
 * A bounded immutable view of the applied world, stamped with the revision it reflects. Phase 1 uses it
 * only to rescan surviving crystals after an action generation ends; Phase 2's runtime capture supplies
 * the same shape.
 */
public record Snapshot(ConnectionKey conn, long appliedRevision, List<Crystal> crystals) {
    public Snapshot {
        Objects.requireNonNull(conn, "conn");
        crystals = crystals == null ? List.of() : List.copyOf(crystals);
    }

    /** One end crystal present in the applied world. */
    public record Crystal(EntityKey entity, Vec position) {
        public Crystal {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(position, "position");
        }
    }
}
