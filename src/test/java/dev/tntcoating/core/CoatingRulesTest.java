package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoatingRulesTest {
    @Test void tunnelDoesNotBlockSmartJumpDirectlyUnderPlayer() {
        assertTrue(CoatingRules.tunnelAllows(true, true, false));
        assertTrue(CoatingRules.tunnelAllows(true, true, true));
    }
    @Test void tunnelStillRejectsForwardCoatingAndScaffold() {
        assertFalse(CoatingRules.tunnelAllows(true, false, false));
        assertTrue(CoatingRules.tunnelAllows(true, false, true));
    }
    @Test void disabledTunnelDoesNotRestrictEitherPlacementType() {
        assertTrue(CoatingRules.tunnelAllows(false, false, false));
        assertTrue(CoatingRules.tunnelAllows(false, true, false));
    }

    @Test void ordinaryStoneDirtAndEndStoneCanBeCoated() {
        for (float resistance : new float[] {.5f, 6, 9})
            assertTrue(CoatingRules.surface(false, false, false, true, resistance, 1.5f));
    }
    @Test void ObsidianBedrockAndOtherBlastProofBlocksAreSkipped() {
        for (float resistance : new float[] {100, 600, 1200, 3600000})
            assertFalse(CoatingRules.surface(false, false, false, true, resistance, 50));
        assertFalse(CoatingRules.surface(false, false, false, true, 6, -1));
    }
    @Test void waterloggedStoneIsSkippedEvenThoughStoneItselfIsDestructible() {
        assertFalse(CoatingRules.surface(false, true, false, true, 6, 1.5f));
    }
    @Test void tntAirAndNonCollidableVegetationCannotCreateMoreLayers() {
        assertFalse(CoatingRules.surface(true, false, false, true, 0, 0));
        assertFalse(CoatingRules.surface(false, false, true, false, 0, 0));
        assertFalse(CoatingRules.surface(false, false, false, false, 0, 0));
    }
    @Test void feetAndHeadPlacementsRequireATerrainWall() {
        assertFalse(CoatingRules.heightAllowed(64, 64, false));
        assertFalse(CoatingRules.heightAllowed(65, 64, false));
        assertTrue(CoatingRules.heightAllowed(64, 64, true));
        assertTrue(CoatingRules.heightAllowed(65, 64, true));
        assertTrue(CoatingRules.heightAllowed(63, 64, false));
        assertTrue(CoatingRules.heightAllowed(66, 64, false));
    }
    @Test void changingElevationMovesOnlyTheFeetAndHeadExclusions() {
        for (int feet = 64; feet <= 70; feet++) {
            assertFalse(CoatingRules.heightAllowed(feet, feet, false));
            assertFalse(CoatingRules.heightAllowed(feet + 1, feet, false));
            assertTrue(CoatingRules.heightAllowed(feet - 1, feet, false));
            assertTrue(CoatingRules.heightAllowed(feet + 2, feet, false));
        }
    }
}
