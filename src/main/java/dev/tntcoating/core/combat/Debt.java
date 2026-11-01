/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * Outstanding sent-without-terminal-evidence status for one entity: how many risk components are in debt
 * and their summed upper bound. A zero-bound component still contributes a count (rules 3 and 4), so a
 * presence check is not the same as a margin check.
 */
public record Debt(int count, double bound) {
    public Debt {
        if (count < 0) throw new IllegalArgumentException("count must be non-negative");
        if (!Double.isFinite(bound) || bound < 0.0) {
            throw new IllegalArgumentException("bound must be finite and non-negative");
        }
    }

    public static Debt zero() {
        return new Debt(0, 0.0);
    }
}
