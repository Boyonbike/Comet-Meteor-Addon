/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * One observed motion state of an entity, in blocks and blocks per server tick. The model predicts from this
 * single sample; it keeps no per-entity history.
 */
public record MotionSample(double x, double y, double z, double vx, double vy, double vz,
                           boolean onGround, boolean teleportOrSetback) {
    public MotionSample {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(vx, "vx");
        requireFinite(vy, "vy");
        requireFinite(vz, "vz");
    }

    public static MotionSample of(double x, double y, double z, double vx, double vy, double vz,
                                  boolean onGround) {
        return new MotionSample(x, y, z, vx, vy, vz, onGround, false);
    }

    public Vec position() {
        return new Vec(x, y, z);
    }

    public Vec velocity() {
        return new Vec(vx, vy, vz);
    }

    /** A teleport or setback is a new observation, not a continuation of the previous trajectory. */
    public MotionSample invalidated() {
        return new MotionSample(x, y, z, vx, vy, vz, onGround, true);
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }
}
