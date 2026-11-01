/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Why new detonations are held until evidence is reconciled. Suspension is never retirement: outstanding
 * bounds stay in debt and keep counting against the margins.
 */
public enum SuspensionCause {
    /** The observation queue overflowed, so damage evidence may be missing. */
    QUEUE_OVERFLOW,
    /** A setback is not yet reconciled against a coherent health snapshot. */
    SETBACK,
    /** A death event is not yet reconciled. */
    DEATH,
    /** A respawn event is not yet reconciled. */
    RESPAWN,
    /** The connection or dimension changed and the world view is not rebuilt. */
    CONNECTION_CHANGE,
    /** A packet the damage adapter needs was absent or unusable. */
    MISSING_DAMAGE_DATA,
    /** Health and absorption for a possible event have not both been applied yet. */
    INCOHERENT_HEALTH,
    /** The server stalled long enough that an unobserved attack cannot be excluded. */
    SERVER_STALL,
    /** An envelope revision could not be priced safely. */
    WIDENING_UNAVAILABLE
}
