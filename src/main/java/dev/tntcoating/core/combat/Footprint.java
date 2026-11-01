/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Set;

/** What a plan occupies and could disturb; Hybrid uses it to decide whether two plans may coexist. */
public record Footprint(Set<Cell> structural, Box swept, Box blastSearch, Set<EntityKey> bound) {
    public Footprint {
        structural = structural == null ? Set.of() : Set.copyOf(structural);
        bound = bound == null ? Set.of() : Set.copyOf(bound);
    }
}
