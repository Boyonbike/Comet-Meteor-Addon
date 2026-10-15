package dev.tntcoating.core;

import java.util.LinkedHashSet;
import java.util.List;

/** Geometry independent of the game: cube inclusion and alternate clickable face points. */
public final class PlacementGeometry {
    public record Point(double x, double y, double z) {}
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    private PlacementGeometry() {}

    public static boolean inCube(int x, int y, int z, int ox, int oy, int oz, int radius) {
        return Math.abs(x - ox) <= radius && Math.abs(y - oy) <= radius && Math.abs(z - oz) <= radius;
    }

    public static List<Point> faceSamples(Box box, int axis, int sign, Point eye) {
        return faceSamples(box, axis, sign, eye, false);
    }

    public static List<Point> faceSamples(Box box, int axis, int sign, Point eye, boolean narrowGaps) {
        if (axis < 0 || axis > 2 || (sign != -1 && sign != 1)) throw new IllegalArgumentException("Invalid face");
        double[] min = { box.minX, box.minY, box.minZ }, max = { box.maxX, box.maxY, box.maxZ };
        double[] viewer = { eye.x, eye.y, eye.z };
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        double loA = min[a] + (max[a] - min[a]) * .12, hiA = max[a] - (max[a] - min[a]) * .12;
        double loB = min[b] + (max[b] - min[b]) * .12, hiB = max[b] - (max[b] - min[b]) * .12;
        double face = sign > 0 ? max[axis] : min[axis];
        LinkedHashSet<Point> points = new LinkedHashSet<>();
        points.add(point(axis, a, b, face, Math.clamp(viewer[a], loA, hiA), Math.clamp(viewer[b], loB, hiB)));
        points.add(point(axis, a, b, face, (loA + hiA) / 2, (loB + hiB) / 2));
        for (double aa : new double[] {loA, (loA + hiA) / 2, hiA}) {
            for (double bb : new double[] {loB, (loB + hiB) / 2, hiB}) points.add(point(axis, a, b, face, aa, bb));
        }
        if (!narrowGaps) return List.copyOf(points);
        // Near-edge samples can expose faces through the narrow gaps around chests and partial blocks.
        double edgeA = Math.min(.005, (max[a] - min[a]) * .01);
        double edgeB = Math.min(.005, (max[b] - min[b]) * .01);
        for (double aa : new double[] {min[a] + edgeA, (min[a] + max[a]) / 2, max[a] - edgeA}) {
            for (double bb : new double[] {min[b] + edgeB, (min[b] + max[b]) / 2, max[b] - edgeB})
                points.add(point(axis, a, b, face, aa, bb));
        }
        return List.copyOf(points);
    }

    private static Point point(int axis, int a, int b, double face, double aa, double bb) {
        double[] p = new double[3]; p[axis] = face; p[a] = aa; p[b] = bb;
        return new Point(p[0], p[1], p[2]);
    }
}
