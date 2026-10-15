package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PacketBudgetTest {
    @Test void countsTheLastTwentyTicks() {
        PacketBudget b = new PacketBudget();
        for (int tick = 1; tick <= 20; tick++) b.record(tick, 5);
        assertEquals(100, b.perSecond(20));
        assertEquals(95, b.perSecond(21));
        assertEquals(5, b.perSecond(39));
        assertEquals(0, b.perSecond(40));
    }
    @Test void longGapClearsTheWindow() {
        PacketBudget b = new PacketBudget();
        b.record(1, 50);
        b.record(500, 3);
        assertEquals(3, b.perSecond(500));
    }
    @Test void tickCounterResetClearsTheWindow() {
        PacketBudget b = new PacketBudget();
        b.record(100, 50);
        b.record(1, 2);
        assertEquals(2, b.perSecond(1));
        b.reset();
        assertEquals(0, b.perSecond(1));
    }
    @Test void allowsUpToTheLimitIncludingPlannedCost() {
        PacketBudget b = new PacketBudget();
        b.record(1, 140);
        assertTrue(b.allows(1, 10, 150));
        assertFalse(b.allows(1, 11, 150));
    }
    @Test void placementCostCountsEachExtra() {
        assertEquals(2, PacketBudget.placementCost(0, false, false));
        assertEquals(4, PacketBudget.placementCost(2, false, false));
        assertEquals(5, PacketBudget.placementCost(2, true, false));
        assertEquals(7, PacketBudget.placementCost(2, true, true));
    }
}
