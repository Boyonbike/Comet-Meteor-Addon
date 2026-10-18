package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.tntcoating.core.ShulkerIconShape.Point.of;

class ShulkerIconShapeTest {
    @Test void repeatedFaceCornersAndVertexOrderDoNotDuplicateAShape() {
        var a = of(0, 0, 0);
        var b = of(1, 1, 1);
        assertEquals(ShulkerIconShape.key(List.of(a, b, a)), ShulkerIconShape.key(List.of(b, a)));
    }

    @Test void smallBakingRoundingDifferencesShareAShape() {
        assertEquals(of(0.5f, 0.25f, -0.5f), of(0.500001f, 0.250001f, -0.500001f));
    }

    @Test void differentHeightsAndInteriorStepsKeepDifferentShapes() {
        var cube = List.of(of(0, 0, 0), of(1, 1, 1));
        var slab = List.of(of(0, 0, 0), of(1, 0.5f, 1));
        var stairs = List.of(of(0, 0, 0), of(1, 1, 1), of(0.5f, 0.5f, 1));
        assertNotEquals(ShulkerIconShape.key(cube), ShulkerIconShape.key(slab));
        assertNotEquals(ShulkerIconShape.key(cube), ShulkerIconShape.key(stairs));
    }
}
