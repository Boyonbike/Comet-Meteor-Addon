/* Adapted from Quiettee Utils regression checks. SPDX-License-Identifier: GPL-3.0-or-later */
package dev.tntcoating.modules.boatfly;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.tntcoating.modules.boatfly.BoatPhaseTravel.Mode;

class BoatPhaseTravelTest {
    @Test void correctionFallsBackWithoutRepeatedFastRetries() {
        var travel = new BoatPhaseTravel();
        assertEquals(Mode.SUBSTEPS, travel.select(true));
        travel.sent(Mode.SUBSTEPS, 100, 3.2, .99);
        assertEquals(Mode.SUBSTEPS, travel.corrected(102));
        assertEquals(Mode.LIMITED, travel.select(true));
        travel.newRide();
        assertEquals(Mode.LIMITED, travel.select(true));
        travel.retryFlight();
        assertEquals(Mode.SUBSTEPS, travel.select(true));
        assertEquals(Mode.LIMITED, travel.select(false));
    }
    @Test void staleCorrectionsAndInvalidSpeedsCannotRejectFlight() {
        var travel = new BoatPhaseTravel();
        travel.sent(Mode.SUBSTEPS, 10, 3.2, .99);
        assertEquals(Mode.LIMITED, travel.corrected(31));
        assertEquals(Mode.LIMITED, travel.corrected(9));
        travel.newRide();
        travel.sent(Mode.SUBSTEPS, 40, Double.NaN, .99);
        assertEquals(Mode.LIMITED, travel.corrected(41));
        assertEquals(Mode.SUBSTEPS, travel.select(true));
    }
}
