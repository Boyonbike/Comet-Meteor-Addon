/*
 * Original Comet implementation by Boyonabike.
 * Independent: dispatch tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Action;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.ExposureBound;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.TargetEstimate;
import dev.tntcoating.core.combat.mc.CombatRuntime.PlanContext;
import dev.tntcoating.core.combat.mc.CombatRuntime.Reason;
import dev.tntcoating.core.combat.mc.CombatRuntime.Status;
import dev.tntcoating.core.combat.mc.CombatTestFakes.FakeSink;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 2b dispatch: one send path, typed failures and retention. */
final class CombatRuntimeDispatchTest {
    private static final FakeSink.Kind PLACE = FakeSink.Kind.PLACE;
    private static final FakeSink.Kind ATTACK = FakeSink.Kind.ATTACK;
    private static final FakeSink.Kind SWING = FakeSink.Kind.SWING;
    private static final FakeSink.Kind SLOT = FakeSink.Kind.SLOT;

    private static PlanContext badEstimate(CombatRuntimeFixture fixture) {
        return new PlanContext(fixture.safetyState(), fixture.zeroBound(),
            new TargetEstimate(0.0, 0.0, 0.0), fixture.healthySelf(), true, 0,
            EngineHarness.END_CRYSTAL, fixture.client.selectedSlot, fixture.client.offhandItem(),
            false, false, false, null, false, null);
    }

