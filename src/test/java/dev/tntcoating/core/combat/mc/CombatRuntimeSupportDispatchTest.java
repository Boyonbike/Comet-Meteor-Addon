/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.Strategy;
import dev.tntcoating.core.combat.TargetEstimate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Phase 3.3 one-support-block dispatch and the face-place target-minimum relaxation. */
class CombatRuntimeSupportDispatchTest {
    private static final Cell CELL = new Cell(0, 64, 0);

    private Intent support(CombatRuntimeFixture fixture) {
        return new Intent(fixture.freshId(), CombatRuntimeFixture.PLAN, Strategy.DIRECT,
            Action.PLACE_SUPPORT, CELL, null, Set.of(CELL), Set.of(), Set.of(), 20);
    }

    private Intent place(CombatRuntimeFixture fixture) {
        return fixture.place();
    }

    private CombatRuntime.PlanContext context(CombatRuntimeFixture fixture, int slot,
                                              String item, int selection, TargetEstimate estimate,
                                              boolean faceplace) {
        return new CombatRuntime.PlanContext(fixture.state(), fixture.exposure(), estimate,
            new EffectiveHealth(8.0, 0.0, Capability.KNOWN, 0L), false, slot, item, selection,
            fixture.client.offhand, false, faceplace);
    }

    @Test
    void supportActionSendsTheSupportPacketThroughTheSingleDispatchPath() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.hotbar[0] = "minecraft:end_crystal";
        fixture.client.hotbar[1] = "minecraft:obsidian";
        Intent intent = support(fixture);
        fixture.reserve(intent, context(fixture, 1, "minecraft:obsidian", 0,
            fixture.estimate(), false));

        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);

        assertEquals(CombatRuntime.Status.SENT, result.status());
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT));
        assertEquals(0, fixture.sink.count(CombatTestFakes.FakeSink.Kind.PLACE));
        assertEquals(2, fixture.sink.count(CombatTestFakes.FakeSink.Kind.SLOT));
        assertSame(CombatRiskBudget.State.SENT, fixture.budget.state(
            fixture.scheduler.riskId(CombatRuntimeFixture.PLAN, intent.id()).orElseThrow()));
    }

    @Test
    void faceplaceRelaxesOnlyTheMinimumTargetDamage() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        TargetEstimate tiny = new TargetEstimate(1.6, 2.0, 3.0);
        Intent intent = place(fixture);
        fixture.reserve(intent, context(fixture, 0, "minecraft:end_crystal", 0, tiny, true));

        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);

        assertEquals(CombatRuntime.Status.SENT, result.status());
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.PLACE));
    }

    @Test
    void withoutFaceplaceTheSameTinyEstimateIsRejected() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        TargetEstimate tiny = new TargetEstimate(1.6, 2.0, 3.0);
        Intent intent = place(fixture);
        fixture.reserve(intent, context(fixture, 0, "minecraft:end_crystal", 0, tiny, false));

        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);

        assertEquals(CombatRuntime.Status.FAILED, result.status());
        assertEquals(CombatRuntime.Reason.POLICY_REJECTED, result.reason());
        assertEquals(0, fixture.sink.count(CombatTestFakes.FakeSink.Kind.PLACE));
    }

    @Test
    void faceplaceNeverRelaxesTheSelfUpperBound() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        TargetEstimate tiny = new TargetEstimate(1.6, 2.0, 3.0);
        Intent intent = place(fixture);
        CombatRuntime.PlanContext context = new CombatRuntime.PlanContext(fixture.state(),
            fixture.exposure(7.0), tiny, new EffectiveHealth(8.0, 0.0, Capability.KNOWN, 0L),
            false, 0, "minecraft:end_crystal", 0, fixture.client.offhand, false, true);
        fixture.reserve(intent, context);

        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);

        assertEquals(CombatRuntime.Status.FAILED, result.status());
        assertEquals(CombatRuntime.Reason.POLICY_REJECTED, result.reason());
        assertEquals(0, fixture.sink.count(CombatTestFakes.FakeSink.Kind.PLACE));
    }
}
