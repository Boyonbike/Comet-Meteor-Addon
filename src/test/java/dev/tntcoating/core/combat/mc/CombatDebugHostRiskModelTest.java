/*
 * Original Comet implementation by Boyonabike.
 * Independent: debug-header tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tntcoating.core.combat.RiskModel;
import org.junit.jupiter.api.Test;

/** Stage 6 display: a tester export names the active risk model, and the label follows live switches. */
final class CombatDebugHostRiskModelTest {
    @Test
    void headerNamesTheActiveRiskModel() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();

        assertEquals("", CombatDebugHost.riskModelHeader(null));
        assertEquals(" risk-model=strict", CombatDebugHost.riskModelHeader(fixture.budget));

        fixture.budget.setRiskModel(RiskModel.BARRIER);
        assertEquals(" risk-model=barrier", CombatDebugHost.riskModelHeader(fixture.budget));
    }
}
