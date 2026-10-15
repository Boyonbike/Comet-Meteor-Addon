package dev.tntcoating.core;

import dev.tntcoating.core.WaypointMirror.Dim;
import dev.tntcoating.core.WaypointMirror.Marker;
import dev.tntcoating.core.WaypointMirror.Source;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WaypointMirrorTest {
    private static Source source(String id, Dim dim, int x, int z, boolean opposite, boolean visible) {
        return new Source(id, "base", x, 64, z, dim, opposite, visible, 0xFF0000);
    }

    @Test void showsWaypointInItsOwnDimension() {
        var plan = WaypointMirror.plan(List.of(source("a", Dim.NETHER, 10, -20, false, true)), false, true);
        assertEquals(Map.of(Dim.NETHER, List.of(new Marker("a", "base", "B", 10, 64, -20, 0xFF0000, true))), plan);
    }

    @Test void hiddenWaypointsOnlyWhenIncluded() {
        var hidden = List.of(source("a", Dim.OVERWORLD, 0, 0, false, false));
        assertTrue(WaypointMirror.plan(hidden, false, true).isEmpty());
        assertEquals(1, WaypointMirror.plan(hidden, true, true).get(Dim.OVERWORLD).size());
    }

    @Test void oppositeDimensionUsesMeteorScaling() {
        var plan = WaypointMirror.plan(List.of(source("a", Dim.OVERWORLD, 100, -100, true, true)), false, true);
        Marker nether = plan.get(Dim.NETHER).getFirst();
        // Meteor truncates towards zero: -100 / 8 is -12, not floor's -13.
        assertEquals(12, nether.x());
        assertEquals(-12, nether.z());
        assertEquals("a", nether.id());

        Marker overworld = WaypointMirror.plan(List.of(source("b", Dim.NETHER, 12, -13, true, true)), false, true).get(Dim.OVERWORLD).getFirst();
        assertEquals(96, overworld.x());
        assertEquals(-104, overworld.z());
    }

    @Test void endHasNoOppositeAndOppositeIsOptIn() {
        assertEquals(Map.of(Dim.END, 1), counts(WaypointMirror.plan(List.of(source("a", Dim.END, 0, 0, true, true)), false, true)));
        assertEquals(Map.of(Dim.OVERWORLD, 1), counts(WaypointMirror.plan(List.of(source("a", Dim.OVERWORLD, 0, 0, false, true)), false, true)));
    }

    @Test void worldMapOnlyFlagReachesEveryMarker() {
        var plan = WaypointMirror.plan(List.of(source("a", Dim.OVERWORLD, 0, 0, true, true)), false, false);
        plan.values().forEach(markers -> markers.forEach(marker -> assertFalse(marker.minimap())));
    }

    @Test void unchangedWaypointsGiveAnEqualPlan() {
        var sources = List.of(source("a", Dim.OVERWORLD, 1, 2, true, true), source("b", Dim.END, 3, 4, false, true));
        assertEquals(WaypointMirror.plan(sources, false, true), WaypointMirror.plan(List.copyOf(sources), false, true));
        assertNotEquals(WaypointMirror.plan(sources, false, true), WaypointMirror.plan(sources, false, false));
    }

    @Test void initialsFollowXaero() {
        assertEquals("B", WaypointMirror.initials("base"));
        assertEquals("É", WaypointMirror.initials("  élan"));
        assertEquals("X", WaypointMirror.initials(" "));
    }

    @Test void picksNearestPaletteColourIgnoringAlpha() {
        int[] palette = {0xFF000000, 0xFFFF5555, 0xFF5555FF, 0xFFFFFFFF};
        assertEquals(1, WaypointMirror.nearestColor(0xFF5555, palette));
        assertEquals(1, WaypointMirror.nearestColor(0xE04040, palette));
        assertEquals(2, WaypointMirror.nearestColor(0x3030D0, palette));
        assertEquals(0, WaypointMirror.nearestColor(0x101010, palette));
        assertEquals(3, WaypointMirror.nearestColor(0xF0F0F0, palette));
    }

    private static Map<Dim, Integer> counts(Map<Dim, List<Marker>> plan) {
        var counts = new java.util.EnumMap<Dim, Integer>(Dim.class);
        plan.forEach((dim, markers) -> counts.put(dim, markers.size()));
        return counts;
    }
}
