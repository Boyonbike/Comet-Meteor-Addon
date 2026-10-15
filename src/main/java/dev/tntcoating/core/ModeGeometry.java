package dev.tntcoating.core;
import java.util.ArrayList;
import java.util.List;
public final class ModeGeometry {
    private ModeGeometry() {}
    public record Cell(int x, int y, int z) {}
    /** Follow the actual horizontal trajectory, including diagonal boundary crossings, two blocks ahead. */
    public static List<Cell> path(double x, int y, double z, double dx, double dz, int width) {
        if (width < 1 || width > 9) throw new IllegalArgumentException("width");
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < .0001) { dx = 0; dz = 1; } else { dx /= length; dz /= length; }
        java.util.LinkedHashSet<Cell> result = new java.util.LinkedHashSet<>();
        for (int lateralIndex = 0; lateralIndex < width; lateralIndex++) {
            int lateral = lateralIndex == 0 ? 0 : (lateralIndex % 2 == 1 ? (lateralIndex + 1) / 2 : -lateralIndex / 2);
            boolean alongX = Math.abs(dx) > Math.abs(dz);
            for (int step = 0; step <= 8; step++) {
                result.add(new Cell((int) Math.floor(x + dx * step * .25) + (alongX ? 0 : lateral), y,
                    (int) Math.floor(z + dz * step * .25) + (alongX ? lateral : 0)));
            }
        }
        return new ArrayList<>(result);
    }

    /** Minecraft yaw: zero faces south, positive 90 faces west. Pitch does not affect backfill. */
    public static boolean behindFacing(double x, double z, double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return behind(x, z, -Math.sin(yaw), Math.cos(yaw));
    }

    public static boolean behind(double x, double z, double dx, double dz) {
        return x * dx + z * dz <= -1.5;
    }
}
