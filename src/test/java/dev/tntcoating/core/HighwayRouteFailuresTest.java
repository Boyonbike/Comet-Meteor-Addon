package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HighwayRouteFailuresTest {
    @Test
    void excludesOnlyNearbyApproachUntilExpiryAndCanClearWorldState() {
        HighwayRouteFailures failures = new HighwayRouteFailures();
        failures.record(new Vec3(2, 64, 10), 20);
        assertTrue(failures.blocked(body(2, 64, 10), 21));
        assertFalse(failures.blocked(body(-2, 64, 10), 21));
        assertFalse(failures.blocked(body(2, 66, 10), 21));
        assertFalse(failures.blocked(body(2, 64, 10), 220));
        failures.record(new Vec3(2, 64, 10), 221);
        failures.clear();
        assertFalse(failures.blocked(body(2, 64, 10), 222));
    }

    @Test
    void boundsHistoryAndRefreshesRepeatedFailures() {
        HighwayRouteFailures failures = new HighwayRouteFailures();
        for (int i = 0; i < 9; i++) failures.record(new Vec3(i * 4, 64, 10), i);
        assertFalse(failures.blocked(body(0, 64, 10), 10));
        assertTrue(failures.blocked(body(32, 64, 10), 10));
        failures.record(new Vec3(32, 64, 10), 100);
        assertTrue(failures.blocked(body(32, 64, 10), 250));
        assertFalse(failures.blocked(body(32, 64, 10), 300));
    }

    private static AABB body(double x, double y, double z) {
        return new AABB(x - .3, y, z - .3, x + .3, y + 1.8, z + .3);
    }
}
