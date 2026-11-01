/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Revision of the worst-case protection state a reservation was priced against. When protection can
 * deteriorate before arrival the adapter publishes a wider envelope; every outstanding reservation's upper
 * bound then widens and never narrows again. Phase 1 keeps only the revision: the damage adapter in Phase
 * 1.5 supplies the per-state numbers, and the risk budget in Phase 2 applies the widening.
 */
public record MitigationEnvelope(long revision) {
}
