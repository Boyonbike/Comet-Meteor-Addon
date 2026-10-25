package dev.tntcoating.core;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HighwayCenterTest {
    private static final AABB BODY = new AABB(-.3, 0, -.3, .3, .6, .3);
    private static final Vec3 PERPENDICULAR = new Vec3(1, 0, 0);

    @Test
    void openAreaIsAlreadyCentered() {
        assertEquals(0, HighwayCenter.offset(BODY.move(1, 64, 0), new Vec3(1, 64, 0), PERPENDICULAR, 64,
            new HighwayCenter.Config(4, .5), clearProbe()), .001);
    }

    @Test
    void narrowCorridorPullsAnOffCenterPlayerBack() {
        double offset = HighwayCenter.offset(BODY.move(1, 64, 0), new Vec3(1, 64, 0), PERPENDICULAR, 64,
            new HighwayCenter.Config(4, .5), corridor(-2.5, 2.5));
        assertEquals(-1, offset, .001);
    }

    @Test
    void runEndingAtLimitIsCentered() {
        double offset = HighwayCenter.offset(BODY, new Vec3(0, 64, 0), PERPENDICULAR, 64,
            new HighwayCenter.Config(2, .5), corridor(-.5, 2.5));
        assertEquals(1, offset, .001);
    }

    private static FlightCorridor.Probe clearProbe() {
        return new FlightCorridor.Probe() {
            @Override
            public boolean loaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean clear(AABB sweptSegment) {
                return true;
            }
        };
    }

    private static FlightCorridor.Probe corridor(double minX, double maxX) {
        return new FlightCorridor.Probe() {
            @Override
            public boolean loaded(int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean clear(AABB sweptSegment) {
                return sweptSegment.minX >= minX - 1e-7 && sweptSegment.maxX <= maxX + 1e-7;
            }
        };
    }
}
