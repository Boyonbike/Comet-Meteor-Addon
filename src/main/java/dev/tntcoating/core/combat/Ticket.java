/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * Authority to act for one plan. A ticket is valid only while its connection, action generation and config
 * generation all still match the session.
 */
public record Ticket(ConnectionKey conn, ActionGeneration gen, long config, long plan) {
    public Ticket {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(gen, "gen");
    }
}
