package dev.tntcoating.core;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SweptBlockIntersectionTest {
    private static final AABB BODY = new AABB(-.3, 120, -.3, .3, 120.6, .3);

    @Test void reportsFractionForNegativeTravel() {
        var hit = SweptBlockIntersection.entryFraction(BODY, new Vec3(-10, 0, 0),
            new AABB(-4, 120, 0, -3, 121, 1));
        assertTrue(hit.isPresent());
        assertEquals(.27, hit.getAsDouble(), 1e-9);
    }

    @Test void stationaryBodyOnlyHitsAnAlreadyOverlappingBlock() {
        assertEquals(0, SweptBlockIntersection.entryFraction(BODY, Vec3.ZERO,
            new AABB(0, 120, 0, 1, 121, 1)).orElseThrow(), 1e-9);
        assertTrue(SweptBlockIntersection.entryFraction(BODY, Vec3.ZERO,
            new AABB(0, 121, 0, 1, 122, 1)).isEmpty());
    }

    @Test void grazingAnObstacleFaceDoesNotCountAsEnteringIt() {
        assertTrue(SweptBlockIntersection.entryFraction(BODY, new Vec3(10, 0, 0),
            new AABB(3, 120.6, 0, 4, 121.6, 1)).isEmpty());
    }

    @Test void touchingOnlyAtTheEndOfTheSweepDoesNotOverlap() {
        assertTrue(SweptBlockIntersection.entryFraction(BODY, new Vec3(2.7, 0, 0),
            new AABB(3, 120, 0, 4, 121, 1)).isEmpty());
    }
}
