/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Three target damage values kept apart. Lethal eligibility needs {@code guaranteedLower} at least equal
 * to the target's effective-health upper bound; {@code expected} may rank but never claims lethality.
 */
public record TargetEstimate(double guaranteedLower, double expected, double optimistic) {
}
