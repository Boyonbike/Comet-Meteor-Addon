package dev.tntcoating.core;

import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlacementOrderTest {
    @Test void nearbyRetryCanPrecedeUntouchedDistantTarget() {
        var candidates = List.of(7, 2, 5, 1);
        assertEquals(List.of(1, 2, 5, 7), PlacementOrder.nearFirst(candidates, Comparator.naturalOrder()));
    }
}
