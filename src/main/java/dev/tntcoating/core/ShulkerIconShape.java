package dev.tntcoating.core;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** Stable model-geometry identity, independent of textures, vertex order and duplicate face corners. */
public final class ShulkerIconShape {
    private ShulkerIconShape() {}

    public record Point(long x, long y, long z) implements Comparable<Point> {
        public static Point of(float x, float y, float z) {
            return new Point(Math.round(x * 10000), Math.round(y * 10000), Math.round(z * 10000));
        }
        @Override public int compareTo(Point other) {
            int result = Long.compare(x, other.x);
            if (result == 0) result = Long.compare(y, other.y);
            return result == 0 ? Long.compare(z, other.z) : result;
        }
    }

    public static List<Point> key(Collection<Point> vertices) {
        return List.copyOf(new TreeSet<>(vertices));
    }
}
