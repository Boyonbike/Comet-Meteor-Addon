package dev.tntcoating.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FlightPolicyTest {
    @Test void toggleActuallyControlsDetectedFlight() {
        assertTrue(FlightPolicy.allowPlacement(true,true,false,false));
        assertFalse(FlightPolicy.allowPlacement(false,true,false,false));
    }
    @Test void detectsGroundSpoofedFlight() {
        assertFalse(FlightPolicy.allowPlacement(false,true,true,false));
    }
    @Test void preservesOrdinaryWalkingAndJumping() {
        assertTrue(FlightPolicy.allowPlacement(false,false,true,false));
        assertTrue(FlightPolicy.allowPlacement(false,false,false,true));
    }
    @Test void coversUnknownAirborneMovement() {
        assertFalse(FlightPolicy.allowPlacement(false,false,false,false));
        assertTrue(FlightPolicy.allowPlacement(true,false,false,false));
    }
}
