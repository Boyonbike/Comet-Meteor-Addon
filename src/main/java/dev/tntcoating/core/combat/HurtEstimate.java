/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * A raw-damage estimate after the victim's invulnerability window has been applied. Never absorption-adjusted
 * and never rounded.
 *
 * <p>{@code guaranteedLower} is the amount the victim cannot take less than; it is zero whenever the prior
 * hurt is unknown or the arrival could fall on either side of the window edge. {@code expected} is the
 * best estimate given the recorded confidence.</p>
 */
public record HurtEstimate(double guaranteedLower, double expected) {
    public HurtEstimate {
        if (!Double.isFinite(guaranteedLower) || guaranteedLower < 0.0) {
            throw new IllegalArgumentException("guaranteedLower must be finite and non-negative: "
                + guaranteedLower);
        }
        if (!Double.isFinite(expected) || expected < 0.0) {
            throw new IllegalArgumentException("expected must be finite and non-negative: " + expected);
        }
        if (expected < guaranteedLower) {
            throw new IllegalArgumentException(
                "expected must be >= guaranteedLower: " + expected + " < " + guaranteedLower);
        }
    }

    /** No prior hurt is known: the lower bound is zero and the expectation uses the full value. */
    public static HurtEstimate unknownPrior(double incoming) {
        if (!Double.isFinite(incoming) || incoming < 0.0) {
            throw new IllegalArgumentException("incoming must be finite and non-negative: " + incoming);
        }
        return new HurtEstimate(0.0, incoming);
    }
}
