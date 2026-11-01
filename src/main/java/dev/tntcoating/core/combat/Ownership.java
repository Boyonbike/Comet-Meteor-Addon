/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * How confident the ledger is that a block cell is ours. Vanilla block updates carry no actor, so
 * {@link #CONFIRMED_OURS} is reserved for a cooperating server and is never produced from observation
 * alone.
 */
public enum Ownership {
    CONFIRMED_OURS,
    INFERRED_OURS,
    CONTESTED,
    NOT_OURS
}
