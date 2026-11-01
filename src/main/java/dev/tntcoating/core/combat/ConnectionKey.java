/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Identity of one server connection and its world. Numeric entity IDs are only meaningful inside this
 * epoch; the same number in another connection is a different entity.
 */
public record ConnectionKey(long epoch) {
}
