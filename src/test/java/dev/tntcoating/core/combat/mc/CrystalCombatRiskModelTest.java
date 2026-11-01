/*
 * Original Comet implementation by Boyonabike.
 * Independent: controller tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.CrystalCombatGate;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.RiskModel;
import dev.tntcoating.core.combat.Vec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Stage 6 plumbing: the model is live state on the built stack. A switch never rebuilds the stack and never
 * clears risk that has already been sent; leaving BARRIER drops only in-flight probe evidence.
 */
final class CrystalCombatRiskModelTest {
    @BeforeAll
    static void bootstrap() {
        AdapterTestSupport.bootstrap();
    }

    @AfterEach
    void clearGate() {
        CrystalCombatGate.deactivate();
        CrystalCombatGate.setProtector(null);
    }

    private static CrystalCombatController enabled(CombatRuntimeFixture fixture) {
        CrystalCombatController controller =
            new CrystalCombatController(() -> null, fixture::controllerStack);
        assertTrue(controller.enable());
        return controller;
    }

    private static RiskId sentPlace(CombatRuntimeFixture fixture) {
        Intent intent = fixture.place();
        RiskId id = fixture.reserve(intent);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(intent, fixture.ticket).status());
        return id;
    }

    private static Observation explosion(CombatRuntimeFixture fixture) {
        return new Observation(fixture.ticket.conn(), 0, 0, 0, 0, ObservationKind.EXPLOSION,
            fixture.crystal(), null, new Vec(0.5, 64.5, 0.5), null,
            new ObservationPayload.Explosion(6.0));
    }

    @Test
    void switchReachesTheLiveBudgetWithoutRebuildingTheStack() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = enabled(fixture);
        CrystalCombatController.Stack before = controller.stack();

        controller.setRiskModel(RiskModel.BARRIER);

        assertSame(before, controller.stack(), "switching never rebuilds the stack");
        assertSame(fixture.budget, controller.stack().risk());
        assertEquals(RiskModel.BARRIER, fixture.budget.riskModel());
        assertEquals(RiskModel.BARRIER, controller.riskModel());
    }

    @Test
    void switchAndConfigChangeKeepRiskThatAlreadyLeft() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = enabled(fixture);
        RiskId id = sentPlace(fixture);
        int outstandingBefore = fixture.budget.outstanding();
        int debtBefore = fixture.budget.debt(fixture.self()).count();

        controller.setRiskModel(RiskModel.METEOR_STYLE);
        controller.onConfigChanged();
        controller.setRiskModel(RiskModel.BARRIER);
        controller.setRiskModel(RiskModel.STRICT);

        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(id));
        assertEquals(outstandingBefore, fixture.budget.outstanding());
        assertEquals(debtBefore, fixture.budget.debt(fixture.self()).count());
        assertEquals(RiskModel.STRICT, fixture.budget.riskModel());
    }

    @Test
    void leavingBarrierDropsProbeEvidenceButKeepsDebt() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller = enabled(fixture);
        controller.setRiskModel(RiskModel.BARRIER);
        RiskId id = sentPlace(fixture);
        fixture.budget.observe(explosion(fixture));
        fixture.sink.probeSequence = 7;

        fixture.runtime.tick(1);
        assertTrue(fixture.budget.barrierProbeInFlight());

        controller.setRiskModel(RiskModel.STRICT);

        assertFalse(fixture.budget.barrierProbeInFlight());
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(id));
        assertEquals(1, fixture.budget.outstanding());
        assertEquals(1, fixture.budget.debt(fixture.self()).count());
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.PROBE));
    }

    @Test
    void riskModelIsStagedBeforeTheStackExists() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        CrystalCombatController controller =
            new CrystalCombatController(() -> null, fixture::controllerStack);
        assertFalse(controller.hasStack());

        controller.setRiskModel(RiskModel.METEOR_STYLE);

        assertEquals(RiskModel.METEOR_STYLE, controller.riskModel());
        assertFalse(controller.hasStack());
    }
}
