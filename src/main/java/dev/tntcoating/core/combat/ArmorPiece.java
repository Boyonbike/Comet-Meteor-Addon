/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * One worn armour piece as the mitigation envelope sees it.
 *
 * <p>{@code couldBreakBeforeArrival} is the caller's audited judgement, not a guess made here: when the
 * piece has little durability left, the planned explosion may destroy it before the latest arrival and
 * its protection must then be removed from the envelope.</p>
 */
public record ArmorPiece(double armour, double toughness, double protectionPoints,
                         boolean couldBreakBeforeArrival) {
    public ArmorPiece {
        requireNonNegativeFinite(armour, "armour");
        requireNonNegativeFinite(toughness, "toughness");
        requireNonNegativeFinite(protectionPoints, "protectionPoints");
    }

    static void requireNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative: " + value);
        }
    }
}
