package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketPositionTest {
    @Test void relativeMovementRoundsFullPrecisionBasesBeforeAddingDeltas() {
        var base = new PacketPosition(1.0001, -1.0001, -1.0002);
        var moved = base.relativeMove(1, -1, 1);

        assertEquals(new PacketPosition(1.000244140625, -1.000244140625, -1), moved);
        assertNotEquals(base.x() + 1.0 / 4096, moved.x());
        assertNotEquals(base.y() - 1.0 / 4096, moved.y());
    }

    @Test void zeroDeltasPreserveFullPrecisionAndSignedZero() {
        var base = new PacketPosition(12.3456789, -0.0, -98.7654321);

        assertEquals(base, base.relativeMove(0, 0, 0));
        var moved = base.relativeMove(0, 1, 0);
        assertEquals(base.x(), moved.x());
        assertEquals(base.z(), moved.z());
        assertEquals(0.000244140625, moved.y());
    }

    @Test void halfStepRoundingMatchesJavaForBothSigns() {
        var base = new PacketPosition(0.0001220703125, -0.0001220703125, 0);

        assertEquals(new PacketPosition(0.00048828125, -0.000244140625, 0),
            base.relativeMove(1, -1, 0));
    }

    @Test void consecutiveMovesUseTheLastDecodedPosition() {
        var moved = new PacketPosition(1.0001, -1.0001, 0)
            .relativeMove(1, -1, 0)
            .relativeMove(1, -1, 0);

        assertEquals(new PacketPosition(1.00048828125, -1.00048828125, 0), moved);
    }

    @Test void teleportResolvesEachAxisIndependentlyWithoutQuantizing() {
        var base = new PacketPosition(10.125, 64.25, -20.5);
        var change = new PacketPosition(2.00001, 100.123456, -3.00001);

        assertEquals(new PacketPosition(12.12501, 100.123456, -23.50001),
            base.teleport(change, true, false, true));
        assertEquals(new PacketPosition(2.00001, 164.373456, -3.00001),
            base.teleport(change, false, true, false));
    }

    @Test void absoluteTeleportPreservesFarCoordinatesExactly() {
        var destination = new PacketPosition(29_999_999.123456, -63.875, -29_999_999.654321);

        assertEquals(destination, new PacketPosition(0, 64, 0)
            .teleport(destination, false, false, false));
    }

    @Test void rejectsNonFiniteCoordinatesAndTeleportOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new PacketPosition(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PacketPosition(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new PacketPosition(0, 0, Double.NEGATIVE_INFINITY));
        var largest = new PacketPosition(Double.MAX_VALUE, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> largest.teleport(largest, true, false, false));
    }
}
