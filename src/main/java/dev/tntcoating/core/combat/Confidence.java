/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/** Confidence in a predicted position; a prediction is never treated as certain. */
public enum Confidence {
    STRONG(1.0),
    WEAK(0.5),
    LOST(0.0);

    private final double factor;

    Confidence(double factor) {
        this.factor = factor;
    }

    public double factor() {
        return factor;
    }

    public Confidence lowerBy() {
        return switch (this) {
            case STRONG -> WEAK;
            case WEAK, LOST -> LOST;
        };
    }
}
