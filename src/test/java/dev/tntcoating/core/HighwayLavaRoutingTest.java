package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HighwayLavaRoutingTest {
    private static final Vec3 START = new Vec3(.5, 65, .5);
    private static final Vec3 FORWARD = new Vec3(0, 0, 1);
    private static final AABB FLYING_BODY = new AABB(.2, 65, .2, .8, 65.6, .8);

    @Test
    void fliesAboveAFullWidthLavaWallWithoutAnySolidCollisionShapes() {
        LavaField lava = lowLavaWall();
        HighwayPathfinder.Config config = config(66);

        HighwayPathfinder.Path path = HighwayPathfinder.find(FLYING_BODY, START, FORWARD, 65,
            config, lava.corridor());

        assertTrue(path.found());
        assertEquals(16, path.progress(), "The route must cross the lava and reach its full horizon");
        assertEquals(32.5, path.points().getLast().z, 1e-7);
        assertTrue(path.points().stream().anyMatch(point -> point.z >= 8 && point.z <= 12 && point.y == 66),
            "Lava fills every permitted lateral cell, so the route must fly above it");
        assertTrue(path.points().stream().allMatch(point -> point.y >= 65 && point.y <= 66));
        assertSafeEdges(path.points(), lava);
        assertTrue(lava.fluidReads > 0, "The planner must check real fluid cells, not just solid boxes");
    }

    @Test
    void cannotEscapeTheMaximumAltitudeToCrossLava() {
        LavaField lava = lowLavaWall();

        HighwayPathfinder.Path path = HighwayPathfinder.find(FLYING_BODY, START, FORWARD, 65,
            config(65), lava.corridor());

        assertTrue(path.found(), "A safe partial approach is still useful");
        assertTrue(path.progress() < 16, "A bounded path cannot cross this wall at its allowed altitude");
        assertTrue(path.points().stream().allMatch(point -> point.y == 65));
        assertTrue(path.points().stream().allMatch(point -> point.z + .3 <= 8),
            "The whole body must remain before the lava");
        assertSafeEdges(path.points(), lava);
    }

    @Test
    void stopsItsPlanBeforeUnloadedChunksWithoutReadingTheirFluids() {
        LavaField lava = new LavaField();
        lava.unloaded.add(new BlockPos(-1, 0, 1));
        lava.unloaded.add(new BlockPos(0, 0, 1));
        lava.lava.add(new BlockPos(0, 65, 17));

        HighwayPathfinder.Path path = HighwayPathfinder.find(FLYING_BODY, START, FORWARD, 65,
            config(66), lava.corridor());

        assertTrue(path.found());
        assertTrue(path.progress() < 16, "Planning must not cross the unloaded chunk boundary");
        assertTrue(path.points().stream().allMatch(point -> point.z + .3 <= 16));
        assertTrue(lava.fluidReads > 0, "Loaded fluid cells should still be inspected");
        assertEquals(0, lava.unloadedFluidReads);
        assertSafeEdges(path.points(), lava);
    }

    @Test
    void keepsAnElevatedCommitmentUntilReturningToCruiseAltitudeIsClear() {
        LavaField lava = lowLavaWall();
        FlightCorridor.Probe corridor = lava.corridor();
        HighwayNavigation navigation = new HighwayNavigation();
        assertTrue(navigation.commit(new HighwayPathfinder.Path(List.of(
            new Vec3(.5, 66, 8.5), new Vec3(.5, 66, 14.5), new Vec3(.5, 66, 32.5)),
            HighwayPathfinder.Reason.FOUND, 12)));
        Vec3 abovePool = new Vec3(.5, 66, 9.5);
        AABB abovePoolBody = bodyAt(abovePool);

        assertEquals(FlightCorridor.Reason.CLEAR,
            FlightCorridor.inspect(abovePoolBody, FORWARD, 16, corridor).reason(),
            "Horizontal clearance above lava alone must not release the elevated route");
        assertFalse(HighwayDetour.clearAt(abovePoolBody, 65, 0, 16, corridor));
        for (int tick = 1; tick <= 12; tick++) {
            boolean returnClear = HighwayDetour.clearAt(abovePoolBody, 65, 0, 16, corridor);
            HighwayNavigation.Step step = navigation.update(tick, abovePool, abovePoolBody, FORWARD,
                returnClear, 8, corridor);
            assertTrue(step.followingPath(), "The body cannot descend while it is above the pool");
            assertFalse(step.needsPath());
            assertEquals(66, step.target().y, 1e-7);
        }

        Vec3 pastPool = new Vec3(.5, 66, 14.5);
        AABB pastPoolBody = bodyAt(pastPool);
        assertTrue(HighwayDetour.clearAt(pastPoolBody, 65, 0, 16, corridor));
        for (int tick = 13; tick <= 17; tick++) {
            assertTrue(navigation.update(tick, pastPool, pastPoolBody, FORWARD,
                HighwayDetour.clearAt(pastPoolBody, 65, 0, 16, corridor), 8, corridor).followingPath());
        }
        HighwayNavigation.Step released = navigation.update(18, pastPool, pastPoolBody, FORWARD,
            HighwayDetour.clearAt(pastPoolBody, 65, 0, 16, corridor), 8, corridor);
        assertFalse(released.followingPath(), "Cruise may resume after sustained safe descent clearance");
        assertFalse(released.needsPath());
    }

    private static HighwayPathfinder.Config config(double maxY) {
        return new HighwayPathfinder.Config(5, 3, 16, 2, 1, 1, 65, maxY);
    }

    private static LavaField lowLavaWall() {
        LavaField field = new LavaField();
        for (int x = -4; x <= 4; x++) {
            for (int z = 8; z <= 11; z++) field.lava.add(new BlockPos(x, 65, z));
        }
        return field;
    }

    private static AABB bodyAt(Vec3 position) {
        return FLYING_BODY.move(position.subtract(START));
    }

    private static void assertSafeEdges(List<Vec3> points, LavaField lava) {
        for (int index = 0; index < points.size() - 1; index++) {
            Vec3 start = points.get(index);
            Vec3 delta = points.get(index + 1).subtract(start);
            AABB body = bodyAt(start);
            assertFalse(HighwayLavaSafety.intersects(body, delta, lava),
                "No part of the player's swept body may enter fluid");
            FlightCorridor.Result result = FlightCorridor.inspect(body, delta, delta.length(), lava.corridor());
            assertEquals(FlightCorridor.Reason.CLEAR, result.reason());
            assertEquals(delta.length(), result.clearDistance(), 1e-7);
        }
    }

    private static final class LavaField implements HighwayLavaSafety.Probe {
        final Set<BlockPos> lava = new HashSet<>();
        final Set<BlockPos> unloaded = new HashSet<>();
        int fluidReads;
        int unloadedFluidReads;

        @Override public boolean loaded(int chunkX, int chunkZ) {
            return !unloaded.contains(new BlockPos(chunkX, 0, chunkZ));
        }

        @Override public boolean lava(BlockPos pos) {
            if (!loaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                unloadedFluidReads++;
                fail("A flight plan must not read fluid from an unloaded chunk");
            }
            fluidReads++;
            return lava.contains(pos);
        }

        FlightCorridor.Probe corridor() {
            return new FlightCorridor.Probe() {
                @Override public boolean loaded(int chunkX, int chunkZ) {
                    return LavaField.this.loaded(chunkX, chunkZ);
                }

                @Override public boolean clear(AABB swept) {
                    return !HighwayLavaSafety.intersects(swept, Vec3.ZERO, LavaField.this);
                }
            };
        }
    }
}
