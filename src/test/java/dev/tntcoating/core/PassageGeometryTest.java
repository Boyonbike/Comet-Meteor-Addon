package dev.tntcoating.core;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PassageGeometryTest {
    @Test void laneIncludesEveryStraightAndDiagonalBoundaryCrossing() {
        assertEquals(Set.of(new PassageGeometry.Cell(0, 0), new PassageGeometry.Cell(1, 0),
            new PassageGeometry.Cell(2, 0)), Set.copyOf(PassageGeometry.lane(.8, .2, 1, 0, 2)));
        var diagonal = PassageGeometry.lane(.8, .2, 1, 1, 2);
        assertTrue(diagonal.contains(new PassageGeometry.Cell(1, 0)));
        assertTrue(diagonal.contains(new PassageGeometry.Cell(1, 1)));
        assertTrue(diagonal.contains(new PassageGeometry.Cell(2, 1)));
    }

    @Test void stationaryLaneOnlyContainsTheCurrentColumn() {
        assertEquals(java.util.List.of(new PassageGeometry.Cell(-1, -1)),
            PassageGeometry.lane(-.2, -.2, 0, 0, 2));
    }

    @Test void recognisesBoundedOneAndTwoWidePassagesOnly() {
        assertTrue(PassageGeometry.narrowCorridor(offset -> offset == 0, 2));
        assertTrue(PassageGeometry.narrowCorridor(offset -> offset == 0 || offset == 1, 2));
        assertTrue(PassageGeometry.narrowCorridor(offset -> offset == -1 || offset == 0, 2));
        assertFalse(PassageGeometry.narrowCorridor(offset -> offset >= -1 && offset <= 1, 2));
        assertFalse(PassageGeometry.narrowCorridor(offset -> offset >= 0, 2));
        assertFalse(PassageGeometry.narrowCorridor(offset -> false, 2));
    }
}
