/*
 * Original Comet implementation by Boyonabike.
 * Independent: lifecycle tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Capability;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.CombatRiskBudget;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EffectiveHealth;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.ExposureBound;
import dev.tntcoating.core.combat.Intent;
import dev.tntcoating.core.combat.MitigatedDamage;
import dev.tntcoating.core.combat.MitigationEnvelope;
import dev.tntcoating.core.combat.Observation;
import dev.tntcoating.core.combat.ObservationKind;
import dev.tntcoating.core.combat.ObservationPayload;
import dev.tntcoating.core.combat.RiskId;
import dev.tntcoating.core.combat.RiskRequest;
import dev.tntcoating.core.combat.SafetyState;
import dev.tntcoating.core.combat.Vec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lifecycle wiring: exactly-one ticking, generation transitions and debt retention rules. */
final class CombatRuntimeLifecycleTest {
    @Test
    void tickAdvancesBothClocksExactlyOncePerServerTick() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        assertEquals(1, fixture.runtime.tickCount());
        fixture.runtime.tick(0);
        assertEquals(1, fixture.runtime.tickCount());
        fixture.runtime.tick(1);
        assertEquals(2, fixture.runtime.tickCount());
        fixture.runtime.tick(1);
        assertEquals(2, fixture.runtime.tickCount());
        fixture.runtime.tick(-5);
        assertEquals(2, fixture.runtime.tickCount());
        assertThrows(IllegalArgumentException.class, () -> fixture.runtime.tick(0));
    }

    @Test
    void tickingNeverSendsPackets() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.runtime.tick(1);
        fixture.runtime.tick(2);
        assertEquals(0, fixture.sink.attempts);
        assertEquals(1, fixture.scheduler.leaseCount());
    }

    @Test
    void deathDropsUnsentLeasesAndRiskButNeverRetiresSentRisk() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent first = fixture.place();
        RiskId sentRisk = fixture.reserve(first);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(first, fixture.ticket).status());
        Intent second = fixture.placeAt(new dev.tntcoating.core.combat.Cell(2, 64, 0));
        RiskId unsentRisk = fixture.reserve(second);
        assertEquals(CombatRiskBudget.State.RESERVED, fixture.budget.state(unsentRisk));

        fixture.runtime.onDeath();

        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(0, fixture.scheduler.heldCells().size());
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(unsentRisk));
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(sentRisk));
        assertEquals(6.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);
        assertTrue(fixture.budget.suspended());
        assertTrue(fixture.session.isOpen(), "death keeps the connection open");
        assertEquals(CombatRuntime.Reason.TICKET_INVALID,
            fixture.runtime.dispatch(first, fixture.ticket).reason());
    }

    @Test
    void respawnClearsSelfDebtOnlyThroughTheObservationPath() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent attack = fixture.attack();
        RiskId riskId = fixture.reserve(attack);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(attack, fixture.ticket).status());
        assertEquals(6.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);

        fixture.runtime.onRespawn();

        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(riskId));
        assertEquals(0.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);
        assertEquals(0, fixture.scheduler.leaseCount());
        assertTrue(fixture.budget.suspended());
    }

    @Test
    void respawnKeepsFriendDebtAndTheRiskThatCarriesIt() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        EntityKey friend = new EntityKey(fixture.ticket.conn(), 2, UUID.nameUUIDFromBytes("friend".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ExposureBound exposure = new ExposureBound(new MitigatedDamage(6.0),
            Map.of(friend, new MitigatedDamage(2.0)), new MitigationEnvelope(0L));
        SafetyState state = new SafetyState(fixture.ticket.conn(), fixture.ticket.gen(),
            fixture.self(), fixture.healthySelf(),
            Map.of(friend, new EffectiveHealth(20.0, 0.0, Capability.KNOWN, 0L)),
            dev.tntcoating.core.combat.CombatProfiles.CONSERVATIVE_SAFETY);
        Intent intent = fixture.attack();
        RiskRequest request = new RiskRequest(exposure, new dev.tntcoating.core.combat.ServerTickInterval(10, 12),
            state, fixture.crystal(), CombatRuntimeFixture.CRYSTAL_POSITION, 2, 0, true, false, false);
        assertTrue(fixture.scheduler.reserve(intent, fixture.ticket, request));
        RiskId riskId = fixture.scheduler.riskId(intent.plan(), intent.id()).orElseThrow();
        fixture.runtime.plan(intent, fixture.context());
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(intent, fixture.ticket).status());
        assertEquals(2.0, fixture.budget.debt(friend).bound(), 1.0e-9);

        fixture.runtime.onRespawn();

        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(riskId));
        assertEquals(0.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);
        assertEquals(2.0, fixture.budget.debt(friend).bound(), 1.0e-9);
    }

    @Test
    void dimensionChangeMigratesSentSelfDebtAndReleasesSchedulerBookkeeping() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        ConnectionKey previous = fixture.session.connection();
        Intent attack = fixture.attack();
        RiskId riskId = fixture.reserve(attack);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(attack, fixture.ticket).status());
        assertEquals(6.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);

        ConnectionKey next = fixture.runtime.onDimensionChanged();

        assertNotEquals(previous, next);
        assertEquals(next, fixture.session.connection());
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(riskId));
        assertEquals(6.0, fixture.budget.debt(new EntityKey(next, 1, fixture.selfUuid)).bound(), 1.0e-9);
        assertEquals(0.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);
        assertEquals(0, fixture.scheduler.leaseCount());
        assertTrue(fixture.budget.suspended());
        assertEquals(CombatRuntime.Reason.TICKET_INVALID,
            fixture.runtime.dispatch(attack, fixture.ticket).reason());
    }

    @Test
    void queuedDimensionChangeKeepsMigratedSentSelfDebt() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        ConnectionKey previous = fixture.session.connection();
        Intent attack = fixture.attack();
        RiskId riskId = fixture.reserve(attack);
        assertEquals(CombatRuntime.Status.SENT,
            fixture.runtime.dispatch(attack, fixture.ticket).status());
        assertEquals(6.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);

        ObservationIntake.Receipt seed =
            fixture.intake.record(1, -1, 0, new ObservationIntake.RawRespawn("minecraft:overworld"));
        fixture.intake.apply(seed, null);
        fixture.intake.drain();
        ObservationIntake.Receipt receipt =
            fixture.intake.record(1, -1, 0, new ObservationIntake.RawRespawn("minecraft:the_nether"));
        fixture.intake.apply(receipt, null);

        fixture.runtime.tick(1);

        ConnectionKey next = fixture.session.connection();
        assertNotEquals(previous, next);
        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(riskId));
        assertEquals(6.0, fixture.budget.debt(new EntityKey(next, 1, fixture.selfUuid)).bound(), 1.0e-9);
        assertEquals(0.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);
        assertTrue(fixture.budget.suspended());
    }

    @Test
    void disableStopsSendsButKeepsSentRiskAndDropsUnsentWork() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent first = fixture.place();
        RiskId sentRisk = fixture.reserve(first);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(first, fixture.ticket).status());
        Intent second = fixture.placeAt(new dev.tntcoating.core.combat.Cell(2, 64, 0));
        RiskId unsentRisk = fixture.reserve(second);

        fixture.runtime.onDisabled();

        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(sentRisk));
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(unsentRisk));
        assertEquals(6.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(0, fixture.sink.attempts - 2);
        assertEquals(CombatRuntime.Reason.TICKET_INVALID,
            fixture.runtime.dispatch(first, fixture.ticket).reason());
    }

    @Test
    void configChangeDropsUnsentWorkAndKeepsSentRisk() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent first = fixture.place();
        RiskId sentRisk = fixture.reserve(first);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(first, fixture.ticket).status());
        Intent second = fixture.placeAt(new dev.tntcoating.core.combat.Cell(2, 64, 0));
        RiskId unsentRisk = fixture.reserve(second);

        fixture.runtime.onConfigChanged();

        assertEquals(CombatRiskBudget.State.SENT, fixture.budget.state(sentRisk));
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(unsentRisk));
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(CombatRuntime.Reason.TICKET_INVALID,
            fixture.runtime.dispatch(first, fixture.ticket).reason());
    }

    @Test
    void connectionCloseClearsEveryRiskAndLease() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent first = fixture.place();
        fixture.reserve(first);
        assertEquals(CombatRuntime.Status.SENT, fixture.runtime.dispatch(first, fixture.ticket).status());
        Intent second = fixture.attack();
        fixture.reserve(second);

        fixture.runtime.onConnectionClosed();

        assertEquals(0, fixture.budget.outstanding());
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(0, fixture.scheduler.heldCells().size());
        assertEquals(0.0, fixture.budget.debt(fixture.self()).bound(), 1.0e-9);
        assertFalse(fixture.session.isOpen());
        fixture.runtime.close();
        assertTrue(fixture.runtime.closed());
    }

    @Test
    void queuedObservationOverflowSuspendsAndReconciliationResumes() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        for (int index = 0; index < 70; index++) {
            ObservationIntake.Receipt receipt =
                fixture.intake.record(1, -1, 0, new ObservationIntake.RawSetback());
            fixture.intake.apply(receipt, null);
        }
        assertTrue(fixture.intake.overflowed());
        fixture.runtime.tick(1);
        assertTrue(fixture.budget.suspended());

        assertEquals(CombatRuntime.Reason.SUSPENDED,
            fixture.runtime.dispatch(intent, fixture.ticket).reason());
        assertEquals(0, fixture.sink.attempts);

        fixture.runtime.onReconciled();
        assertFalse(fixture.intake.overflowed());
        assertFalse(fixture.budget.suspended());
    }

    @Test
    void overflowIsSuspendedAtTheDispatchBoundaryBeforeTheNextTick() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        for (int index = 0; index < 70; index++) {
            ObservationIntake.Receipt receipt =
                fixture.intake.record(1, -1, 0, new ObservationIntake.RawSetback());
            fixture.intake.apply(receipt, null);
        }
        assertTrue(fixture.intake.overflowed());
        Intent intent = fixture.place();
        fixture.reserve(intent);
        assertEquals(CombatRuntime.Reason.SUSPENDED,
            fixture.runtime.dispatch(intent, fixture.ticket).reason());
        assertEquals(0, fixture.sink.attempts);
        assertTrue(fixture.budget.suspended());
    }

    @Test
    void queuedRespawnStartsANewConnectionEpochAndInvalidatesWork() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        ConnectionKey previous = fixture.session.connection();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        // The first respawn only establishes the dimension; discard its observation so the change is next.
        ObservationIntake.Receipt seed =
            fixture.intake.record(1, -1, 0, new ObservationIntake.RawRespawn("minecraft:overworld"));
        fixture.intake.apply(seed, null);
        fixture.intake.drain();
        ObservationIntake.Receipt receipt =
            fixture.intake.record(1, -1, 0, new ObservationIntake.RawRespawn("minecraft:the_nether"));
        fixture.intake.apply(receipt, null);

        fixture.runtime.tick(1);

        assertNotEquals(previous, fixture.session.connection());
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(CombatRuntime.Reason.TICKET_INVALID,
            fixture.runtime.dispatch(intent, fixture.ticket).reason());
    }

    @Test
    void queuedDeathInvalidatesWorkAndSuspends() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        RiskId riskId = fixture.reserve(intent);
        ObservationIntake.Receipt receipt =
            fixture.intake.record(1, -1, 0, new ObservationIntake.RawHealth(0.0f, 20, 5.0f));
        fixture.intake.apply(receipt, null);

        fixture.runtime.tick(1);

        assertTrue(fixture.session.isOpen(), "death keeps the connection open");
        assertEquals(CombatRiskBudget.State.RETIRED, fixture.budget.state(riskId));
        assertEquals(0, fixture.scheduler.leaseCount());
        assertTrue(fixture.budget.suspended());
    }

    @Test
    void closeIsIdempotentAndClearsConnectionState() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.runtime.close();
        fixture.runtime.close();
        assertTrue(fixture.runtime.closed());
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(0, fixture.budget.outstanding());
    }

    @Test
    void contextIsRemovedWhenTheIntentFailsBeforeDispatch() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        fixture.client.screenOpen = true;
        assertEquals(CombatRuntime.Reason.SCREEN_OPEN,
            fixture.runtime.dispatch(intent, fixture.ticket).reason());
        assertEquals(0, fixture.scheduler.leaseCount());
        assertEquals(0, fixture.budget.outstanding());
    }

    @Test
    void plannedGenerationEndsOnlyOncePerBoundary() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        Intent intent = fixture.place();
        fixture.reserve(intent);
        assertEquals(1, fixture.runtime.tickCount());
        fixture.runtime.onDeath();
        fixture.runtime.onDeath();
        assertEquals(1, fixture.runtime.tickCount());
        assertEquals(0, fixture.scheduler.leaseCount());
    }

    @Test
    void explosionObserverReceivesOnlyAppliedExplosionCentresAndSendsNothing() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        List<Vec> centres = new ArrayList<>();
        fixture.runtime.setExplosionObserver(centres::add);
        int attempts = fixture.sink.attempts;

        fixture.intake.enqueueForTest(blockUpdateObservation(fixture, 1L));
        fixture.runtime.tick(1);
        assertTrue(centres.isEmpty(), "only EXPLOSION observations reach the observer");
        assertEquals(attempts, fixture.sink.attempts, "applying observations never sends");

        Vec centre = new Vec(0.5, -59.0, 0.5);
        fixture.intake.enqueueForTest(explosionObservation(fixture, 2L, centre));
        fixture.runtime.tick(2);
        assertEquals(List.of(centre), centres);
        assertEquals(attempts, fixture.sink.attempts, "the passive observer sends nothing");

        fixture.runtime.setExplosionObserver(null);
        fixture.intake.enqueueForTest(explosionObservation(fixture, 3L, new Vec(3.5, -60.0, 0.5)));
        fixture.runtime.tick(3);
        assertEquals(List.of(centre), centres, "null restores the no-op observer");
        assertEquals(attempts, fixture.sink.attempts);
    }

    @Test
    void explosionWithoutAPositionIsNotReported() {
        CombatRuntimeFixture fixture = new CombatRuntimeFixture();
        List<Vec> centres = new ArrayList<>();
        fixture.runtime.setExplosionObserver(centres::add);
        fixture.intake.enqueueForTest(explosionObservation(fixture, 1L, null));
        fixture.runtime.tick(1);
        assertTrue(centres.isEmpty());
        assertEquals(0, fixture.sink.attempts);
    }

    private static Observation explosionObservation(CombatRuntimeFixture fixture, long receiptSeq,
                                                    Vec centre) {
        return new Observation(fixture.session.connection(), receiptSeq, 0L, System.nanoTime(), 0,
            ObservationKind.EXPLOSION, null, null, centre, null,
            new ObservationPayload.Explosion(6.0f));
    }

    private static Observation blockUpdateObservation(CombatRuntimeFixture fixture, long receiptSeq) {
        Cell cell = new Cell(0, -61, 0);
        return new Observation(fixture.session.connection(), receiptSeq, 0L, System.nanoTime(), 0,
            ObservationKind.BLOCK_UPDATE, null, cell, cell.center(), null,
            new ObservationPayload.BlockUpdate("minecraft:stone", "minecraft:air", false));
    }
}
