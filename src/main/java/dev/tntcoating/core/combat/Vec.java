/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/** A three-dimensional point in world space. */
public record Vec(double x, double y, double z) {
    public double distanceSquared(Vec other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Vec other) {
        return Math.sqrt(distanceSquared(other));
    }

    public Vec add(double dx, double dy, double dz) {
        return new Vec(x + dx, y + dy, z + dz);
    }
}
