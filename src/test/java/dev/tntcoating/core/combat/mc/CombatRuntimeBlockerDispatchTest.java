/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.mc.CombatRuntime.PlanContext;
import dev.tntcoating.core.combat.mc.CombatRuntime.Reason;
import dev.tntcoating.core.combat.mc.CombatRuntime.Status;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import org.junit.jupiter.api.Test;

/** Blocker placement is a structural support action with the same gates as support. */
final class CombatRuntimeBlockerDispatchTest {
    private static PlanContext blocker(CombatRuntimeFixture fixture) {
        return new PlanContext(fixture.safetyState(), fixture.zeroBound(), fixture.estimate(),
            fixture.healthySelf(), true, 0, EngineHarness.END_CRYSTAL, fixture.client.selectedSlot,
            fixture.client.offhandItem(), false, false, false, null, true, null);
    }

    @Test
    void blockerSendsOneSupportPacketAndASwing() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.intent(fixture.freshId(), Action.PLACE_BLOCKER,
            CombatRuntimeFixture.CELL, null);
        PlanContext context = blocker(fixture);
        assertTrue(fixture.reserve(intent, context));
        CombatRuntime.DispatchResult result = fixture.dispatch(intent, context);
        assertEquals(Status.SENT, result.status());
        assertEquals(1, fixture.sink.count(FakeSink.Kind.SUPPORT));
        assertEquals(0, fixture.sink.count(FakeSink.Kind.PLACE));
        assertEquals(1, fixture.sink.count(FakeSink.Kind.SWING));
    }

    @Test
    void blockerRequiresSupportBelowAndAFreeCell() {
        CombatRuntimeFixture noSupport = new CombatRuntimeFixture();
        noSupport.client.supportBelow = false;
        Intent unsupported = noSupport.intent(noSupport.freshId(), Action.PLACE_BLOCKER,
            CombatRuntimeFixture.CELL, null);
        PlanContext unsupportedContext = blocker(noSupport);
        noSupport.reserve(unsupported, unsupportedContext);
        assertEquals(Reason.NO_SUPPORT,
            noSupport.dispatch(unsupported, unsupportedContext).reason());

        CombatRuntimeFixture occupied = new CombatRuntimeFixture();
        occupied.client.entityFree = false;
        Intent blocked = occupied.intent(occupied.freshId(), Action.PLACE_BLOCKER,
            CombatRuntimeFixture.CELL, null);
        PlanContext blockedContext = blocker(occupied);
        occupied.reserve(blocked, blockedContext);
        assertEquals(Reason.OCCUPIED, occupied.dispatch(blocked, blockedContext).reason());
        assertEquals(0, occupied.sink.attempts);
    }

    @Test
    void blockerIsNotValidatedAgainstTheTargetDamageEstimate() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.setEstimateForTest(new dev.tntcoating.core.combat.TargetEstimate(0.0, 0.0, 0.0));
        Intent intent = fixture.intent(fixture.freshId(), Action.PLACE_BLOCKER,
            CombatRuntimeFixture.CELL, null);
        PlanContext context = blocker(fixture);
        fixture.reserve(intent, context);
        assertEquals(Status.SENT, fixture.dispatch(intent, context).status());
    }

    @Test
    void blockerNeverAttacksOrMines() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.intent(fixture.freshId(), Action.PLACE_BLOCKER,
            CombatRuntimeFixture.CELL, null);
        PlanContext context = blocker(fixture);
        fixture.reserve(intent, context);
        fixture.dispatch(intent, context);
        assertEquals(0, fixture.sink.count(FakeSink.Kind.ATTACK));
        assertEquals(0, fixture.sink.count(FakeSink.Kind.BREAK));
        assertNotNull(fixture.sink.last(FakeSink.Kind.SUPPORT));
        assertFalse(fixture.sink.last(FakeSink.Kind.SUPPORT).hit() == null);
    }
}
