package dev.tntcoating.core;

import dev.tntcoating.core.SmartElytraSwap.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmartElytraSwapTest {
    @Test void findsWingsInMainInventoryInsteadOfOnlyTheHotbar() {
        assertEquals(30, SmartElytraSwap.best(List.of(
            new Candidate(0, false, 528, 8, 3), new Candidate(30, true, 400, 0, 0)), true));
    }

    @Test void takesTheMostDurableWingsIncludingOffhand() {
        assertEquals(40, SmartElytraSwap.best(List.of(
            new Candidate(2, true, 10, 0, 0), new Candidate(22, true, 300, 0, 0),
            new Candidate(40, true, 432, 0, 0)), true));
    }

    @Test void brokenWingsCannotBeSelected() {
        assertEquals(-1, SmartElytraSwap.best(List.of(
            new Candidate(0, true, 1, 0, 0), new Candidate(20, true, 0, 0, 0)), true));
        assertEquals(20, SmartElytraSwap.best(List.of(new Candidate(20, true, 2, 0, 0)), true));
    }

    @Test void missingGearLeavesCurrentEquipmentAlone() {
        assertEquals(-1, SmartElytraSwap.best(List.of(), true));
        assertEquals(-1, SmartElytraSwap.best(List.of(new Candidate(5, true, 400, 0, 0)), false));
    }

    @Test void prefersChestplateArmorBeforeDurability() {
        assertEquals(25, SmartElytraSwap.best(List.of(
            new Candidate(0, false, 200, 5, 0), new Candidate(25, false, 20, 8, 2)), false));
    }

    @Test void prefersToughnessThenDurabilityForMatchingArmor() {
        assertEquals(20, SmartElytraSwap.best(List.of(
            new Candidate(0, false, 400, 8, 2), new Candidate(19, false, 20, 8, 3),
            new Candidate(20, false, 300, 8, 3)), false));
    }

    @Test void unbreakableWingsRemainUsable() {
        assertEquals(12, SmartElytraSwap.best(List.of(
            new Candidate(12, true, Integer.MAX_VALUE, 0, 0), new Candidate(0, true, 432, 0, 0)), true));
    }

    @Test void keepsSelectionStableForIdenticalItems() {
        assertEquals(3, SmartElytraSwap.best(List.of(
            new Candidate(3, false, 528, 8, 3), new Candidate(20, false, 528, 8, 3)), false));
    }
}
