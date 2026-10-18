package dev.tntcoating.core;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static dev.tntcoating.core.FlightCorridor.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveSpeedTerrainTest {
    private static final double EPSILON = 1e-10;
    private final AABB body = new AABB(.2, 65, .2, .8, 66.8, .8);
    private final VoxelShape floor = Shapes.box(-2, 64, -2, 12, 65, 2);

    @Test void jumpingThroughTwoBlockTunnelClipsRiseWithoutStoppingHorizontalTravel() {
        VoxelShape ceiling = Shapes.box(-2, 67, -2, 12, 68, 2);
        List<VoxelShape> tunnel = List.of(floor, ceiling);

        // The former un-clipped jump body overlaps this ceiling at its starting position.
        AABB unclipped = new AABB(body.minX, body.minY + 1e-6, body.minZ,
            body.maxX, body.maxY + .42, body.maxZ);
        assertEquals(OBSTACLE, inspect(unclipped, tunnel).reason());
        assertEquals(0, inspect(unclipped, tunnel).clearDistance());

        AABB clipped = AdaptiveSpeedTerrain.sweepBody(body, .42, tunnel);
        assertNotNull(clipped);
        assertEquals(67, clipped.maxY, EPSILON);
        FlightCorridor.Result corridor = inspect(clipped, tunnel);
        assertEquals(CLEAR, corridor.reason());
        assertEquals(8, corridor.clearDistance());
    }

    @Test void unobstructedRiseStillDetectsRaisedBeamAhead() {
        VoxelShape beam = Shapes.box(2, 67, 0, 3, 68, 1);
        AABB sweep = AdaptiveSpeedTerrain.sweepBody(body, .42, List.of(floor));

        assertNotNull(sweep);
        assertEquals(body.maxY + .42, sweep.maxY, EPSILON);
        assertEquals(CLEAR, inspect(body, List.of(floor, beam)).reason(),
            "the raised beam is above the original walking body");
        FlightCorridor.Result corridor = inspect(sweep, List.of(floor, beam));
        assertEquals(OBSTACLE, corridor.reason());
        assertTrue(corridor.clearDistance() <= 1.2);
        assertTrue(corridor.clearDistance() > 1.19);
    }

    @Test void supportingFloorDoesNotClipTheJumpOrHorizontalCorridor() {
        AABB sweep = AdaptiveSpeedTerrain.sweepBody(body, .42, List.of(floor));

        assertNotNull(sweep);
        assertEquals(body.maxY + .42, sweep.maxY, EPSILON);
        assertEquals(body.minY + 1e-6, sweep.minY, EPSILON);
        assertEquals(CLEAR, inspect(sweep, List.of(floor)).reason());
    }

    @Test void nonpositiveMotionKeepsTheBodyAboveItsSupportingFloor() {
        for (double verticalMotion : new double[] {0, -.08, -4, -100}) {
            AABB sweep = AdaptiveSpeedTerrain.sweepBody(body, verticalMotion, List.of(floor));

            assertNotNull(sweep);
            assertEquals(body.minX, sweep.minX);
            assertEquals(body.maxX, sweep.maxX);
            assertEquals(body.minZ, sweep.minZ);
            assertEquals(body.maxZ, sweep.maxZ);
            assertEquals(body.minY + 1e-6, sweep.minY, EPSILON);
            assertEquals(body.maxY, sweep.maxY);
            FlightCorridor.Result corridor = inspect(sweep, List.of(floor));
            assertEquals(CLEAR, corridor.reason());
            assertEquals(8, corridor.clearDistance());
        }
    }

    @Test void unexpectedlyLargeUpwardMotionHasABoundedScanHeight() {
        AABB sweep = AdaptiveSpeedTerrain.sweepBody(body, Double.MAX_VALUE, List.of());

        assertNotNull(sweep);
        assertEquals(body.maxY + 4, sweep.maxY, EPSILON);
    }

    @Test void invalidInputsFailClosedBeforeCollisionIteration() {
        Iterable<VoxelShape> unreadable = () -> { throw new AssertionError("must not inspect invalid input"); };
        for (double verticalMotion : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertNull(AdaptiveSpeedTerrain.sweepBody(body, verticalMotion, unreadable));
        }
        assertNull(AdaptiveSpeedTerrain.sweepBody(null, .42, unreadable));
        assertNull(AdaptiveSpeedTerrain.sweepBody(body, .42, null));
        for (AABB invalid : List.of(
            new AABB(Double.NaN, 65, 0, 1, 67, 1),
            new AABB(0, 65, 0, 0, 67, 1),
            new AABB(0, 65, 0, 17, 67, 1),
            new AABB(0, 65, 0, 1, 65 + 1e-7, 1)
        )) assertNull(AdaptiveSpeedTerrain.sweepBody(invalid, .42, unreadable));

        AABB invalidSweep = AdaptiveSpeedTerrain.sweepBody(body, Double.NaN, unreadable);
        FlightCorridor.Result corridor = inspect(invalidSweep, List.of());
        assertEquals(INVALID, corridor.reason());
        assertEquals(0, corridor.clearDistance());
        assertEquals(0, corridor.chunkChecks());
        assertEquals(0, corridor.collisionChecks());
    }

    private static FlightCorridor.Result inspect(AABB box, List<VoxelShape> obstacles) {
        return FlightCorridor.inspect(box, new Vec3(1, 0, 0), 8, new FlightCorridor.Probe() {
            @Override public boolean loaded(int x, int z) { return true; }
            @Override public boolean clear(AABB swept) {
                VoxelShape query = Shapes.create(swept);
                return obstacles.stream().noneMatch(shape -> Shapes.joinIsNotEmpty(query, shape, BooleanOp.AND));
            }
        });
    }
}
