package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PassageClearanceTest {
    private static PassageClearance.Cell cell(int x, int y, int z) {
        return new PassageClearance.Cell(x, y, z);
    }

    @Test void miningProtectsBothBodyCellsButExpiresWhenAbandoned() {
        PassageClearance clearance = new PassageClearance();
        clearance.begin(cell(2, 64, 0), 64, 10);
        assertTrue(clearance.protects(cell(2, 64, 0)));
        assertTrue(clearance.protects(cell(2, 65, 0)));
        assertFalse(clearance.protects(cell(2, 63, 0)));
        clearance.observe(.3, .3, 10 + PassageClearance.MINING_TICKS + 1);
        assertEquals(0, clearance.size());
    }

    @Test void removedColumnWaitsForEntryThenReleasesBehindPlayer() {
        PassageClearance clearance = new PassageClearance();
        var target = cell(2, 64, 0);
        clearance.begin(target, 64, 0);
        clearance.removed(target, 1);
        clearance.observe(.5, .5, 20);
        assertTrue(clearance.protects(target));
        clearance.observe(2.2, .5, 21);
        assertTrue(clearance.protects(target));
        clearance.observe(3.4, .5, 22);
        assertFalse(clearance.protects(target));
    }

    @Test void removedColumnEventuallyExpiresIfPlayerNeverEnters() {
        PassageClearance clearance = new PassageClearance();
        var target = cell(2, 65, 0);
        clearance.begin(target, 64, 0);
        clearance.removed(target, 1);
        clearance.observe(.3, .3, 1 + PassageClearance.APPROACH_TICKS + 1);
        assertEquals(0, clearance.size());
    }

    @Test void adjacentClearancesAreIndependent() {
        PassageClearance clearance = new PassageClearance();
        var first = cell(1, 64, 0);
        var second = cell(2, 64, 0);
        clearance.begin(first, 64, 0);
        clearance.begin(second, 64, 0);
        clearance.removed(first, 1);
        clearance.removed(second, 1);
        clearance.observe(1.2, .5, 2);
        clearance.observe(2.2, .5, 3);
        assertFalse(clearance.protects(first));
        assertTrue(clearance.protects(second));
    }
}
