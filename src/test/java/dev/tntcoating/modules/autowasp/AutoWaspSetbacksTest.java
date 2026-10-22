package dev.tntcoating.modules.autowasp;

import dev.tntcoating.modules.autowasp.AutoWaspSetbacks.Kind;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoWaspSetbacksTest {
    private static final Vec3 SPOT = new Vec3(100, 80, 100);
    private static final Vec3 AHEAD = new Vec3(112, 80, 100);

    @Test void aNearbyCorrectionIsASetbackAndAFarOneATeleport() {
        AutoWaspSetbacks setbacks = new AutoWaspSetbacks();
        assertEquals(Kind.SETBACK, setbacks.classify(1, SPOT, AHEAD));
        assertEquals(Kind.TELEPORT, setbacks.classify(2, new Vec3(5000, 80, 100), AHEAD));
        assertEquals(Kind.TELEPORT, setbacks.classify(3, SPOT, null), "no reported position to compare with");
    }

    @Test void aSecondSetbackToTheSameSpotPinsTheFlight() {
        AutoWaspSetbacks setbacks = new AutoWaspSetbacks();
        assertEquals(Kind.SETBACK, setbacks.classify(10, SPOT, AHEAD));
        assertEquals(Kind.PINNED, setbacks.classify(14, SPOT.add(.05, 0, 0), AHEAD));
        assertEquals(Kind.PINNED, setbacks.classify(18, SPOT, AHEAD));
    }

    @Test void differentSpotsOrALongGapAreOrdinarySetbacks() {
        AutoWaspSetbacks setbacks = new AutoWaspSetbacks();
        setbacks.classify(10, SPOT, AHEAD);
        assertEquals(Kind.SETBACK, setbacks.classify(12, SPOT.add(3, 0, 0), AHEAD));
        assertEquals(Kind.SETBACK, setbacks.classify(12 + AutoWaspSetbacks.PINNED_WINDOW_TICKS + 1,
            SPOT.add(3, 0, 0), AHEAD));
    }

    @Test void aTeleportForgetsTheLastSetbackSpot() {
        AutoWaspSetbacks setbacks = new AutoWaspSetbacks();
        setbacks.classify(10, SPOT, AHEAD);
        setbacks.classify(11, SPOT, null);
        assertEquals(Kind.SETBACK, setbacks.classify(12, SPOT, AHEAD));
    }

    @Test void pinningLastsTheGivenTicks() {
        AutoWaspSetbacks setbacks = new AutoWaspSetbacks();
        setbacks.pin(3);
        for (int tick = 0; tick < 3; tick++) {
            assertTrue(setbacks.pinned());
            setbacks.tick();
        }
        assertFalse(setbacks.pinned());
        setbacks.pin(5);
        setbacks.reset();
        assertFalse(setbacks.pinned());
    }
}
