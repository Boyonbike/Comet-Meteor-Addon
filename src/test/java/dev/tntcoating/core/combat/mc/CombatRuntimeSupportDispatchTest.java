/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.mc.CombatRuntime.PlanContext;
import dev.tntcoating.core.combat.mc.CombatRuntime.Reason;
import dev.tntcoating.core.combat.mc.CombatRuntime.Status;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import org.junit.jupiter.api.Test;

/** Structural support placement goes through the same dispatch gates as a crystal placement. */
final class CombatRuntimeSupportDispatchTest {
    private static PlanContext structural(CombatRuntimeFixture fixture, String expectedBlock) {
        return new PlanContext(fixture.safetyState(), fixture.zeroBound(), fixture.estimate(),
            fixture.healthySelf(), true, 0, EngineHarness.END_CRYSTAL, fixture.client.selectedSlot,
            fixture.client.offhandItem(), false, false, false, null, true, expectedBlock);
    }

    @Test
    void supportPlacementSendsSupportAndSwing() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.intent(fixture.freshId(), Action.PLACE_SUPPORT,
            CombatRuntimeFixture.CELL, null);
        PlanContext context = structural(fixture, null);
        assertTrue(fixture.reserve(intent, context));
        CombatRuntime.DispatchResult result = fixture.dispatch(intent, context);
        assertEquals(Status.SENT, result.status());
        assertEquals(1, fixture.sink.count(FakeSink.Kind.SUPPORT));
        assertEquals(1, fixture.sink.count(FakeSink.Kind.SWING));
    }

    @Test
    void missingSupportBelowRefusesThePlacement() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.supportBelow = false;
        Intent intent = fixture.intent(fixture.freshId(), Action.PLACE_SUPPORT,
            CombatRuntimeFixture.CELL, null);
        PlanContext context = structural(fixture, null);
        fixture.reserve(intent, context);
        CombatRuntime.DispatchResult result = fixture.dispatch(intent, context);
        assertEquals(Status.FAILED, result.status());
        assertEquals(Reason.NO_SUPPORT, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void occupiedCellRefusesThePlacement() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.entityFree = false;
        Intent intent = fixture.intent(fixture.freshId(), Action.PLACE_SUPPORT,
            CombatRuntimeFixture.CELL, null);
        PlanContext context = structural(fixture, null);
        fixture.reserve(intent, context);
        assertEquals(Reason.OCCUPIED, fixture.dispatch(intent, context).reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void replaceableCellIsRequired() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.replaceable = false;
        Intent intent = fixture.intent(fixture.freshId(), Action.PLACE_POWER,
            CombatRuntimeFixture.CELL, null);
        PlanContext context = structural(fixture, null);
        fixture.reserve(intent, context);
        assertEquals(Reason.OCCUPIED, fixture.dispatch(intent, context).reason());
    }

    @Test
    void misalignedPistonFacingRefuses() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.pistonFacing = new dev.tntcoating.core.combat.PistonFacing(0, 0, -1);
        Intent intent = fixture.intent(fixture.freshId(), Action.PLACE_PISTON,
            CombatRuntimeFixture.CELL, null);
        PlanContext context = new PlanContext(fixture.safetyState(), fixture.zeroBound(),
            fixture.estimate(), fixture.healthySelf(), true, 0, EngineHarness.END_CRYSTAL,
            fixture.client.selectedSlot, fixture.client.offhandItem(), false, false, false,
            new dev.tntcoating.core.combat.PistonFacing(0, 0, 1), true, null);
        fixture.reserve(intent, context);
        assertEquals(Reason.WRONG_FACING, fixture.dispatch(intent, context).reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void structuralActionsNeverRequireTheDamagePolicy() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.intent(fixture.freshId(), Action.PLACE_BLOCKER,
            new Cell(0, 63, 0), null);
        PlanContext context = structural(fixture, null);
        fixture.reserve(intent, context);
        assertEquals(Status.SENT, fixture.dispatch(intent, context).status());
        assertEquals(1, fixture.sink.count(FakeSink.Kind.SUPPORT));
    }
}
