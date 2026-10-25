package dev.tntcoating.core.scaffold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldEdgeGuardTest {
    @Test void leavesSupportedMovementUnchanged() {
        var result = ScaffoldEdgeGuard.clip(.3, .2, (x, z) -> true);
        assertEquals(.3, result.x()); assertEquals(.2, result.z());
    }
    @Test void stopsBeforeFirstGapEvenWhenEndpointHasSupport() {
        var result = ScaffoldEdgeGuard.clip(1, 0, (x, z) -> x < .5 || x > .8);
        assertTrue(result.x() < .5); assertTrue(result.x() >= .4);
    }
    @Test void noConfirmedSupportMeansNoHorizontalAdvance() {
        var result = ScaffoldEdgeGuard.clip(.3, .3, (x, z) -> false);
        assertEquals(0, result.x()); assertEquals(0, result.z());
    }
    @Test void diagonalStopsBothAxesAtTheSameBoundaryAndRejectsUnreasonableSweeps() {
        var result = ScaffoldEdgeGuard.clip(.4, .4, (x, z) -> x < .3);
        assertEquals(result.x(), result.z()); assertTrue(result.x() < .3);
        assertEquals(0, ScaffoldEdgeGuard.clip(1000, 0, (x, z) -> true).x());
    }
}
