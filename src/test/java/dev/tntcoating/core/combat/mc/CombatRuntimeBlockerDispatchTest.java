/*
 * Original Comet implementation by Boyonabike.
 * Independent: test scaffolding written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.ExposureBound;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.RiskRequest;
import dev.tntcoating.core.combat.ServerTickInterval;
import dev.tntcoating.core.combat.Strategy;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.mc.CombatRuntime.PlanContext;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Phase 4.6: PLACE_BLOCKER goes through the one dispatch path with zero exposure, no offhand. */
final class CombatRuntimeBlockerDispatchTest {
    private static final long PLAN = CombatRuntimeFixture.PLAN;
    private static final dev.tntcoating.core.combat.Cell CELL = CombatRuntimeFixture.CELL;

    private static Intent blocker(CombatRuntimeFixture fixture) {
        return new Intent(fixture.freshId(), PLAN, Strategy.DEFEND, Action.PLACE_BLOCKER, CELL, null,
            Set.of(CELL), Set.of(), Set.of(), 20);
    }

    private static PlanContext context(CombatRuntimeFixture fixture) {
        return new PlanContext(fixture.state(), fixture.exposure(0.0),
            new TargetEstimate(0.0, 0.0, 0.0), fixture.healthySelf(), false, 1,
            "minecraft:obsidian", fixture.client.selectedSlot, fixture.client.offhand, false, false,
            true);
    }

    private static void prepare(CombatRuntimeFixture fixture) {
        fixture.client.hotbar[0] = "minecraft:end_crystal";
        fixture.client.hotbar[1] = "minecraft:obsidian";
        fixture.client.offhand = "minecraft:totem_of_undying";
        fixture.client.selectedSlot = 0;
    }

    @Test
    void blockerDispatchSendsThePlacementThroughTheSinglePathWithZeroRisk() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        prepare(fixture);
        Intent intent = blocker(fixture);
        boolean reserved = fixture.scheduler.reserve(intent, fixture.ticket,
            new RiskRequest(fixture.exposure(0.0), new ServerTickInterval(0, 2), fixture.state(),
                2, 0, true, false, false));
        assertTrue(reserved);
        fixture.runtime.plan(intent, context(fixture));

        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);

        assertEquals(CombatRuntime.Status.SENT, result.status());
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT));
        assertEquals(0, fixture.sink.count(CombatTestFakes.FakeSink.Kind.ATTACK));
        assertEquals(0, fixture.client.selectedSlot);
        assertEquals("minecraft:totem_of_undying", fixture.client.offhand);
        assertEquals(0.0, fixture.budget.current(fixture.self()).value(), 1.0e-9);
        assertEquals(0.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);
        var riskId = fixture.scheduler.riskId(PLAN, intent.id());
        assertTrue(riskId.isEmpty() || fixture.budget.boundCrystal(riskId.get()).isEmpty());
        assertFalse(fixture.client.hotbar[0].equals("minecraft:obsidian"));
    }

    @Test
    void refusedBlockerPacketReleasesTheUnsentLeaseWithoutRiskDebt() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        prepare(fixture);
        fixture.client.supportBelow = false;
        Intent intent = blocker(fixture);
        assertTrue(fixture.scheduler.reserve(intent, fixture.ticket,
            new RiskRequest(fixture.exposure(0.0), new ServerTickInterval(0, 2), fixture.state(),
                2, 0, true, false, false)));
        fixture.runtime.plan(intent, context(fixture));

        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);

        assertEquals(CombatRuntime.Status.FAILED, result.status());
        assertEquals(CombatRuntime.Reason.NO_SUPPORT, result.reason());
        assertEquals(0, fixture.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT));
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(0, fixture.budget.outstanding());
        assertEquals(0.0, fixture.budget.current(fixture.self()).value(), 1.0e-9);
        assertEquals(0, fixture.client.selectedSlot);
    }

    @Test
    void defensiveBreakRevalidatesCeilingsAtDispatchWithoutTheTargetFloor() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        prepare(fixture);
        // Self ceiling breach discovered only at dispatch time: no packet may leave.
        Intent intent = fixture.attack();
        fixture.reserve(intent, new PlanContext(fixture.state(),
            fixture.exposure(fixture.safety.maxSelf() + 1.0), new TargetEstimate(0.0, 0.0, 0.0),
            fixture.healthySelf(), false, fixture.client.selectedSlot,
            fixture.client.hotbar[fixture.client.selectedSlot], fixture.client.selectedSlot,
            fixture.client.offhand, false, false, true));

        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);

        assertEquals(CombatRuntime.Status.FAILED, result.status());
        assertEquals(CombatRuntime.Reason.POLICY_REJECTED, result.reason());
        assertEquals(0, fixture.sink.count(CombatTestFakes.FakeSink.Kind.ATTACK));
    }

    @Test
    void defensiveBreakHasNoTargetDamageRequirement() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        prepare(fixture);
        Intent intent = fixture.attack();
        fixture.reserve(intent, new PlanContext(fixture.state(), fixture.exposure(1.0),
            new TargetEstimate(0.0, 0.0, 0.0), new EffectiveHealth(0.0, 0.0, Capability.UNKNOWN, 0L),
            false, fixture.client.selectedSlot, fixture.client.hotbar[fixture.client.selectedSlot],
            fixture.client.selectedSlot, fixture.client.offhand, false, false, true));

        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);

        assertEquals(CombatRuntime.Status.SENT, result.status());
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.ATTACK));
    }

    @Test
    void ordinaryOffenceStillRequiresTheTargetMinimum() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        prepare(fixture);
        Intent intent = fixture.attack();
        fixture.reserve(intent, new PlanContext(fixture.state(), fixture.exposure(1.0),
            new TargetEstimate(0.0, 0.0, 0.0), fixture.healthySelf(), false,
            fixture.client.selectedSlot, fixture.client.hotbar[fixture.client.selectedSlot],
            fixture.client.selectedSlot, fixture.client.offhand, false, false, false));

        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);

        assertEquals(CombatRuntime.Status.FAILED, result.status());
        assertEquals(CombatRuntime.Reason.POLICY_REJECTED, result.reason());
    }

    @Test
    void blockerUsesTheObsidianSlotAndRestoresTheSelection() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        prepare(fixture);
        fixture.client.hotbar[2] = "minecraft:obsidian";
        fixture.client.hotbar[1] = "minecraft:dirt";
        Intent intent = blocker(fixture);
        assertTrue(fixture.scheduler.reserve(intent, fixture.ticket,
            new RiskRequest(fixture.exposure(0.0), new ServerTickInterval(0, 2), fixture.state(),
                2, 0, true, false, false)));
        fixture.runtime.plan(intent, new PlanContext(fixture.state(), fixture.exposure(0.0),
            new TargetEstimate(0.0, 0.0, 0.0), fixture.healthySelf(), false, 2,
            "minecraft:obsidian", 0, fixture.client.offhand, false, false, true));

        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);

        assertEquals(CombatRuntime.Status.SENT, result.status());
        assertEquals(1, fixture.sink.count(CombatTestFakes.FakeSink.Kind.SUPPORT));
        // Slot switch out and the restore back to the reserved selection.
        assertEquals(2, fixture.sink.count(CombatTestFakes.FakeSink.Kind.SLOT));
        assertEquals(0, fixture.client.selectedSlot);
    }
}
