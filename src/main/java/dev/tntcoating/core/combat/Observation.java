/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * One applied packet observation. Identity and world fields are resolved on the client thread after
 * vanilla applied the packet; an observation is never created from a network-thread receipt.
 *
 * @param conn            connection epoch the receipt arrived on
 * @param receiptSeq      monotonic receipt order; duplicates are ignored
 * @param appliedRevision applied-state revision this observation caused
 * @param receiptNanos    {@link System#nanoTime()} at receipt
 * @param clientTick      client tick at application
 * @param kind            observation kind
 * @param entity          resolved identity, or {@code null} when the packet names no entity
 * @param cell            block cell for block updates and explosions, otherwise {@code null}
 * @param position        resolved position, or {@code null} when unknown
 * @param bounds          resolved entity bounds, or {@code null} when unknown
 * @param payload         immutable kind-specific detail
 */
public record Observation(ConnectionKey conn, long receiptSeq, long appliedRevision, long receiptNanos,
                          int clientTick, ObservationKind kind, EntityKey entity, Cell cell, Vec position,
                          Box bounds, ObservationPayload payload) {
    public Observation {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(kind, "kind");
        payload = payload == null ? new ObservationPayload.None() : payload;
    }
}
