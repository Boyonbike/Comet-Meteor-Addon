/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PacketLedgerTest {
    @Test
    void conservativeAndAnarchyUseOneTwentyTickWindow() {
        PacketLedger conservative = PacketLedger.conservative();
        PacketLedger anarchy = PacketLedger.anarchy();
        assertEquals(60, conservative.capacity());
        assertEquals(100, anarchy.capacity());
        assertEquals(20, conservative.windowTicks());
        assertEquals(20, anarchy.windowTicks());
    }

    @Test
    void acquisitionCountsInsideTheRollingWindowAndExpires() {
        PacketLedger ledger = new PacketLedger(10, 20);
        assertTrue(ledger.tryAcquire(0, 4));
        assertTrue(ledger.tryAcquire(5, 4));
        assertEquals(8, ledger.used(5));
        // Tick 1 bucket (0) is still inside the window ending at 20 only while tick > 0.
        assertTrue(ledger.tryAcquire(20, 2));
        assertEquals(6, ledger.used(20));
        assertEquals(0, ledger.used(41));
    }

    @Test
    void windowBoundaryIsHalfOpen() {
        PacketLedger ledger = new PacketLedger(10, 20);
        assertTrue(ledger.tryAcquire(0, 3));
        assertEquals(3, ledger.used(19));
        assertEquals(0, ledger.used(20));
    }

    @Test
    void refusalDoesNotConsumeCapacity() {
        PacketLedger ledger = new PacketLedger(5, 20);
        assertTrue(ledger.tryAcquire(0, 4));
        assertFalse(ledger.tryAcquire(0, 2));
        assertEquals(4, ledger.used(0));
        assertTrue(ledger.tryAcquire(0, 1));
        assertEquals(5, ledger.used(0));
    }

    @Test
    void rollbackReturnsReservedPackets() {
        PacketLedger ledger = new PacketLedger(5, 20);
        assertTrue(ledger.tryAcquire(0, 5));
        ledger.release(0, 5);
        assertEquals(0, ledger.used(0));
        assertTrue(ledger.tryAcquire(0, 5));
    }

    @Test
    void rollbackClampsAndIgnoresUnknownBuckets() {
        PacketLedger ledger = new PacketLedger(5, 20);
        assertTrue(ledger.tryAcquire(3, 2));
        ledger.release(3, 5);
        assertEquals(0, ledger.used(3));
        ledger.release(3, 1);
        ledger.release(9, 1);
        assertEquals(0, ledger.used(3));
    }

    @Test
    void zeroAcquisitionSucceedsAndNegativeValuesFail() {
        PacketLedger ledger = new PacketLedger(1, 20);
        assertTrue(ledger.tryAcquire(0, 0));
        assertFalse(ledger.tryAcquire(-1, 1));
        assertFalse(ledger.tryAcquire(0, -1));
        assertEquals(0, ledger.used(-1));
    }

    @Test
    void usedPrunesBucketsOutsideTheWindow() {
        PacketLedger ledger = new PacketLedger(4, 3);
        assertTrue(ledger.tryAcquire(0, 4));
        assertEquals(4, ledger.used(2));
        assertFalse(ledger.tryAcquire(2, 1));
        assertEquals(0, ledger.used(3));
        assertTrue(ledger.tryAcquire(3, 4));
    }

    @Test
    void overflowingCapacityIntegerCannotWrap() {
        PacketLedger ledger = new PacketLedger(6, 20);
        assertTrue(ledger.tryAcquire(0, 6));
        assertFalse(ledger.tryAcquire(0, Integer.MAX_VALUE));
        assertEquals(6, ledger.used(0));
    }

    @Test
    void constructorRejectsNonPositiveConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new PacketLedger(0));
        assertThrows(IllegalArgumentException.class, () -> new PacketLedger(1, 0));
    }
}
