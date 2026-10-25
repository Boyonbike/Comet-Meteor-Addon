package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HighwayDetourTest {
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);
    private static final double EAST = -90;

    @Test void clearPathKeepsThePreferredAltitudeAndHeading() {
        Probe probe = new Probe();
        var choice = HighwayDetour.choose(BODY, EAST, 65,
            new HighwayDetour.Config(45, 5, 5, 125, 2, 32, 0), probe);

        assertEquals(HighwayDetour.Reason.CLEAR, choice.reason());
        assertEquals(0, choice.yawOffsetDegrees(), 1e-6);
        assertEquals(65, choice.targetY(), 1e-6);
    }

    @Test void lateralDetourUsesTheClosestOffset() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(6, 64, .2, 7, 67, .8));

        var choice = HighwayDetour.choose(BODY, EAST, 65,
            new HighwayDetour.Config(45, 5, 5, 125, 2, 32, 0), probe);
        assertEquals(HighwayDetour.Reason.DETOUR, choice.reason());
        assertNotEquals(0, choice.yawOffsetDegrees(), 1e-6);
        assertTrue(Math.abs(choice.yawOffsetDegrees()) <= 45 + 1e-6);
        assertTrue(Math.abs(choice.yawOffsetDegrees()) <= 25 + 1e-6);
    }

    @Test void climbsInsideTheBandWhenLateralOffsetsCannotClear() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(2, 64, -8, 3, 68, 8));

        var choice = HighwayDetour.choose(BODY, EAST, 65,
            new HighwayDetour.Config(20, 5, 5, 70, 2, 32, 0), probe);

        assertEquals(HighwayDetour.Reason.DETOUR, choice.reason());
        assertEquals(69, choice.targetY(), 1e-6);
    }

    @Test void blockedWhenEveryCandidateInsideTheBandIsBlocked() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(2, 55, -12, 3, 85, 12));

        var choice = HighwayDetour.choose(BODY, EAST, 65,
            new HighwayDetour.Config(20, 5, 60, 70, 5, 32, 0), probe);

        assertEquals(HighwayDetour.Reason.BLOCKED, choice.reason());
    }

    @Test void unloadedChunksAreReportedWithoutProbing() {
        Probe probe = new Probe(false);
        probe.obstacles.add(new AABB(2, 64, -2, 3, 68, 2));

        var choice = HighwayDetour.choose(BODY, EAST, 65,
            new HighwayDetour.Config(20, 5, 5, 125, 2, 32, 0), probe);

        assertEquals(HighwayDetour.Reason.CHUNKS, choice.reason());
        assertTrue(probe.boxes.isEmpty());
    }

    @Test void minimumClearDistanceCanRejectAFullClearResult() {
        Probe probe = new Probe();
        var choice = HighwayDetour.choose(BODY, EAST, 65,
            new HighwayDetour.Config(0, 5, 5, 125, 2, 16, 32), probe);

        assertEquals(HighwayDetour.Reason.BLOCKED, choice.reason());
    }

    @Test void clearAtRequiresTheWholeProbeDistance() {
        Probe probe = new Probe();
        probe.obstacles.add(new AABB(2, 64, -2, 3, 68, 2));

        assertFalse(HighwayDetour.clearAt(BODY, 65, EAST, 32, probe));
        assertTrue(HighwayDetour.clearAt(BODY, 69, EAST, 32, probe));
    }

    @Test void rejectsUnsafeConfigurationAndInputs() {
        assertThrows(IllegalArgumentException.class,
            () -> new HighwayDetour.Config(91, 5, 5, 125, 2, 32, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new HighwayDetour.Config(20, 0, 5, 125, 2, 32, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new HighwayDetour.Config(20, 5, 126, 125, 2, 32, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new HighwayDetour.Config(20, 5, 5, 125, 2, 65, 0));
        assertThrows(IllegalArgumentException.class,
            () -> HighwayDetour.choose(BODY, Double.NaN, 65,
                new HighwayDetour.Config(20, 5, 5, 125, 2, 32, 0), new Probe()));
    }

    private static final class Probe implements FlightCorridor.Probe {
        final List<AABB> obstacles = new ArrayList<>();
        final List<AABB> boxes = new ArrayList<>();
        final boolean loaded;

        Probe() { this(true); }
        Probe(boolean loaded) { this.loaded = loaded; }

        @Override public boolean loaded(int chunkX, int chunkZ) { return loaded; }

        @Override public boolean clear(AABB swept) {
            boxes.add(swept);
            return obstacles.stream().noneMatch(swept::intersects);
        }
    }
}