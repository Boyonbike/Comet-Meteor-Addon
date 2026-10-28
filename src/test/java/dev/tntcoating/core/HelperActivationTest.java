package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HelperActivationTest {
    @Test void lifecycleCallbackWaitsForMeteorToFinishUpdatingActiveFlag() {
        var activation = new HelperActivation();
        activation.managed(true);
        assertTrue(activation.needsTransition(false, false));
        assertFalse(activation.needsTransition(false, true));
        activation.disable();
        assertFalse(activation.needsTransition(true, true));
        assertTrue(activation.needsTransition(true, false));
        assertFalse(activation.needsTransition(false, false));
    }
    @Test void tripRequestDoesNotBecomeIndependentActivation() {
        var activation = new HelperActivation();
        activation.managed(true);
        assertTrue(activation.effective());
        assertFalse(activation.independent());
        activation.managed(false);
        assertFalse(activation.effective());
    }
    @Test void independentHelperSurvivesTripEnding() {
        var activation = new HelperActivation();
        activation.independent(true);
        activation.managed(true);
        activation.managed(false);
        assertTrue(activation.effective());
        assertTrue(activation.independent());
    }
    @Test void explicitDisableClearsBothOwners() {
        var activation = new HelperActivation();
        activation.independent(true);
        activation.managed(true);
        activation.disable();
        assertFalse(activation.effective());
        assertFalse(activation.managed());
        assertFalse(activation.independent());
    }
    @org.junit.jupiter.api.Test void flightOwnersCanReleaseTheirRequestsIndependently() {
        var activation = new HelperActivation();
        activation.managed(true);
        activation.managed("boat-fly", true);
        activation.managed(false);
        org.junit.jupiter.api.Assertions.assertTrue(activation.effective());
        activation.managed("boat-fly", false);
        org.junit.jupiter.api.Assertions.assertFalse(activation.effective());
    }
}
