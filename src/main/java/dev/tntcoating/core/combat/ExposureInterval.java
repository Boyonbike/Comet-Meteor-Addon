/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * The swept exposure interval over every feasible crystal position and every feasible victim position: the
 * smallest exposure any pair can have and the largest any pair can have. Both are unobstructed fractions in
 * {@code [0, 1]}; the lower bound is computed with {@link ExposurePolicy#TARGET_LOWER_BOUND} and the upper
 * bound with {@link ExposurePolicy#SELF_FRIEND_UPPER_BOUND}.
 */
public record ExposureInterval(double lower, double upper) {
    public ExposureInterval {
        requireFraction(lower, "lower");
        requireFraction(upper, "upper");
        if (lower > upper) {
            throw new IllegalArgumentException("lower must not exceed upper: " + lower + " > " + upper);
        }
    }

    private static void requireFraction(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be a fraction in [0, 1]: " + value);
        }
    }
}
