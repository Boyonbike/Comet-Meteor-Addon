package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class NearestFirstTest {
    @Test void emptyAndSingleListsAreUnchangedAndTiesKeepTheEarliest() {
        var empty = new ArrayList<Point>();
        NearestFirst.moveMinimumToFront(empty, byDistance(0, 0, 0));
        assertTrue(empty.isEmpty());
        var ties = new ArrayList<>(List.of(new Point(-1, 0, 0), new Point(0, 0, -1), new Point(1, 0, 0)));
        NearestFirst.moveMinimumToFront(ties, byDistance(0, 0, 0));
        assertEquals(new Point(-1, 0, 0), ties.getFirst());
    }

    @Test void firstElementMatchesStableSortingIncludingWorldBorderCoordinates() {
        Random random = new Random(0x5CAFF01D);
        for (int origin : new int[] {-29_999_980, -16, 0, 16, 29_999_980}) {
            for (int search = 0; search < 100; search++) {
                int targetY = random.nextInt(400) - 64;
                var candidates = new ArrayList<Point>();
                for (int x = origin - 8; x < origin + 8; x++) {
                    for (int z = origin - 8; z < origin + 8; z++) {
                        for (int y = targetY - 8; y < targetY + 8; y++) {
                            // Emulate arbitrary support, collision and reach filtering.
                            if (random.nextInt(8) == 0) candidates.add(new Point(x, y, z));
                        }
                    }
                }
                var sorted = new ArrayList<>(candidates);
                sorted.sort(byDistance(origin, targetY, origin));
                NearestFirst.moveMinimumToFront(candidates, byDistance(origin, targetY, origin));
                assertEquals(sorted.getFirst(), candidates.getFirst());
            }
        }
    }

    private static Comparator<Point> byDistance(int x, int y, int z) {
        return Comparator.comparingDouble(point -> point.distance(x, y, z));
    }

    private record Point(int x, int y, int z) {
        double distance(int targetX, int targetY, int targetZ) {
            double dx = (double) x - targetX, dy = (double) y - targetY, dz = (double) z - targetZ;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
