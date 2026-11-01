/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Lifecycle state of a crystal known to the ledger. {@link #EXPLODED} and {@link #REMOVED} are separate
 * facts about why the crystal is gone; neither is risk-retirement evidence on its own.
 */
public enum CrystalState {
    /** A spawn hazard was reserved but has not been sent. */
    PROPOSED,
    /** The placement was sent and no crystal is bound yet. */
    SENT,
    /** The crystal exists in the applied world. */
    OBSERVED,
    /** The entity left tracking range. This is not removal evidence. */
    TRACKING_LOST,
    /** An explosion was observed within association range of the crystal's last known position. */
    EXPLODED,
    /** The server removed the entity without an associated explosion. */
    REMOVED,
    /** More than one crystal could explain a spawn; no ownership is claimed. */
    QUARANTINED
}
