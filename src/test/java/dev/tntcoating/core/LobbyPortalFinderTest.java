package dev.tntcoating.core;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LobbyPortalFinderTest {
    private static final Vec3 ORIGIN = new Vec3(.5, 64, .5);
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 319;

    @Test void findsTheNearestLoadedPortal() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(6, 64, 0));
        probe.portals.add(new BlockPos(2, 64, 0));

        LobbyPortalFinder.Result result = LobbyPortalFinder
            .nearest(ORIGIN, 32, 16, MIN_Y, MAX_Y, probe)
            .orElseThrow();

        assertEquals(new BlockPos(2, 64, 0), result.pos());
        assertEquals(4.25, result.distanceSq(), 1e-6);
    }

    @Test void ignoresPortalsOutsideTheHorizontalRadius() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(9, 64, 0));

        assertTrue(LobbyPortalFinder.nearest(ORIGIN, 8, 16, MIN_Y, MAX_Y, probe).isEmpty());
    }

    @Test void ignoresPortalsOutsideTheVerticalRange() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(2, 70, 0));

        assertTrue(LobbyPortalFinder.nearest(ORIGIN, 32, 2, MIN_Y, MAX_Y, probe).isEmpty());
    }

    @Test void clampsTheScanToTheBuildRange() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(2, -64, 0));

        LobbyPortalFinder.Result result = LobbyPortalFinder
            .nearest(new Vec3(.5, -63, .5), 8, 8, MIN_Y, MAX_Y, probe)
            .orElseThrow();

        assertEquals(new BlockPos(2, -64, 0), result.pos());
    }

    @Test void skipsUnloadedChunks() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(2, 64, 0));
        probe.allChunksLoaded = false;

        assertTrue(LobbyPortalFinder.nearest(ORIGIN, 32, 16, MIN_Y, MAX_Y, probe).isEmpty());
        assertEquals(0, probe.blockChecks);
    }

    @Test void checksTheSectionPaletteBeforeReadingBlocks() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(2, 64, 0));
        probe.paletteMayContainPortal = false;

        assertTrue(LobbyPortalFinder.nearest(ORIGIN, 32, 16, MIN_Y, MAX_Y, probe).isEmpty());
        assertEquals(0, probe.blockChecks);
    }

    @Test void handlesNegativeCoordinates() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(-20, 64, -20));

        assertTrue(LobbyPortalFinder
            .nearest(new Vec3(-.5, 64, -.5), 32, 16, MIN_Y, MAX_Y, probe)
            .isPresent());
    }

    @Test void usesAStableTieBreak() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(1, 64, 0));
        probe.portals.add(new BlockPos(-1, 64, 0));

        LobbyPortalFinder.Result result = LobbyPortalFinder
            .nearest(ORIGIN, 32, 16, MIN_Y, MAX_Y, probe)
            .orElseThrow();

        assertEquals(new BlockPos(-1, 64, 0), result.pos());
    }

    @Test void rejectsInvalidInputs() {
        Probe probe = new Probe();
        assertThrows(IllegalArgumentException.class,
            () -> LobbyPortalFinder.nearest(new Vec3(Double.NaN, 64, 0), 8, 8, MIN_Y, MAX_Y, probe));
        assertThrows(IllegalArgumentException.class,
            () -> LobbyPortalFinder.nearest(ORIGIN, 0, 8, MIN_Y, MAX_Y, probe));
        assertThrows(IllegalArgumentException.class,
            () -> LobbyPortalFinder.nearest(ORIGIN, 8, -1, MIN_Y, MAX_Y, probe));
        assertThrows(IllegalArgumentException.class,
            () -> LobbyPortalFinder.nearest(ORIGIN, 8, 8, 64, 63, probe));
        assertThrows(NullPointerException.class,
            () -> LobbyPortalFinder.nearest(null, 8, 8, MIN_Y, MAX_Y, probe));
        assertThrows(NullPointerException.class,
            () -> LobbyPortalFinder.nearest(ORIGIN, 8, 8, MIN_Y, MAX_Y, null));
    }

    private static final class Probe implements LobbyPortalFinder.Probe {
        final Set<BlockPos> portals = new HashSet<>();
        boolean allChunksLoaded = true;
        boolean paletteMayContainPortal = true;
        int blockChecks;

        @Override public boolean loaded(int chunkX, int chunkZ) {
            return allChunksLoaded;
        }

        @Override public boolean maybePortal(int chunkX, int sectionY, int chunkZ) {
            return paletteMayContainPortal;
        }

        @Override public boolean portal(BlockPos pos) {
            blockChecks++;
            return portals.contains(pos);
        }
    }
}
