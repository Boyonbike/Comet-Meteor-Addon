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
    @Test void noSpares() {
        assertEquals(-1, ElytraReplacement.best(new int[] {-1, -1}, 10));
        assertEquals(-1, ElytraReplacement.best(new int[0], 10));
    }
    @Test void strandedWhenLowWithNoSpare() {
        assertTrue(ElytraReplacement.stranded(20, new int[] {-1, -1}, 20, 20));
        assertFalse(ElytraReplacement.stranded(21, new int[] {-1, -1}, 20, 20));
    }
    @Test void aSpareThatWillBeSwappedInIsNotStranded() {
        // elytra-replace at 10 swaps the 15 in later, even though 15 is under the log-out threshold.
        assertFalse(ElytraReplacement.stranded(20, new int[] {-1, 15}, 20, 10));
    }
    @Test void sparesAtOrBelowTheSpareThresholdDoNotCount() {
        assertTrue(ElytraReplacement.stranded(5, new int[] {8, 10}, 5, 10));
    }
    @Test void nothingWornIsNeverStranded() {
        assertFalse(ElytraReplacement.stranded(-1, new int[0], 20, 20));
    }
}
