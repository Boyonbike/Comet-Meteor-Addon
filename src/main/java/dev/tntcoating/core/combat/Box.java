/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/** An axis-aligned box. Minimums are inclusive and maximums are inclusive for containment tests. */
public record Box(Vec min, Vec max) {
    public Box {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("box minimum must not exceed maximum");
        }
    }

    public static Box fromCell(Cell cell) {
        return new Box(new Vec(cell.x(), cell.y(), cell.z()),
            new Vec(cell.x() + 1.0, cell.y() + 1.0, cell.z() + 1.0));
    }

    public static Box around(Vec center, double radius) {
        return new Box(center.add(-radius, -radius, -radius), center.add(radius, radius, radius));
    }

    public boolean contains(Vec point) {
        return point.x() >= min.x() && point.x() <= max.x()
            && point.y() >= min.y() && point.y() <= max.y()
            && point.z() >= min.z() && point.z() <= max.z();
    }

    public boolean contains(Cell cell) {
        return contains(cell.center());
    }

    public boolean intersects(Box other) {
        return min.x() <= other.max.x() && max.x() >= other.min.x()
            && min.y() <= other.max.y() && max.y() >= other.min.y()
            && min.z() <= other.max.z() && max.z() >= other.min.z();
    }

    public Box expand(double amount) {
        if (amount < 0) throw new IllegalArgumentException("expand amount must be non-negative");
        return new Box(min.add(-amount, -amount, -amount), max.add(amount, amount, amount));
    }

    public Vec center() {
        return new Vec((min.x() + max.x()) / 2.0, (min.y() + max.y()) / 2.0,
            (min.z() + max.z()) / 2.0);
    }
}
