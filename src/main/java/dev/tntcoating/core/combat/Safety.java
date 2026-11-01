/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/** The safety thresholds a single opportunity is checked against. */
public record Safety(double minTarget, double maxSelf, double minRemaining, double maxFriend) {
}