    @Test
    void placeDispatchSendsOneCausativeAndASwing() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        assertTrue(fixture.reserve(intent, fixture.exposure()));
        RiskId risk = fixture.scheduler.riskId(CombatRuntimeFixture.PLAN, intent.id()).orElseThrow();
        CombatRuntime.DispatchResult result = fixture.dispatch(intent,
            fixture.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null));
        assertEquals(Status.SENT, result.status());
        assertEquals(Reason.NONE, result.reason());
        assertEquals(1, fixture.sink.count(PLACE));
        assertEquals(1, fixture.sink.count(SWING));
        assertEquals(0, fixture.sink.count(ATTACK));
        assertTrue(result.trace().causativeAccepted());
        assertEquals(CombatRiskBudget.State.SENT, fixture.risk.state(risk));
        assertEquals(1, fixture.scheduler.leaseCount());
    }

    @Test
    void attackDispatchSendsTheVerifiedEntityIdOnly() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.attack();
        fixture.reserve(intent, fixture.context(Action.ATTACK_CRYSTAL, null, intent.entity()));
        CombatRuntime.DispatchResult result = fixture.dispatch(intent,
            fixture.context(Action.ATTACK_CRYSTAL, null, intent.entity()));
        assertEquals(Status.SENT, result.status());
        assertEquals(1, fixture.sink.count(ATTACK));
        assertEquals(intent.entity().id(), fixture.sink.last(ATTACK).number());
        assertEquals(0, fixture.sink.count(PLACE));
    }

    @Test
    void nonClientThreadSendsNothing() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.onClientThread = false;
        CombatRuntime.DispatchResult result = fixture.dispatch(fixture.place(),
            fixture.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null));
        assertEquals(Status.FAILED, result.status());
        assertEquals(Reason.NOT_CLIENT_THREAD, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void disableDeathAndConfigChangeInvalidateTheTicket() {
        CombatRuntimeFixture disable = new CombatRuntimeFixture();
        Intent disableIntent = disable.place();
        disable.reserve(disableIntent, disable.exposure());
        disable.runtime.onDisabled();
        assertEquals(Reason.TICKET_INVALID, disable.dispatch(disableIntent,
            disable.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null)).reason());
        assertEquals(0, disable.sink.attempts);

        CombatRuntimeFixture death = new CombatRuntimeFixture();
        Intent deathIntent = death.place();
        death.reserve(deathIntent, death.exposure());
        death.runtime.onDeath();
        assertEquals(Reason.TICKET_INVALID, death.dispatch(deathIntent,
            death.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null)).reason());
        assertEquals(0, death.sink.attempts);

        CombatRuntimeFixture config = new CombatRuntimeFixture();
        Intent configIntent = config.place();
        config.reserve(configIntent, config.exposure());
        config.runtime.onConfigChanged();
        assertEquals(Reason.TICKET_INVALID, config.dispatch(configIntent,
            config.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null)).reason());
        assertEquals(0, config.sink.attempts);
    }

    @Test
    void missingPlanIsRefusedWithoutSending() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent, fixture.exposure());
        CombatRuntime.DispatchResult result = fixture.runtime.dispatch(intent, fixture.ticket);
        assertEquals(Reason.NO_PLAN, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void policyRejectionPreventsTheSend() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        PlanContext context = badEstimate(fixture);
        fixture.reserve(intent, context);
        CombatRuntime.DispatchResult result = fixture.dispatch(intent, context);
        assertEquals(Status.FAILED, result.status());
        assertEquals(Reason.POLICY_REJECTED, result.reason());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void rangeVisibilityScreenSlotOffhandAndItemGatesAllRefuse() {
        CombatRuntimeFixture far = new CombatRuntimeFixture();
        far.client.blockRange = 0.1;
        Intent farIntent = far.place();
        far.reserve(farIntent, far.exposure());
        assertEquals(Reason.OUT_OF_REACH, far.dispatch(farIntent,
            far.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null)).reason());
        assertEquals(0, far.sink.attempts);

        CombatRuntimeFixture blind = new CombatRuntimeFixture();
        blind.client.visible = false;
        Intent blindIntent = blind.place();
        blind.reserve(blindIntent, blind.exposure());
        assertEquals(Reason.NOT_VISIBLE, blind.dispatch(blindIntent,
            blind.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null)).reason());

        CombatRuntimeFixture screen = new CombatRuntimeFixture();
        screen.client.screenOpen = true;
        Intent screenIntent = screen.place();
        screen.reserve(screenIntent, screen.exposure());
        assertEquals(Reason.SCREEN_OPEN, screen.dispatch(screenIntent,
            screen.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null)).reason());

        CombatRuntimeFixture manual = new CombatRuntimeFixture();
        Intent manualIntent = manual.place();
        PlanContext manualContext = manual.context(Action.PLACE_CRYSTAL,
            CombatRuntimeFixture.CELL, null);
        manual.reserve(manualIntent, manualContext);
        manual.client.selectedSlot = 4;
        assertEquals(Reason.MANUAL_SLOT_CHANGE,
            manual.dispatch(manualIntent, manualContext).reason());
    }

    @Test
    void changedOffhandAndMissingItemRefuse() {
        CombatRuntimeFixture offhand = new CombatRuntimeFixture();
        Intent offhandIntent = offhand.place();
        PlanContext offhandContext = offhand.context(Action.PLACE_CRYSTAL,
            CombatRuntimeFixture.CELL, null);
        offhand.reserve(offhandIntent, offhandContext);
        offhand.client.offhand = "minecraft:shield";
        assertEquals(Reason.OFFHAND_CHANGED, offhand.dispatch(offhandIntent, offhandContext).reason());

        CombatRuntimeFixture item = new CombatRuntimeFixture();
        item.client.hotbar[0] = "minecraft:dirt";
        Intent itemIntent = item.place();
        item.reserve(itemIntent, item.exposure());
        assertEquals(Reason.ITEM_MISSING, item.dispatch(itemIntent,
            item.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null)).reason());
        assertEquals(0, item.sink.attempts);
    }

    @Test
    void rotationGateRequestsAimAndBlocksUntilSatisfied() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.rotationSatisfied = false;
        Intent intent = fixture.place();
        fixture.reserve(intent, fixture.exposure());
        CombatRuntime.DispatchResult result = fixture.dispatch(intent,
            fixture.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null));
        assertEquals(Reason.ROTATION_NOT_READY, result.reason());
        assertEquals(1, fixture.client.rotationRequests.size());
        assertEquals(0, fixture.sink.attempts);
    }

    @Test
    void attackGatesOnPresentCrystalIdentity() {
        CombatRuntimeFixture absent = new CombatRuntimeFixture();
        Intent absentIntent = absent.intent(absent.freshId(), Action.ATTACK_CRYSTAL, null,
            new dev.tntcoating.core.combat.EntityKey(absent.connection, 777,
                java.util.UUID.randomUUID()));
        absent.reserve(absentIntent, absent.context(Action.ATTACK_CRYSTAL, null, absentIntent.entity()));
        CombatRuntime.DispatchResult absentResult = absent.dispatch(absentIntent,
            absent.context(Action.ATTACK_CRYSTAL, null, absentIntent.entity()));
        assertEquals(Reason.UNKNOWN_ENTITY, absentResult.reason());
        assertEquals(0, absent.sink.attempts);

        CombatRuntimeFixture wrong = new CombatRuntimeFixture();
        wrong.client.putEntity(5, java.util.UUID.randomUUID(), "minecraft:zombie",
            CombatRuntimeFixture.CRYSTAL_POSITION, dev.tntcoating.core.combat.Box.fromCell(
                CombatRuntimeFixture.CELL));
        dev.tntcoating.core.combat.EntityKey zombie =
            wrong.client.entity(5);
        Intent wrongIntent = wrong.intent(wrong.freshId(), Action.ATTACK_CRYSTAL, null, zombie);
        wrong.reserve(wrongIntent, wrong.context(Action.ATTACK_CRYSTAL, null, zombie));
        assertEquals(Reason.ENTITY_MISMATCH, wrong.dispatch(wrongIntent,
            wrong.context(Action.ATTACK_CRYSTAL, null, zombie)).reason());
        assertEquals(0, wrong.sink.attempts);
    }

    @Test
    void slotSwitchAndRestoreWrapTheSupportPlacement() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.client.selectedSlot = 0;
        Intent intent = fixture.intent(fixture.freshId(), Action.PLACE_SUPPORT,
            CombatRuntimeFixture.CELL, null);
        PlanContext context = new PlanContext(fixture.safetyState(), fixture.zeroBound(),
            fixture.estimate(), fixture.healthySelf(), true, 1, EngineHarness.OBSIDIAN,
            fixture.client.selectedSlot, fixture.client.offhandItem(), false, false, false, null,
            true, null);
        fixture.reserve(intent, context);
        CombatRuntime.DispatchResult result = fixture.dispatch(intent, context);
        assertEquals(Status.SENT, result.status());
        assertEquals(2, fixture.sink.count(SLOT));
        assertEquals(1, fixture.sink.count(FakeSink.Kind.SUPPORT));
        assertEquals(2, fixture.client.selections.size());
        assertEquals(1, fixture.client.selections.get(0));
        assertEquals(0, fixture.client.selections.get(1));
    }

    @Test
    void acknowledgedSequenceIsTrackedButNeverInferred() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        fixture.sink.ackSequence = 7;
        Intent intent = fixture.place();
        fixture.reserve(intent, fixture.exposure());
        CombatRuntime.DispatchResult result = fixture.dispatch(intent,
            fixture.context(Action.PLACE_CRYSTAL, CombatRuntimeFixture.CELL, null));
        assertEquals(7, result.trace().packet(CombatRuntime.DispatchTrace.Stage.CAUSATIVE)
            .orElseThrow().ackSequence());
        assertEquals(0, result.trace().acknowledgedCount());
        fixture.runtime.onBlockChangedAck(7);
        assertEquals(2, result.trace().acknowledgedCount());
        assertTrue(result.trace().packet(CombatRuntime.DispatchTrace.Stage.CAUSATIVE)
            .orElseThrow().acknowledged());
        assertEquals(2, fixture.runtime.lastTrace().orElseThrow().acknowledgedCount());
    }
}
