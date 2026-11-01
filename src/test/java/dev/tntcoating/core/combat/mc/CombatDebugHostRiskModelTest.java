/*
 * Original Comet implementation by Boyonabike.
 * Independent: written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.RiskModel;
import org.junit.jupiter.api.Test;

final class CombatDebugHostRiskModelTest {
    @Test
    void headerNamesTheActiveRiskModel() {
        CombatRiskBudget budget = new CombatRiskBudget((envelope, current) -> current);
        String header = CombatDebugHost.riskModelHeader(budget);
        assertTrue(header.startsWith(" risk-model="), header);
        assertEquals(" risk-model=" + budget.riskModel().key(), header);
        budget.setRiskModel(RiskModel.METEOR_STYLE);
        assertEquals(" risk-model=" + RiskModel.METEOR_STYLE.key(),
            CombatDebugHost.riskModelHeader(budget));
    }
}
