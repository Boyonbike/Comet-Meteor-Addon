package dev.tntcoating.core;
import dev.tntcoating.core.ElytraHandoff.End;
import dev.tntcoating.core.ElytraHandoff.Refusal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ElytraHandoffTest {
    // Baritone's defaults: elytraMinimumDurability 5, elytraMinFireworksBeforeLanding 5.
    private static Refusal check(double distance, int durability, int fireworks, boolean fallFlying, boolean onGround, boolean clear) {
        return ElytraHandoff.check(distance, 300, true, durability, 5, fireworks, 5, fallFlying, onGround, clear);
    }

    @Test void longTripOnOpenGroundIsFlown() {
        assertNull(check(5000, 432, 64, false, true, true));
    }
    @Test void shortTripIsWalked() {
        assertEquals(Refusal.SHORT, check(299, 432, 64, false, true, true));
        assertNull(check(300, 432, 64, false, true, true));
    }
    @Test void unsupportedSystemIsWalked() {
        assertEquals(Refusal.UNSUPPORTED, ElytraHandoff.check(5000, 300, false, 432, 5, 64, 5, false, true, true));
    }
    @Test void needsAnElytraBaritoneWontLandStraightAway() {
        assertEquals(Refusal.NO_ELYTRA, check(5000, -1, 64, false, true, true));
        assertEquals(Refusal.NO_ELYTRA, check(5000, 4, 64, false, true, true));
        assertNull(check(5000, 5, 64, false, true, true));
        assertEquals(Refusal.NO_ELYTRA, ElytraHandoff.check(5000, 300, true, -1, 0, 64, 5, false, true, true));
    }
    @Test void needsMoreFireworksThanBaritoneLandsAt() {
        assertEquals(Refusal.FIREWORKS, check(5000, 432, 5, false, true, true));
        assertNull(check(5000, 432, 6, false, true, true));
    }
    @Test void controlledFlightTakesOffWithNoFireworks() {
        assertNull(ElytraHandoff.check(5000, 300, true, 432, 5, 0, 5, false, true, true, true));
        assertNull(ElytraHandoff.check(5000, 300, true, 432, 5, 0, 5, true, false, false, true));
    }
    @Test void rocketlessFlightStillRequiresUsableWingsAndOpenGround() {
        assertEquals(Refusal.NO_ELYTRA, ElytraHandoff.check(5000, 300, true, -1, 5, 0, 5, false, true, true, true));
        assertEquals(Refusal.NO_ELYTRA, ElytraHandoff.check(5000, 300, true, 4, 5, 0, 5, false, true, true, true));
        assertEquals(Refusal.COVERED, ElytraHandoff.check(5000, 300, true, 432, 5, 0, 5, false, true, false, true));
        assertEquals(Refusal.AIRBORNE, ElytraHandoff.check(5000, 300, true, 432, 5, 0, 5, false, false, true, true));
    }
    @Test void vanillaFlightStillRequiresFireworks() {
        assertEquals(Refusal.FIREWORKS, ElytraHandoff.check(5000, 300, true, 432, 5, 0, 5, false, true, true, false));
    }
    @Test void coveredOrAirborneIsCheckedAgainLater() {
        assertEquals(Refusal.COVERED, check(5000, 432, 64, false, true, false));
        assertEquals(Refusal.AIRBORNE, check(5000, 432, 64, false, false, true));
        assertFalse(Refusal.COVERED.settled);
        assertFalse(Refusal.AIRBORNE.settled);
        assertTrue(Refusal.FIREWORKS.settled);
    }
    @Test void alreadyGlidingIsFlownWhateverIsAbove() {
        assertNull(check(5000, 432, 64, true, false, false));
    }
    @Test void flightEnds() {
        assertEquals(End.STOPPED_MID_AIR, ElytraHandoff.end(true, true, 20, 300));
        assertEquals(End.STOPPED_ON_GROUND, ElytraHandoff.end(false, false, 5000, 300));
        assertEquals(End.WALK_REST, ElytraHandoff.end(false, true, 40, 300));
        assertEquals(End.LANDED_SHORT, ElytraHandoff.end(false, true, 300, 300));
    }
    @Test void distanceIgnoresHeight() {
        assertEquals(500, ElytraHandoff.horizontalDistance(0, 0, 300, 400), 1e-9);
    }
}
