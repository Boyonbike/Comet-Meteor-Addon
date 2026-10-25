package dev.tntcoating.core;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalLookaheadTest {
    private static final AABB BODY = new AABB(.2, 65, .2, .8, 66.8, .8);

    @Test void findsTheNearestPortalAlongTheFlight() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(3, 65, 0));
        probe.portals.add(new BlockPos(6, 65, 0));

        var hit = PortalLookahead.first(BODY, new Vec3(1, 0, 0), 10, probe);

        assertTrue(hit.isPresent());
        assertEquals(2.2, hit.get().distance(), 1e-6);
        assertEquals(new BlockPos(3, 65, 0), hit.get().pos());
    }

    @Test void ignoresPortalsBesideADiagonalSweep() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(0, 120, 3));
        AABB body = new AABB(-.3, 120, -.3, .3, 120.6, .3);

        assertTrue(PortalLookahead.first(body, new Vec3(1, 0, 1), 16, probe).isEmpty());
    }

    @Test void findsDiagonalPortalAtTheFirstBodyContact() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(3, 120, 3));
        AABB body = new AABB(-.3, 120, -.3, .3, 120.6, .3);

        var hit = PortalLookahead.first(body, new Vec3(1, 0, 1), 16, probe);

        assertTrue(hit.isPresent());
        assertEquals(2.7 * Math.sqrt(2), hit.get().distance(), 1e-6);
    }

    @Test void ignoresPortalsOutsideTheBodyHeight() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(3, 67, 0));
        assertTrue(PortalLookahead.first(BODY, new Vec3(1, 0, 0), 10, probe).isEmpty());
    }

    @Test void ignoresPortalsBehindTheBodyAndOutsideTheLookahead() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(-3, 65, 0));
        probe.portals.add(new BlockPos(20, 65, 0));

        assertTrue(PortalLookahead.first(BODY, new Vec3(1, 0, 0), 10, probe).isEmpty());
    }

    @Test void ignoresPortalsInUnloadedChunks() {
        Probe probe = new Probe();
        probe.loaded = false;
        probe.portals.add(new BlockPos(3, 65, 0));

        assertTrue(PortalLookahead.first(BODY, new Vec3(1, 0, 0), 10, probe).isEmpty());
        assertFalse(PortalLookahead.contains(BODY, probe));
    }

    @Test void containsDetectsAPortalIntersectingTheBody() {
        Probe probe = new Probe();
        probe.portals.add(new BlockPos(0, 65, 0));

        assertTrue(PortalLookahead.contains(BODY, probe));
    }

    @Test void rejectsInvalidInputs() {
        Probe probe = new Probe();
        assertThrows(IllegalArgumentException.class,
            () -> PortalLookahead.first(BODY, new Vec3(1, 0, 0), 0, probe));
        assertThrows(IllegalArgumentException.class,
            () -> PortalLookahead.first(BODY, new Vec3(0, 0, 0), 10, probe));
        assertThrows(NullPointerException.class,
            () -> PortalLookahead.first(null, new Vec3(1, 0, 0), 10, probe));
    }

    private static final class Probe implements PortalLookahead.Probe {
        final Set<BlockPos> portals = new HashSet<>();
        boolean loaded = true;

        @Override public boolean loaded(int chunkX, int chunkZ) { return loaded; }
        @Override public boolean portal(BlockPos pos) { return portals.contains(pos); }
    }
}