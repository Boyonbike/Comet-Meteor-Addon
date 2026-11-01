/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/** Stable handle for one risk reservation. Ids are monotonic for the life of a budget. */
public record RiskId(long value) {
    public RiskId {
        if (value <= 0) throw new IllegalArgumentException("risk id must be positive");
    }
}
