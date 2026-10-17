package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ScaffoldSupportSelectionTest {
    @Test void emptySearchHasNoResultAndTiesKeepOriginalIterationOrder() {
        var nearest = new ScaffoldSupportSelection(0, 0, 0);
        assertFalse(nearest.found());
        nearest.offer(-1, 0, 0);
        nearest.offer(0, 0, -1);
        nearest.offer(1, 0, 0);
        assertTrue(nearest.found());
        assertEquals(-1, nearest.x());
        assertEquals(0, nearest.z());
    }

    @Test void nearestSelectionMatchesStableSortingIncludingWorldBorderCoordinates() {
        Random random = new Random(0x5CAFF01D);
        for (int origin : new int[] {-29_999_980, -16, 0, 16, 29_999_980}) {
            for (int search = 0; search < 100; search++) {
                int targetY = random.nextInt(400) - 64;
                var nearest = new ScaffoldSupportSelection(origin, targetY, origin);
                var candidates = new ArrayList<Point>();
                for (int x = origin - 8; x < origin + 8; x++) {
                    for (int z = origin - 8; z < origin + 8; z++) {
                        for (int y = targetY - 8; y < targetY + 8; y++) {
                            // Emulate arbitrary support, collision and reach filtering.
                            if (random.nextInt(8) != 0) continue;
                            candidates.add(new Point(x, y, z));
                            nearest.offer(x, y, z);
                        }
                    }
                }
                candidates.sort(Comparator.comparingDouble(p -> p.distance(origin, targetY, origin)));
                Point expected = candidates.getFirst();
                assertEquals(expected, new Point(nearest.x(), nearest.y(), nearest.z()));
            }
        }
    }

    private record Point(int x, int y, int z) {
        double distance(int targetX, int targetY, int targetZ) {
            double dx = (double) x - targetX, dy = (double) y - targetY, dz = (double) z - targetZ;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
