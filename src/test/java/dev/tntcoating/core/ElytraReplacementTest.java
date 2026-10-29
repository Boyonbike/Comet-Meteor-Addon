package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ElytraReplacementTest {
    @Test void dueAtOrBelowThreshold() {
        assertTrue(ElytraReplacement.due(10, 10));
        assertTrue(ElytraReplacement.due(1, 10));
        assertFalse(ElytraReplacement.due(11, 10));
    }
    @Test void brokenElytraIsDueAtTheLowestThreshold() {
        assertTrue(ElytraReplacement.due(1, 1));
    }
    @Test void nothingWornIsNeverDue() {
        assertFalse(ElytraReplacement.due(-1, 10));
    }
    @Test void picksTheSpareWithMostDurability() {
        assertEquals(2, ElytraReplacement.best(new int[] {-1, 200, 432, 300}, 10));
    }
    @Test void firstOfEquallyGoodSparesWins() {
        assertEquals(1, ElytraReplacement.best(new int[] {-1, 432, 432}, 10));
    }
    @Test void sparesAtOrBelowThresholdAreNotSwappedIn() {
        assertEquals(-1, ElytraReplacement.best(new int[] {10, 5, 1}, 10));
        assertEquals(1, ElytraReplacement.best(new int[] {10, 11}, 10));
    }
    @Test void everyPartiallyUsedSpareAboveMinimumCanBeReequipped() {
        for (int remaining = 11; remaining < 432; remaining++) {
            int[] spares = {-1, 10, remaining, 1};
            assertEquals(2, ElytraReplacement.best(spares, 10), "remaining=" + remaining);
            assertFalse(ElytraReplacement.stranded(10, spares, 10, 10));
        }
    }
    @Test void noSpares() {
        assertEquals(-1, ElytraReplacement.best(new int[] {-1, -1}, 10));
        assertEquals(-1, ElytraReplacement.best(new int[0], 10));
    }
    @Test void strandedWhenLowWithNoSpare() {
        assertTrue(ElytraReplacement.stranded(20, new int[] {-1, -1}, 20, 20));
        assertFalse(ElytraReplacement.stranded(21, new int[] {-1, -1}, 20, 20));
    }
    @Test void aSpareAboveTheLogOutThresholdIsNotStrandedWithinTheWarningMargin() {
        // Warning at 20 with a log-out at 10: the 15 can still be put on before logging out.
        assertFalse(ElytraReplacement.stranded(20, new int[] {-1, 15}, 20, 10));
    }
    @Test void sparesAtOrBelowTheSpareThresholdDoNotCount() {
        assertTrue(ElytraReplacement.stranded(5, new int[] {8, 10}, 5, 10));
    }
    @Test void nothingWornIsNeverStranded() {
        assertFalse(ElytraReplacement.stranded(-1, new int[0], 20, 20));
    }
    @Test void usableCountExcludesItemsAtOrBelowTheConfiguredThreshold() {
        assertEquals(3, ElytraReplacement.countUsable(new int[] {-1, 1, 9, 10, 11, 432, Integer.MAX_VALUE}, 10));
        assertEquals(1, ElytraReplacement.countUsable(new int[] {1, 2}, 1));
        assertEquals(0, ElytraReplacement.countUsable(new int[0], 10));
    }
}
