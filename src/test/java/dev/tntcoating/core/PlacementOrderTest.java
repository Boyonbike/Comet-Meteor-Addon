package dev.tntcoating.core;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlacementOrderTest {
    @org.junit.jupiter.api.Test void nearbyRetryCanPrecedeUntouchedDistantTarget() {
        var candidates = java.util.List.of(7, 2, 5, 1);
        assertEquals(java.util.List.of(1,2,5,7), PlacementOrder.nearFirst(candidates, java.util.Comparator.naturalOrder()));
    }

    @Test void unattemptedSurfacesComeBeforeAFailedNearTarget() {
        assertEquals(List.of(3, 2, 1), PlacementOrder.order(List.of(1, 2, 3), Map.of(1, 5), Comparator.reverseOrder()));
    }
    @Test void oldestRetryComesBeforeRecentRetries() {
        assertEquals(List.of(2, 3, 1), PlacementOrder.order(List.of(1, 2, 3), Map.of(1, 10, 2, 2, 3, 5), Comparator.naturalOrder()));
    }
    @Test void distancePriorityAppliesToUntouchedTargets() {
        assertEquals(List.of(3, 2, 1), PlacementOrder.order(List.of(1, 3, 2), Map.of(), Comparator.reverseOrder()));
        assertEquals(List.of(1, 2, 3), PlacementOrder.order(List.of(1, 3, 2), Map.of(), Comparator.naturalOrder()));
    }
    @Test void aRepeatedlyCancelledTargetCannotMonopoliseThePlacementBudget() {
        Map<Integer, Integer> history = new HashMap<>();
        var candidates = List.of(1, 2, 3, 4, 5, 6);
        var visited = new java.util.HashSet<Integer>();
        for (int tick = 0; tick < 6; tick++) {
            int target = PlacementOrder.order(candidates, history, Comparator.naturalOrder()).getFirst();
            visited.add(target);
            history.put(target, tick); // Even a cancellation consumes this target's opportunity.
        }
        assertEquals(6, visited.size());
    }
}
