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
    QUEUE_OVERFLOW("catching up on packets"),
    /** A setback is not yet reconciled against a coherent health snapshot. */
    SETBACK("rubber-banded, resuming shortly"),
    /** A death event is not yet reconciled. */
    DEATH("dead, resumes after respawn"),
    /** A respawn event is not yet reconciled. */
    RESPAWN("respawning"),
    /** The connection or dimension changed and the world view is not rebuilt. */
    CONNECTION_CHANGE("world changed, resuming shortly"),
    /** A packet the damage adapter needs was absent or unusable. */
    MISSING_DAMAGE_DATA("bad damage data, resuming shortly"),
    /** Health and absorption for a possible event have not both been applied yet. */
    INCOHERENT_HEALTH("waiting for a health update"),
    /**
     * Reserved: the server stalled long enough that an unobserved attack cannot be excluded. Nothing
     * raises it yet; live lag pauses through the engine's pause-on-lag TPS check instead.
     */
    SERVER_STALL("server stalled"),
    /** An envelope revision could not be priced safely. */
    WIDENING_UNAVAILABLE("re-checking armour and effects");

    private final String label;

    SuspensionCause(String label) {
        this.label = label;
    }

    /** Plain-English status text shown to the player, e.g. in the wait reason and the risk overlay. */
    public String label() {
        return label;
    }
}
