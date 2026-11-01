/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * The reachable-position hull for the predicted ticks, in feet coordinates, plus how much the prediction can
 * be trusted. The box is a hull, never a single predicted point.
 */
public record PredictedBox(Box hull, Confidence confidence) {
    public PredictedBox {
        Objects.requireNonNull(hull, "hull");
        Objects.requireNonNull(confidence, "confidence");
    }

    /** A prediction that cannot be made: the point is all that is known and confidence is lost. */
    public static PredictedBox lost(Vec position) {
        Objects.requireNonNull(position, "position");
        return new PredictedBox(new Box(position, position), Confidence.LOST);
    }

    /** A prediction with no movement: the point is known but nothing was predicted beyond it. */
    public static PredictedBox stationarity(Vec position) {
        Objects.requireNonNull(position, "position");
        return new PredictedBox(new Box(position, position), Confidence.STRONG);
    }

    public boolean contains(Vec position) {
        return hull.contains(position);
    }
}
