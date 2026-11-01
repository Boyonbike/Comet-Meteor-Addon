/*
 * Original Comet implementation by Boyonabike.
 * Independent: written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.DirectSettings;
import dev.tntcoating.core.combat.CombatDamagePolicy;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.RiskModel;
import org.junit.jupiter.api.Test;

class CombatDebugHostHeaderTest {
    private static DirectSettings settings() {
        return DirectSettings.conservative(CombatDamagePolicy.conservativeSafety());
    }

    @Test
    void headerReportsOffAndTheConservativeHorizon() {
        String header = CombatDebugHost.motionPredictionHeader(settings());
        assertTrue(header.contains("motion-prediction=off"), header);
        assertTrue(header.contains("motion-prediction-ticks=2"), header);
    }

    @Test
    void headerReportsOnAndTheConfiguredHorizon() {
        String header = CombatDebugHost.motionPredictionHeader(settings().withMotion(true, 3));
        assertTrue(header.contains("motion-prediction=on"), header);
        assertTrue(header.contains("motion-prediction-ticks=3"), header);
    }

    @Test
    void missingSettingsProduceNoHeaderFragment() {
        assertEquals("", CombatDebugHost.motionPredictionHeader((DirectSettings) null));
        assertEquals("", CombatDebugHost.hurtSyncHeader(null));
        assertEquals("", CombatDebugHost.supportHeader(null));
        assertEquals("", CombatDebugHost.surroundGapHeader(null));
        assertEquals("", CombatDebugHost.dangerousCrystalHeader(null));
        assertEquals("", CombatDebugHost.antiPistonHeader(null));
        assertEquals("", CombatDebugHost.riskModelHeader(null));
    }

    @Test
    void supportHeaderReportsOffByDefaultAndOnWhenEnabled() {
        assertTrue(CombatDebugHost.supportHeader(settings()).contains("support=off"));
        assertFalse(CombatDebugHost.supportHeader(settings()).isEmpty());
    }

    @Test
    void defenceHeadersReportOffByDefaultAndOnWhenEnabled() {
        assertTrue(CombatDebugHost.dangerousCrystalHeader(settings())
            .contains("dangerous-crystal-break="));
        assertTrue(CombatDebugHost.antiPistonHeader(settings()).contains("anti-piston="));
        assertTrue(CombatDebugHost.surroundGapHeader(settings()).contains("surround-gap="));
    }

    @Test
    void hurtSyncHeaderReportsTheToggle() {
        assertTrue(CombatDebugHost.hurtSyncHeader(settings()).contains("hurt-sync="));
        assertTrue(CombatDebugHost.hurtSyncHeader(settings().withHurtSync(true)).contains("on"));
    }

    @Test
    void riskModelHeaderNamesTheActiveModel() {
        CombatRiskBudget budget = new CombatRiskBudget((envelope, current) -> current);
        assertTrue(CombatDebugHost.riskModelHeader(budget)
            .contains(budget.riskModel().key()));
        budget.setRiskModel(RiskModel.METEOR_STYLE);
        assertTrue(CombatDebugHost.riskModelHeader(budget).contains("meteor"));
    }
}
