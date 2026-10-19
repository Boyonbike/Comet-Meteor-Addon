package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static dev.tntcoating.core.ReplenishPolicy.StackPreference.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplenishPolicyTest {
    @Test void thresholdSupportsSixteenStacksAndSkipsFullOrEmptyStacks() {
        assertTrue(ReplenishPolicy.needsRefill(8, 16, 8, false));
        assertFalse(ReplenishPolicy.needsRefill(16, 16, 63, false));
        assertFalse(ReplenishPolicy.needsRefill(0, 64, 8, false));
        assertFalse(ReplenishPolicy.needsRefill(1, 1, 8, false));
    }

    @Test void smartRefillWaitsForLastItemInsteadOfConfiguredThreshold() {
        assertFalse(ReplenishPolicy.needsRefill(8, 64, 8, true));
        assertFalse(ReplenishPolicy.needsRefill(2, 16, 8, true));
        assertTrue(ReplenishPolicy.needsRefill(1, 16, 8, true));
    }

    @Test void preferencesSearchEntireInventoryRatherThanStoppingAtThreshold() {
        int[] counts = {0, 8, 64, 2, 32};
        assertEquals(1, ReplenishPolicy.chooseSource(0, 5, FirstMatch, i -> true, i -> counts[i]));
        assertEquals(2, ReplenishPolicy.chooseSource(0, 5, FullStacks, i -> true, i -> counts[i]));
        assertEquals(3, ReplenishPolicy.chooseSource(0, 5, SmallStacks, i -> true, i -> counts[i]));
    }

    @Test void rejectedComponentsNamesAndDestinationCannotWinSelection() {
        int[] counts = {64, 63, 32, 2};
        assertEquals(2, ReplenishPolicy.chooseSource(0, 4, FullStacks, i -> i >= 2, i -> counts[i]));
        assertEquals(-1, ReplenishPolicy.chooseSource(0, 4, SmallStacks, i -> false, i -> counts[i]));
    }

    @Test void searchRangeCannotConsumeArmorOffhandOrHotbarUnlessRequested() {
        int[] counts = new int[41];
        counts[0] = counts[36] = counts[40] = 64;
        counts[9] = 2;
        assertEquals(9, ReplenishPolicy.chooseSource(9, 36, FullStacks, i -> true, i -> counts[i]));
        counts[9] = 0;
        assertEquals(-1, ReplenishPolicy.chooseSource(9, 36, FullStacks, i -> true, i -> counts[i]));
    }
}
