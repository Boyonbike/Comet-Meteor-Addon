package dev.tntcoating.modules.autowasp;

import dev.tntcoating.modules.autowasp.AutoWaspChestSwap.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWaspChestSwapTest {
    @Test void findsWingsInMainInventoryInsteadOfOnlyTheHotbar() {
        assertEquals(30, AutoWaspChestSwap.best(List.of(
            new Candidate(0, false, 528, 8, 3), new Candidate(30, true, 400, 0, 0)), true));
    }

    @Test void takesTheMostDurableWingsIncludingOffhand() {
        assertEquals(40, AutoWaspChestSwap.best(List.of(
            new Candidate(2, true, 10, 0, 0), new Candidate(22, true, 300, 0, 0),
            new Candidate(40, true, 432, 0, 0)), true));
    }

    @Test void wornOutWingsCannotBeSelected() {
        assertEquals(-1, AutoWaspChestSwap.best(List.of(
            new Candidate(0, true, 1, 0, 0), new Candidate(20, true, 0, 0, 0)), true));
        assertEquals(20, AutoWaspChestSwap.best(List.of(new Candidate(20, true, 2, 0, 0)), true));
    }

    @Test void prefersChestplateArmorThenToughnessThenDurability() {
        assertEquals(25, AutoWaspChestSwap.best(List.of(
            new Candidate(0, false, 200, 5, 0), new Candidate(25, false, 20, 8, 2)), false));
        assertEquals(20, AutoWaspChestSwap.best(List.of(
            new Candidate(0, false, 400, 8, 2), new Candidate(19, false, 20, 8, 3),
            new Candidate(20, false, 300, 8, 3)), false));
    }

    @Test void missingGearLeavesCurrentEquipmentAlone() {
        assertEquals(-1, AutoWaspChestSwap.best(List.of(), true));
        assertEquals(-1, AutoWaspChestSwap.best(List.of(new Candidate(5, true, 400, 0, 0)), false));
    }

    @Test void entersOnlyWithinEveryRange() {
        assertTrue(AutoWaspChestSwap.wearChestplate(false, 2.4, .9, 1.2, 2.5, 1, 1.25, 1.5));
        assertFalse(AutoWaspChestSwap.wearChestplate(false, 2.6, .9, 1.2, 2.5, 1, 1.25, 1.5));
        assertFalse(AutoWaspChestSwap.wearChestplate(false, 2.4, 1.1, 1.2, 2.5, 1, 1.25, 1.5));
        assertFalse(AutoWaspChestSwap.wearChestplate(false, 2.4, .9, 1.3, 2.5, 1, 1.25, 1.5));
    }

    @Test void aTargetAtTheEdgeOfARangeDoesNotSwapBackAndForth() {
        boolean wearing = false;
        int swaps = 0;
        // The target hovers either side of the 2.5-block horizontal range.
        for (int tick = 0; tick < 40; tick++) {
            double horizontal = tick % 2 == 0 ? 2.4 : 2.7;
            boolean next = AutoWaspChestSwap.wearChestplate(wearing, horizontal, .5, 0, 2.5, 1, 1.25, 1.5);
            if (next != wearing) swaps++;
            wearing = next;
        }
        assertEquals(1, swaps, "one swap to the chestplate, then it stays on");
        assertFalse(AutoWaspChestSwap.wearChestplate(true, 4.1, .5, 0, 2.5, 1, 1.25, 1.5),
            "moving beyond the range plus hysteresis puts the elytra back on");
    }

    @Test void zeroHysteresisRestoresTheOriginalThresholds() {
        assertFalse(AutoWaspChestSwap.wearChestplate(true, 2.6, .5, 0, 2.5, 1, 1.25, 0));
        assertFalse(AutoWaspChestSwap.wearChestplate(true, 2.6, .5, 0, 2.5, 1, 1.25, -3));
    }
}
