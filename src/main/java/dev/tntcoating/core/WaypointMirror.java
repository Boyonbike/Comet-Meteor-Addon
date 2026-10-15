package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Which map markers to show for Meteor waypoints, per dimension. */
public final class WaypointMirror {
    private WaypointMirror() {}

    public enum Dim { OVERWORLD, NETHER, END }

    /** A Meteor waypoint. {@code opposite} is Meteor's "also show in the opposite dimension" option. */
    public record Source(String id, String name, int x, int y, int z, Dim dim, boolean opposite, boolean visible, int rgb) {}

    /** A map marker. With {@code minimap} off it appears on the world map only. */
    public record Marker(String id, String name, String initials, int x, int y, int z, int rgb, boolean minimap) {}

    public static Map<Dim, List<Marker>> plan(Iterable<Source> sources, boolean includeHidden, boolean minimap) {
        Map<Dim, List<Marker>> plan = new EnumMap<>(Dim.class);
        for (Source s : sources) {
            if (!s.visible() && !includeHidden) continue;
            String initials = initials(s.name());
            add(plan, s.dim(), new Marker(s.id(), s.name(), initials, s.x(), s.y(), s.z(), s.rgb(), minimap));
            if (!s.opposite() || s.dim() == Dim.END) continue;
            // Same conversion as Meteor's Waypoint.getPos(), truncating division included, so the marker sits where Meteor draws it.
            boolean toNether = s.dim() == Dim.OVERWORLD;
            add(plan, toNether ? Dim.NETHER : Dim.OVERWORLD, new Marker(s.id(), s.name(), initials,
                toNether ? s.x() / 8 : s.x() * 8, s.y(), toNether ? s.z() / 8 : s.z() * 8, s.rgb(), minimap));
        }
        return plan;
    }

    private static void add(Map<Dim, List<Marker>> plan, Dim dim, Marker marker) {
        plan.computeIfAbsent(dim, d -> new ArrayList<>()).add(marker);
    }

    /** The name's first character, upper-cased, as Xaero does for new waypoints. */
    public static String initials(String name) {
        String trimmed = name.strip();
        if (trimmed.isEmpty()) return "X";
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    }

    /** Index of the palette colour closest to {@code rgb}, by the "redmean" weighted distance. Alpha is ignored. */
    public static int nearestColor(int rgb, int[] palette) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            long distance = distance(rgb, palette[i]);
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static long distance(int a, int b) {
        int r1 = a >> 16 & 255, r2 = b >> 16 & 255;
        int dr = r1 - r2, dg = (a >> 8 & 255) - (b >> 8 & 255), db = (a & 255) - (b & 255);
        int redMean = (r1 + r2) / 2;
        return (long) (512 + redMean) * dr * dr + 1024L * dg * dg + (long) (767 - redMean) * db * db;
    }
}
