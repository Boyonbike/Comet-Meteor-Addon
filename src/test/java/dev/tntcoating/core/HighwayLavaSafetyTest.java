package dev.tntcoating.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HighwayLavaSafetyTest {
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);

    @Test void fluidWithoutAnyCollisionShapeStillBlocksTheRoute() {
        Probe probe = new Probe();
        probe.lava.add(new BlockPos(3, 65, 0));

        assertTrue(HighwayLavaSafety.intersects(BODY, new Vec3(5, 0, 0), probe));
    }

    @Test void diagonalSweepIgnoresLavaBesideTheActualFlight() {
        Probe probe = new Probe();
        probe.lava.add(new BlockPos(0, 65, 4));

        assertFalse(HighwayLavaSafety.intersects(BODY, new Vec3(6, 0, 6), probe));
    }

    @Test void diagonalSweepFindsLavaTheBodyWillEnter() {
        Probe probe = new Probe();
        probe.lava.add(new BlockPos(3, 65, 3));

        assertTrue(HighwayLavaSafety.intersects(BODY, new Vec3(6, 0, 6), probe));
    }

    @Test void descentChecksLavaUnderTheWholeLandingColumn() {
        Probe probe = new Probe();
        probe.lava.add(new BlockPos(0, 61, 0));

        assertTrue(HighwayLavaSafety.intersects(BODY, new Vec3(0, -5, 0), probe));
        assertFalse(HighwayLavaSafety.intersects(BODY, new Vec3(0, -2, 0), probe));
    }

    @Test void stationaryBodyDetectsCurrentLavaOverlap() {
        Probe probe = new Probe();
        probe.lava.add(new BlockPos(0, 66, 0));

        assertTrue(HighwayLavaSafety.intersects(BODY, Vec3.ZERO, probe));
    }

    @Test void touchingLavaOnlyAtTheEndOfMovementDoesNotOverlap() {
        Probe probe = new Probe();
        probe.lava.add(new BlockPos(3, 65, 0));

        assertFalse(HighwayLavaSafety.intersects(BODY, new Vec3(2.2, 0, 0), probe));
    }

    @Test void neverQueriesFluidFromAnUnloadedChunk() {
        Probe probe = new Probe();
        probe.unloaded.add(new BlockPos(1, 0, 0));
        probe.lava.add(new BlockPos(17, 65, 0));

        assertFalse(HighwayLavaSafety.intersects(BODY, new Vec3(20, 0, 0), probe));
        assertTrue(probe.fluidReads > 0);
    }

    @Test void negativeCoordinatesUseTheCorrectLoadedChunk() {
        Probe probe = new Probe();
        probe.lava.add(new BlockPos(-3, 65, 0));

        assertTrue(HighwayLavaSafety.intersects(BODY, new Vec3(-5, 0, 0), probe));
        probe.unloaded.add(new BlockPos(-1, 0, 0));
        assertFalse(HighwayLavaSafety.intersects(BODY, new Vec3(-5, 0, 0), probe));
    }

    @Test void horizontalFlightAboveTheSourceSurfaceDoesNotEnterItsBlockVolume() {
        PartialFluidProbe probe = new PartialFluidProbe();
        BlockPos lava = new BlockPos(1, 64, 0);
        probe.lava.add(lava);
        probe.bounds.put(lava, new AABB(1, 64, 0, 2, 64.8888889, 1));
        AABB gliding = new AABB(.2, 64.9, .2, .8, 65.5, .8);

        assertFalse(HighwayLavaSafety.intersects(gliding, new Vec3(2, 0, 0), probe));
    }

    @Test void descentStillDetectsEntryBelowTheActualFluidSurface() {
        PartialFluidProbe probe = new PartialFluidProbe();
        BlockPos lava = new BlockPos(0, 64, 0);
        probe.lava.add(lava);
        probe.bounds.put(lava, new AABB(0, 64, 0, 1, 64.875, 1));

        assertTrue(HighwayLavaSafety.intersects(BODY, new Vec3(0, -.2, 0), probe));
        assertFalse(HighwayLavaSafety.intersects(BODY, new Vec3(0, -.125, 0), probe));
    }

    @Test void gravityAtASupportedWalkingEdgeStaysAboveTheLavaSurface() {
        PartialFluidProbe probe = new PartialFluidProbe();
        BlockPos lava = new BlockPos(1, 64, 0);
        probe.lava.add(lava);
        probe.bounds.put(lava, new AABB(1, 64, 0, 2, 64.8888889, 1));
        AABB walking = new AABB(.45, 65, .2, 1.05, 66.8, .8);

        assertFalse(HighwayLavaSafety.intersects(walking, new Vec3(.1, -.0784, 0), probe));
        // A supporting block clips the downward movement before the player actually moves.
        assertFalse(HighwayLavaSafety.intersects(walking, new Vec3(.1, 0, 0), probe));
    }

    @Test void stationaryBodyAboveShallowLavaDoesNotOverlapTheEmptyPartOfItsCell() {
        PartialFluidProbe probe = new PartialFluidProbe();
        BlockPos lava = new BlockPos(0, 65, 0);
        probe.lava.add(lava);
        probe.bounds.put(lava, new AABB(0, 65, 0, 1, 65.25, 1));
        AABB above = new AABB(.2, 65.3, .2, .8, 67.1, .8);

        assertFalse(HighwayLavaSafety.intersects(above, Vec3.ZERO, probe));
        assertTrue(HighwayLavaSafety.intersects(above, new Vec3(0, -.1, 0), probe));
    }

    private static class Probe implements HighwayLavaSafety.Probe {
        final Set<BlockPos> lava = new HashSet<>();
        final Set<BlockPos> unloaded = new HashSet<>();
        int fluidReads;

        @Override public boolean loaded(int chunkX, int chunkZ) {
            return !unloaded.contains(new BlockPos(chunkX, 0, chunkZ));
        }

        @Override public boolean lava(BlockPos pos) {
            if (unloaded.contains(new BlockPos(pos.getX() >> 4, 0, pos.getZ() >> 4))) {
                fail("Fluid queries must never load or read an unloaded chunk.");
            }
            fluidReads++;
            return lava.contains(pos);
        }
    }

    private static final class PartialFluidProbe extends Probe {
        final Map<BlockPos, AABB> bounds = new HashMap<>();

        @Override public AABB lavaBounds(BlockPos pos) {
            return lava(pos) ? bounds.get(pos) : null;
        }
    }
}
