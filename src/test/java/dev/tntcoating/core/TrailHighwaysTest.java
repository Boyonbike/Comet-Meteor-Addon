package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrailHighwaysTest {
    @Test void axesAndDiagonalsAreHighwaysAwayFromSpawn() {
        assertTrue(TrailHighways.near(5_000 / 16, 100 / 16), "+x axis");
        assertTrue(TrailHighways.near(-120 / 16, -40_000 / 16), "-z axis");
        assertTrue(TrailHighways.near(20_000 / 16, -20_100 / 16), "a diagonal");
        assertTrue(TrailHighways.near(-30_000 / 16, -29_800 / 16), "the opposite diagonal");
        assertFalse(TrailHighways.near(5_000 / 16, 600 / 16), "600 blocks off the axis");
        assertFalse(TrailHighways.near(20_000 / 16, -15_000 / 16), "between highways");
        assertFalse(TrailHighways.near(0, 0), "spawn is not a highway");
    }

    @Test void largeCoordinatesDoNotOverflow() {
        assertTrue(TrailHighways.near(Integer.MAX_VALUE / 2, 0));
        assertFalse(TrailHighways.near(Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 4));
    }
}
