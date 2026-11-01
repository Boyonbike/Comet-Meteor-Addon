/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * Immutable view of one scheduler reservation. A sent lease stays registered until its plan is released
 * or invalidated so {@code holder(Cell)} can still describe the physical footprint; the sent flag tells
 * callers that the causative packets may already be on the wire.
 */
public record Lease(long plan, Strategy strategy, int expiresTick, Footprint footprint, Ticket ticket,
                    Intent intent, boolean sent) {
    public Lease {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(footprint, "footprint");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(intent, "intent");
    }
}
