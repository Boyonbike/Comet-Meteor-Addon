/*
 * Original Comet implementation by Boyonabike.
 * Independent: dispatch tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.mc.CombatRuntime.PlanContext;
import dev.tntcoating.core.combat.mc.CombatRuntime.Reason;
import dev.tntcoating.core.combat.mc.CombatRuntime.Status;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.Safety;
import dev.tntcoating.core.combat.SafetyState;
import dev.tntcoating.core.combat.Strategy;
import dev.tntcoating.core.combat.SuspensionCause;
import dev.tntcoating.core.combat.Ticket;
import dev.tntcoating.core.combat.Vec;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The plan's Phase 2b dispatch steps and typed failure/retention behaviour. */
final class CombatRuntimeDispatchTest {
    private static final CombatTestFakes.FakeSink.Kind PLACE = CombatTestFakes.FakeSink.Kind.PLACE;
    private static final CombatTestFakes.FakeSink.Kind ATTACK = CombatTestFakes.FakeSink.Kind.ATTACK;
    private static final CombatTestFakes.FakeSink.Kind SWING = CombatTestFakes.FakeSink.Kind.SWING;
    private static final CombatTestFakes.FakeSink.Kind SLOT = CombatTestFakes.FakeSink.Kind.SLOT;

    @Test
    void placeDispatchSendsOneCausativeAndRecordsProgress() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        RiskId riskId = fixture.reserve(intent);
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.SENT, result.status());
        assertEquals(Reason.NONE, result.reason());
        assertEquals(1, fixture.sink.count(PLACE));
        assertEquals(1, fixture.sink.count(SWING));
        assertEquals(0, fixture.sink.count(ATTACK));
        assertTrue(result.trace().causativeAccepted());
        assertEquals(1, result.trace().packets().size() - 1);
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(riskId));
        assertEquals(1, fixture.scheduler.leaseCount());
    }

    @Test
    void attackDispatchSendsTheVerifiedEntityIdAndNoPlacement() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.attack();
        RiskId riskId = fixture.reserve(intent);
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.SENT, result.status());
        assertEquals(1, fixture.sink.count(ATTACK));
        assertEquals(3, fixture.sink.last(ATTACK).number());
        assertEquals(0, fixture.sink.count(PLACE));
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(riskId));
    }

    @Test
    void dispatchOnNonClientThreadSendsNothing() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.onClientThread = false;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(fixture.place(), fixture.ticket);
        assertEquals(Reason.NOT_CLIENT_THREAD, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void noSendAfterDisable() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.runtime.onDisabled();
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.TICKET_INVALID, result.reason());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());
    }

    @Test
    void noSendAfterDeath() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.runtime.onDeath();
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.TICKET_INVALID, result.reason());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());
    }

    @Test
    void noSendAfterConfigChange() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.runtime.onConfigChanged();
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.TICKET_INVALID, result.reason());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());
    }

    @Test
    void noSendAfterConnectionClose() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.runtime.onConnectionClosed();
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.TICKET_INVALID, result.reason());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());
    }

    @Test
    void noSendAfterDimensionChange() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.runtime.onDimensionChanged();
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.TICKET_INVALID, result.reason());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());
    }

    @Test
    void openScreenBlocksBeforeAnySend() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        RiskId riskId = fixture.reserve(intent);
        fixture.client.screenOpen = true;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.SCREEN_OPEN, result.reason());
        assertEquals(Status.BLOCKED, result.status());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(riskId));
    }

    @Test
    void manualSlotChangeAbortsBeforeCausative() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        RiskId riskId = fixture.reserve(intent);
        fixture.client.selectedSlot = 4;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.MANUAL_SLOT_CHANGE, result.reason());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(riskId));
    }

    @Test
    void offhandChangeAbortsBeforeCausative() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.client.offhand = "minecraft:air";
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.OFFHAND_CHANGED, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void missingRequiredItemBlocks() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.client.hotbar[0] = "";
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.ITEM_MISSING, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void otherOwnersBlockWithoutTouchingTheLoan() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture(40, true);
        Intent intent = fixture.place();
        fixture.reserve(intent);
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.INVENTORY_OWNED_ELSEWHERE, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void outOfReachBlocks() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.client.eye = new Vec(40.0, 65.0, 40.0);
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.OUT_OF_REACH, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void noLineOfSightBlocks() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.client.visible = false;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.NOT_VISIBLE, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void freshPolicyRevalidationCanRejectAfterReservation() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        EffectiveHealth lowHealth = new EffectiveHealth(8.0, 0.0,
            dev.tntcoating.core.combat.Capability.KNOWN, 0L);
        fixture.reserve(intent, fixture.context(fixture.state(lowHealth), fixture.exposure(),
            fixture.estimate()));
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.POLICY_REJECTED, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void suspendedBudgetBlocksAndReleases() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.budget.suspend(SuspensionCause.DEATH);
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.SUSPENDED, result.reason());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());
    }

    @Test
    void unsatisfiedRotationRequestsOnceAndSendsNothing() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent, fixture.rotationContext());
        fixture.client.rotationSatisfied = false;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.ROTATION_NOT_READY, result.reason());
        assertEquals(1, fixture.client.rotationRequests.size());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());

        fixture.client.rotationSatisfied = true;
        Intent retry = fixture.place();
        fixture.reserve(retry, fixture.rotationContext());
        assertEquals(Status.SENT, fixture.runtime.dispatch(retry, fixture.ticket).status());
    }

    @Test
    void wrongEntityTypeIsRejected() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.putEntity(3, fixture.crystalUuid, "minecraft:pig", CombatRuntimeFixture.CRYSTAL_POSITION,
            dev.tntcoating.core.combat.Box.fromCell(CombatRuntimeFixture.CELL));
        Intent intent = fixture.attack();
        fixture.reserve(intent);
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.ENTITY_MISMATCH, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void missingEntityIsRejected() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.entities.remove(3);
        Intent intent = fixture.attack();
        fixture.reserve(intent);
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.UNKNOWN_ENTITY, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void unsupportedActionIsRejected() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = new Intent(fixture.freshId(), CombatRuntimeFixture.PLAN, Strategy.DIRECT,
            Action.MINE_POWER, CombatRuntimeFixture.CELL, null, Set.of(), Set.of(), Set.of(), 20);
        fixture.reserve(intent);
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.UNSUPPORTED_ACTION, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void mismatchedTicketPlanIsRejected() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        Ticket otherPlan = fixture.session.ticket(2L);
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, otherPlan);
        assertEquals(Reason.PLAN_MISMATCH, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void missingPlanContextReleasesTheLease() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.runtime.forgetPlan(CombatRuntimeFixture.PLAN, intent.id());
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.NO_PLAN, result.reason());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());
    }

    @Test
    void failedSlotSwitchReleasesEverythingAndSendsNoCausative() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        RiskId riskId = fixture.reserve(intent, fixture.switchContext(2));
        fixture.sink.failOnAttempt = 1;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.FAILED, result.status());
        assertEquals(Reason.SLOT_SWITCH_FAILED, result.reason());
        assertEquals(1, fixture.sink.attempts);
        assertEquals(SLOT, fixture.sink.last().kind());
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(riskId));
        assertEquals(0, fixture.client.selectedSlot);
    }

    @Test
    void refusedCausativeReleasesTheReservation() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        RiskId riskId = fixture.reserve(intent);
        fixture.sink.failOnAttempt = 1;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.FAILED, result.status());
        assertEquals(Reason.CAUSATIVE_NOT_ACCEPTED, result.reason());
        assertEquals(PLACE, fixture.sink.last().kind());
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(riskId));
    }

    @Test
    void refusedCausativeAfterSlotSwitchRestoresTheSlot() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        RiskId riskId = fixture.reserve(intent, fixture.switchContext(2));
        fixture.sink.failOnAttempt = 2;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.FAILED, result.status());
        assertEquals(Reason.CAUSATIVE_NOT_ACCEPTED, result.reason());
        assertEquals(2, fixture.sink.count(SLOT));
        assertEquals(SLOT, fixture.sink.last().kind());
        assertEquals(0, fixture.client.selectedSlot);
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(riskId));
    }

    @Test
    void acceptedCausativeIsNeverRolledBackByAFailedSwing() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.attack();
        RiskId riskId = fixture.reserve(intent);
        fixture.sink.failOnAttempt = 2;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.SENT, result.status());
        assertEquals(Reason.SUPPORTING_PACKET_FAILED, result.reason());
        assertEquals(1, fixture.sink.count(ATTACK));
        assertEquals(SWING, fixture.sink.last().kind());
        assertTrue(result.trace().causativeAccepted());
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(riskId));
        assertEquals(1, fixture.scheduler.leaseCount());
    }

    @Test
    void failedSwingAfterSlotSwitchKeepsRiskAndRestoresSlot() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        RiskId riskId = fixture.reserve(intent, fixture.switchContext(2));
        fixture.sink.failOnAttempt = 3;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.SENT, result.status());
        assertEquals(Reason.SUPPORTING_PACKET_FAILED, result.reason());
        assertEquals(1, fixture.sink.count(PLACE));
        assertEquals(2, fixture.sink.count(SLOT));
        assertTrue(result.trace().causativeAccepted());
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(riskId));
        assertEquals(1, fixture.scheduler.leaseCount());
        assertEquals(0, fixture.client.selectedSlot);
    }

    @Test
    void acceptedCausativeIsNeverRolledBackByAFailedRestore() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        RiskId riskId = fixture.reserve(intent, fixture.switchContext(2));
        fixture.sink.failOnAttempt = 4;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.SENT, result.status());
        assertEquals(Reason.SUPPORTING_PACKET_FAILED, result.reason());
        assertEquals(1, fixture.sink.count(PLACE));
        assertEquals(2, fixture.sink.count(SLOT));
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(riskId));
        assertEquals(2, fixture.client.selectedSlot);
    }

    @Test
    void happyPathSwitchesAndRestoresTheHotbarSlot() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent, fixture.switchContext(2));
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.SENT, result.status());
        assertEquals(Reason.NONE, result.reason());
        assertEquals(2, fixture.sink.count(SLOT));
        assertEquals(2, fixture.client.selections.get(0));
        assertEquals(0, fixture.client.selectedSlot);
        assertEquals(4, fixture.sink.attempts);
    }

    @Test
    void lateDispatchReacquiresThePacketSlotAtTheCurrentTick() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.runtime.tick(3);
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.SENT, result.status());
        assertEquals(2, fixture.scheduler.packetUsed(3));
        assertEquals(0, fixture.scheduler.packetUsed(0));
    }

    @Test
    void lateDispatchWithoutCapacityBlocksAndReleases() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture(40, false);
        Intent intent = fixture.place();
        RiskId riskId = fixture.reserve(intent);
        fixture.runtime.tick(3);
        // The old reservation tick is gone, so the whole rolling window is available to
        // other combat work before dispatch is attempted.
        fixture.ledger.release(0, 2);
        assertTrue(fixture.ledger.tryAcquire(3, 40));
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.PACKET_BUDGET, result.reason());
        assertEquals(0, fixture.sink.attempts);
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(riskId));
    }

    @Test
    void acknowledgementMarksMatchingCausativeProgress() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.attack();
        fixture.reserve(intent);
        fixture.sink.ackSequence = 77;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Status.SENT, result.status());
        assertEquals(0, result.trace().acknowledgedCount());
        fixture.runtime.onBlockChangedAck(76);
        assertEquals(0, result.trace().acknowledgedCount());
        fixture.runtime.onBlockChangedAck(77);
        assertEquals(2, result.trace().acknowledgedCount());
        assertEquals(77, result.trace().packet(CombatRuntime.DispatchTrace.Stage.CAUSATIVE)
            .orElseThrow().ackSequence());
    }

    @Test
    void productionBoundaryRejectsPermissiveSafetyProfiles() {
        assertEquals(IllegalArgumentException.class,
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CombatRuntimeFixture(40, false, new Safety(4.0, 7.0, 2.0, 2.0)))
                .getClass());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new CombatRuntimeFixture(40, false, new Safety(4.0, 6.0, 1.5, 2.0)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new CombatRuntimeFixture(40, false, new Safety(3.0, 6.0, 2.0, 2.0)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new CombatRuntimeFixture(40, false, new Safety(4.0, 6.0, 2.0, 3.0)));
    }

    @Test
    void planContextWithPermissiveSafetyIsRejected() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        SafetyState weak = new SafetyState(fixture.ticket.conn(), fixture.ticket.gen(),
            fixture.self(), fixture.healthySelf(), Map.of(), new Safety(4.0, 7.0, 2.0, 2.0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new PlanContext(weak, fixture.exposure(), fixture.estimate(),
                fixture.healthySelf(), false, 0, "minecraft:end_crystal", 0, "", false));
    }

    @Test
    void missingSinkConnectionBlocksBeforeAnyResolution() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.client.connectionOpen = false;
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.CONNECTION_CLOSED, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void placementHitTargetsTheSupportBlockTopFace() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        assertEquals(Status.SENT, fixture.runtime.dispatch(intent, fixture.ticket).status());
        var hit = fixture.sink.last(PLACE).hit();
        assertInstanceOf(net.minecraft.world.phys.BlockHitResult.class, hit);
        assertEquals(net.minecraft.core.Direction.UP, hit.getDirection());
        assertEquals(new net.minecraft.core.BlockPos(0, 63, 0), hit.getBlockPos());
        assertEquals(0.5, hit.getLocation().x);
        assertEquals(64.0, hit.getLocation().y);
        assertEquals(0.5, hit.getLocation().z);
    }

    @Test
    void dispatchDoesNotTouchProjectileOrMiningApis() {
        // The only send surface is CombatPacketSink; the source guard keeps Speedmine/loan calls out.
        String source = assertDoesNotThrowRead(
            "src/main/java/dev/tntcoating/core/combat/mc/CombatRuntime.java");
        assertFalse(source.contains("Speedmine"), "runtime may not request mining");
        assertFalse(source.contains(".prepare("), "runtime may not call loan prepare()");
        assertFalse(source.contains("InventoryWeaponAttack"), "runtime may not interleave weapon attack");
    }

    private static String assertDoesNotThrowRead(String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path));
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
