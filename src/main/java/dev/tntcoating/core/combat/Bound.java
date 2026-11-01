/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Summed outstanding upper bound for one entity and the number of components in it. Components are
 * counted even when their bound is zero, so callers can distinguish "no component" from "zero damage
 * still outstanding".
 */
public record Bound(double value, int components) {
    public Bound {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("value must be finite and non-negative");
        }
        if (components < 0) throw new IllegalArgumentException("components must be non-negative");
    }

    public static Bound zero() {
        return new Bound(0.0, 0);
    }
}
