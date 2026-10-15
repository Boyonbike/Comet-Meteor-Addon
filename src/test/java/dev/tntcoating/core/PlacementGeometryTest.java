package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.tntcoating.core.PlacementGeometry.*;

class PlacementGeometryTest {
    @Test void movingOneBlockOnlyAddsOneSlabAtRadiusFive() {
        int entering = 0, retained = 0;
        for (int x=-4;x<=6;x++) for (int y=-5;y<=5;y++) for (int z=-5;z<=5;z++) {
            if (inCube(x,y,z,0,0,0,5)) retained++; else entering++;
        }
        assertEquals(121,entering);
        assertEquals(1331,entering+retained);
    }

    @Test void cubeIncludesDiagonalCornersAndAllSixBoundaries() {
        assertTrue(inCube(5, 5, 5, 0, 0, 0, 5));
        assertTrue(inCube(-5, -5, -5, 0, 0, 0, 5));
        assertFalse(inCube(6, 0, 0, 0, 0, 0, 5));
        assertFalse(inCube(0, -6, 0, 0, 0, 0, 5));
        assertFalse(inCube(0, 0, 6, 0, 0, 0, 5));
    }

    @Test void radiusFiveHasElevenCubedTargetCellsAtAnyOrigin() {
        int count = 0;
        for (int x = -10; x <= 10; x++) for (int y = -10; y <= 10; y++) for (int z = -10; z <= 10; z++) {
            if (inCube(x + 20, y - 64, z + 8, 20, -64, 8, 5)) count++;
        }
        assertEquals(1331, count);
    }

    @Test void everySampleLiesOnTheRequestedFaceIncludingPartialBlocks() {
        Box box = new Box(10, 64, -5, 11, 64.5, -4);
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[] {-1, 1}) {
            for (Point p : faceSamples(box, axis, sign, new Point(12, 66, -3))) {
                double actual = axis == 0 ? p.x() : axis == 1 ? p.y() : p.z();
                double expected = axis == 0 ? (sign > 0 ? 11 : 10) : axis == 1 ? (sign > 0 ? 64.5 : 64) : (sign > 0 ? -4 : -5);
                assertEquals(expected, actual);
                assertTrue(p.x() >= 10 && p.x() <= 11 && p.y() >= 64 && p.y() <= 64.5 && p.z() >= -5 && p.z() <= -4);
            }
        }
    }

    @Test void alternativesRemainWhenTheFaceCentreIsOccluded() {
        var samples = faceSamples(new Box(0, 0, 0, 1, 1, 1), 1, 1, new Point(.5, 2, .5));
        assertEquals(new Point(.5, 1, .5), samples.getFirst());
        assertTrue(samples.stream().anyMatch(p -> p.x() < .2));
        assertTrue(samples.stream().anyMatch(p -> p.x() > .8));
        assertTrue(samples.stream().anyMatch(p -> p.z() < .2));
        assertTrue(samples.stream().anyMatch(p -> p.z() > .8));
    }

    @Test void samplesReachNarrowChestEdgeGapsWithoutLeavingFace() {
        var samples = faceSamples(new Box(0,0,0,1,1,1), 1, 1, new Point(.5,2,.5), true);
        assertTrue(samples.stream().anyMatch(p -> p.x() > 0 && p.x() < 1.0 / 16 && p.z() == .5));
        assertTrue(samples.stream().anyMatch(p -> p.z() < 1 && p.z() > 15.0 / 16 && p.x() == .5));
        assertEquals(samples.size(), samples.stream().distinct().count());
    }

    @Test void normalTargetingAvoidsExtremeEdgesByDefault() {
        var samples = faceSamples(new Box(0,0,0,1,1,1), 1, 1, new Point(.5,2,.5));
        assertTrue(samples.stream().allMatch(p -> p.x() >= .12 && p.x() <= .88 && p.z() >= .12 && p.z() <= .88));
        assertTrue(samples.size() <= 10);
    }

    @Test void nearestPointIsTriedBeforeTheCentreForReachAtAnEdge() {
        var samples = faceSamples(new Box(0, 0, 0, 1, 1, 1), 1, 1, new Point(2, 4, .5));
        assertEquals(new Point(.88, 1, .5), samples.getFirst());
    }
}
