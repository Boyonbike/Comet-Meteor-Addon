/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CombatSchedulerTest {
    private CombatSession session;
    private CombatScheduler scheduler;
    private CombatRiskBudget risk;
    private Ticket ticket;
    private EntityKey self;
    private EntityKey crystal;
    private Cell cell;

    @BeforeEach
    void setUp() {
        session = new CombatSession();
        session.open();
        ticket = session.ticket(1L);
        risk = RiskTestSupport.conservative();
        scheduler = new CombatScheduler(session, risk, new PacketLedger(40, 20));
        self = new EntityKey(ticket.conn(), 1, UUID.nameUUIDFromBytes(
            "scheduler-self".getBytes(StandardCharsets.UTF_8)));
        crystal = new EntityKey(ticket.conn(), 3, null);
        cell = new Cell(0, 64, 0);
    }

    private SafetyState safety(double health) {
        return new SafetyState(ticket.conn(), ticket.gen(), self,
            RiskTestSupport.known(health, 0.0, 0L), Map.of(), RiskTestSupport.SAFETY);
    }

    private RiskRequest request(int tick, double selfBound, int cost, boolean slot,
                                boolean rotation, boolean mining) {
        return new RiskRequest(RiskTestSupport.exposure(selfBound), RiskTestSupport.arrival(),
            safety(20.0), crystal, RiskTestSupport.CRYSTAL_POSITION, cost, tick, slot, rotation,
            mining);
    }

    private RiskRequest request(int tick, double selfBound) {
        return request(tick, selfBound, 1, false, false, false);
    }

    private Intent intent(long id, Cell target, int expires) {
        return RiskTestSupport.intent(id, 1L, target, expires, Set.of(target), Set.of(), Set.of());
    }

    private Intent intentWithEntityLock(long id, Cell target, int expires) {
        return RiskTestSupport.intent(id, 1L, target, expires, Set.of(target), Set.of(self),
            Set.of());
    }

    private Intent dependentIntent(long id, Cell target, int expires, Set<Intent> dependencies) {
        return RiskTestSupport.intent(id, 1L, target, expires, Set.of(target), Set.of(self),
            dependencies);
    }

    private Snapshot snapshot() {
        return new Snapshot(ticket.conn(), 0L, List.of());
    }

    @Test
    void reserveAcquiresEveryResourceAtomically() {
        Intent intent = intentWithEntityLock(1L, cell, 100);
        assertTrue(scheduler.reserve(intent, ticket, request(0, 6.0, 3, true, true, true)));
        assertEquals(1, scheduler.leaseCount());
        assertEquals(Set.of(cell), scheduler.heldCells());
        assertEquals(Set.of(self), scheduler.heldEntities());
        assertTrue(scheduler.hotbarSlotHeld());
        assertTrue(scheduler.rotationHeld());
        assertTrue(scheduler.miningSlotHeld());
        assertEquals(3, scheduler.packetUsed(0));
        assertEquals(CombatRiskBudget.State.RESERVED, risk.state(new RiskId(1L)));

        Lease lease = scheduler.lease(1L, 1L).orElseThrow();
        assertFalse(lease.sent());
        assertEquals(1L, lease.plan());
        assertEquals(Strategy.DIRECT, lease.strategy());
        assertEquals(100, lease.expiresTick());
        assertEquals(Set.of(cell), lease.footprint().structural());
        assertEquals(ticket, lease.ticket());
        assertEquals(intent, lease.intent());
        assertTrue(scheduler.holder(cell).isPresent());
    }

    @Test
    void leaseCarriesTheRequestedFootprint() {
        Box swept = Box.around(RiskTestSupport.CRYSTAL_POSITION, 0.5);
        Box blastSearch = Box.around(RiskTestSupport.CRYSTAL_POSITION, 6.0);
        Footprint footprint = new Footprint(Set.of(cell), swept, blastSearch, Set.of(self));
        RiskRequest request = new RiskRequest(RiskTestSupport.exposure(6.0),
            RiskTestSupport.arrival(), safety(20.0), crystal, RiskTestSupport.CRYSTAL_POSITION,
            1, 0, false, false, false, footprint);
        assertTrue(scheduler.reserve(intentWithEntityLock(1L, cell, 100), ticket, request));
        Footprint carried = scheduler.holder(cell).orElseThrow().footprint();
        assertEquals(footprint, carried);
        assertEquals(swept, carried.swept());
        assertEquals(blastSearch, carried.blastSearch());
        assertEquals(Set.of(self), carried.bound());
    }

    @Test
    void cellConflictLeavesNoPartialAcquisition() {
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket,
            request(0, 3.0, 2, true, true, true)));
        Intent second = intent(2L, cell, 100);
        assertFalse(scheduler.reserve(second, ticket, request(0, 3.0, 2, true, true, true)));

        assertEquals(1, scheduler.leaseCount());
        assertEquals(Set.of(cell), scheduler.heldCells());
        assertEquals(3.0, risk.current(self).value());
        assertEquals(2, scheduler.packetUsed(0));
        assertTrue(scheduler.hotbarSlotHeld());
        assertTrue(scheduler.rotationHeld());
        assertTrue(scheduler.miningSlotHeld());
    }

    @Test
    void entityConflictLeavesNoPartialAcquisition() {
        assertTrue(scheduler.reserve(intentWithEntityLock(1L, cell, 100), ticket,
            request(0, 3.0, 2, false, false, false)));
        Cell other = new Cell(5, 64, 0);
        Intent second = RiskTestSupport.intent(2L, 1L, other, 100, Set.of(other), Set.of(self),
            Set.of());
        assertFalse(scheduler.reserve(second, ticket, request(0, 3.0, 2, true, true, true)));

        assertEquals(1, scheduler.leaseCount());
        assertFalse(scheduler.heldCells().contains(other));
        assertFalse(scheduler.hotbarSlotHeld());
        assertEquals(2, scheduler.packetUsed(0));
    }

    @Test
    void hotbarSlotConflictLeavesNoPartialAcquisition() {
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket,
            request(0, 3.0, 2, true, false, false)));
        Cell other = new Cell(5, 64, 0);
        Intent second = RiskTestSupport.intent(2L, 1L, other, 100, Set.of(other), Set.of(), Set.of());
        assertFalse(scheduler.reserve(second, ticket, request(0, 3.0, 2, true, false, false)));

        assertEquals(1, scheduler.leaseCount());
        assertEquals(2, scheduler.packetUsed(0));
        assertFalse(scheduler.heldCells().contains(other));
    }

    @Test
    void rotationConflictLeavesNoPartialAcquisition() {
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket,
            request(0, 3.0, 2, false, true, false)));
        Cell other = new Cell(5, 64, 0);
        Intent second = RiskTestSupport.intent(2L, 1L, other, 100, Set.of(other), Set.of(), Set.of());
        assertFalse(scheduler.reserve(second, ticket, request(0, 3.0, 2, false, true, false)));

        assertEquals(1, scheduler.leaseCount());
        assertTrue(scheduler.rotationHeld());
        assertEquals(2, scheduler.packetUsed(0));
    }

    @Test
    void miningSlotConflictLeavesNoPartialAcquisition() {
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket,
            request(0, 3.0, 2, false, false, true)));
        Cell other = new Cell(5, 64, 0);
        Intent second = RiskTestSupport.intent(2L, 1L, other, 100, Set.of(other), Set.of(), Set.of());
        assertFalse(scheduler.reserve(second, ticket, request(0, 3.0, 2, false, false, true)));

        assertEquals(1, scheduler.leaseCount());
        assertTrue(scheduler.miningSlotHeld());
        assertEquals(2, scheduler.packetUsed(0));
    }

    @Test
    void packetExhaustionLeavesNoPartialAcquisition() {
        scheduler = new CombatScheduler(session, risk, new PacketLedger(5, 20));
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket,
            request(0, 3.0, 3, false, false, false)));
        Cell other = new Cell(5, 64, 0);
        Intent second = RiskTestSupport.intent(2L, 1L, other, 100, Set.of(other), Set.of(), Set.of());
        assertFalse(scheduler.reserve(second, ticket, request(0, 3.0, 3, true, true, true)));

        assertEquals(1, scheduler.leaseCount());
        assertFalse(scheduler.heldCells().contains(other));
        assertFalse(scheduler.hotbarSlotHeld());
        assertFalse(scheduler.rotationHeld());
        assertFalse(scheduler.miningSlotHeld());
        assertEquals(3, scheduler.packetUsed(0));
        assertEquals(1, risk.outstanding());
    }

    @Test
    void riskRefusalRollsBackThePacketReservationAndLocks() {
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket, request(0, 6.0, 1, false,
            false, false)));
        assertTrue(scheduler.reserve(intent(2L, new Cell(1, 64, 0), 100), ticket,
            request(0, 6.0, 1, false, false, false)));
        Intent third = RiskTestSupport.intent(3L, 1L, new Cell(2, 64, 0), 100,
            Set.of(new Cell(2, 64, 0)), Set.of(), Set.of());
        assertFalse(scheduler.reserve(third, ticket, request(0, 6.0, 1, true, true, true)));

        assertEquals(2, risk.outstanding());
        assertEquals(2, scheduler.packetUsed(0));
        assertFalse(scheduler.heldCells().contains(new Cell(2, 64, 0)));
        assertFalse(scheduler.hotbarSlotHeld());
        assertFalse(scheduler.rotationHeld());
        assertFalse(scheduler.miningSlotHeld());
    }

    @Test
    void duplicateLeaseIsRefused() {
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket, request(0, 3.0)));
        assertFalse(scheduler.reserve(intent(1L, new Cell(2, 64, 0), 100), ticket,
            request(0, 3.0)));
        assertEquals(1, scheduler.leaseCount());
        assertEquals(1, scheduler.packetUsed(0));
    }

    @Test
    void nullsWrongPlanAndInvalidTicketsAreRefused() {
        assertFalse(scheduler.reserve(null, ticket, request(0, 1.0)));
        assertFalse(scheduler.reserve(intent(1L, cell, 100), null, request(0, 1.0)));
        assertFalse(scheduler.reserve(intent(1L, cell, 100), ticket, null));
        Ticket otherPlan = session.ticket(2L);
        assertFalse(scheduler.reserve(intent(1L, cell, 100), otherPlan, request(0, 1.0)));
        assertEquals(0, scheduler.leaseCount());
    }

    @Test
    void cleanupAndDefenceShareTheOnePacketBudget() {
        scheduler = new CombatScheduler(session, risk, new PacketLedger(4, 20));
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket,
            request(0, 3.0, 3, false, false, false)));
        Intent cleanup = new Intent(2L, 1L, Strategy.CLEANUP, Action.MINE_POWER,
            new Cell(1, 64, 0), null, Set.of(new Cell(1, 64, 0)), Set.of(), Set.of(), 100);
        assertFalse(scheduler.reserve(cleanup, ticket, request(0, 0.0, 2, false, false, false)));
        assertEquals(1, scheduler.leaseCount());

        Intent defend = new Intent(3L, 1L, Strategy.DEFEND, Action.PLACE_BLOCKER,
            new Cell(2, 64, 0), null, Set.of(new Cell(2, 64, 0)), Set.of(), Set.of(), 100);
        assertFalse(scheduler.reserve(defend, ticket, request(0, 0.0, 2, false, false, false)));

        scheduler.tick(21);
        assertTrue(scheduler.reserve(cleanup, ticket, request(21, 0.0, 2, false, false, false)));
        assertEquals(2, scheduler.packetUsed(21));
        assertEquals(4, scheduler.packetCapacity());
        assertEquals(2, scheduler.leaseCount());
        assertEquals(2, risk.outstanding());
    }

    @Test
    void eventTriggeredReservationUsesTheSameRiskAndPacketPath() {
        scheduler = new CombatScheduler(session, risk, new PacketLedger(10, 20));
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket,
            request(0, 6.0, 3, false, false, false)));
        assertTrue(scheduler.reserve(intent(2L, new Cell(1, 64, 0), 100), ticket,
            request(0, 6.0, 2, false, false, false)));
        assertEquals(2, risk.outstanding());

        Intent event = new Intent(3L, 1L, Strategy.CLEANUP, Action.MINE_POWER,
            new Cell(2, 64, 0), null, Set.of(new Cell(2, 64, 0)), Set.of(), Set.of(), 100);
        assertFalse(scheduler.reserve(event, ticket, request(0, 6.0, 1, false, false, false)));
        // The event path acquired its packet reservation then rolled it back when the risk was refused.
        assertEquals(5, scheduler.packetUsed(0));
        assertEquals(2, risk.outstanding());
        assertEquals(2, scheduler.leaseCount());
    }

    @Test
    void expiredIntentIsNotReadyAndUnsentRiskIsReleased() {
        Intent intent = intent(1L, cell, 10);
        assertTrue(scheduler.reserve(intent, ticket, request(0, 3.0, 2, true, true, true)));
        assertTrue(scheduler.ready(intent, snapshot()));

        scheduler.tick(9);
        assertTrue(scheduler.ready(intent, snapshot()));
        scheduler.tick(10);
        assertFalse(scheduler.ready(intent, snapshot()));
        assertEquals(0, scheduler.leaseCount());
        assertTrue(scheduler.holder(cell).isEmpty());
        assertEquals(CombatRiskBudget.State.RETIRED, risk.state(new RiskId(1L)));
        assertEquals(0, scheduler.packetUsed(0));
        assertFalse(scheduler.hotbarSlotHeld());
        assertFalse(scheduler.rotationHeld());
        assertFalse(scheduler.miningSlotHeld());
    }

    @Test
    void sentRiskSurvivesExpiryAndKeepsItsFootprint() {
        Intent intent = intent(1L, cell, 10);
        assertTrue(scheduler.reserve(intent, ticket, request(0, 3.0, 2, true, true, true)));
        scheduler.sent(intent, 1);
        scheduler.tick(10);

        assertEquals(1, scheduler.leaseCount());
        assertTrue(scheduler.lease(1L, 1L).orElseThrow().sent());
        assertTrue(scheduler.holder(cell).isPresent());
        assertEquals(CombatRiskBudget.State.SENT, risk.state(new RiskId(1L)));
        assertEquals(1, risk.debt(self).count());
        assertFalse(scheduler.hotbarSlotHeld());
        assertFalse(scheduler.rotationHeld());
        assertFalse(scheduler.miningSlotHeld());
        assertEquals(2, scheduler.packetUsed(0));
    }

    @Test
    void reserveAfterExpiryIsRefused() {
        Intent intent = intent(1L, cell, 10);
        scheduler.tick(10);
        assertFalse(scheduler.reserve(intent, ticket, request(10, 1.0)));
        assertEquals(0, scheduler.leaseCount());
        assertTrue(scheduler.holder(cell).isEmpty());
    }

    @Test
    void negativeTickIsIgnoredByTheClock() {
        Intent intent = intent(1L, cell, 5);
        assertTrue(scheduler.reserve(intent, ticket, request(0, 1.0)));
        scheduler.tick(-1);
        assertTrue(scheduler.ready(intent, snapshot()));
    }

    @Test
    void staleTicketIsNeverReadyOrReservable() {
        Intent intent = intent(1L, cell, 100);
        assertTrue(scheduler.reserve(intent, ticket, request(0, 1.0)));
        session.reconfigure();
        assertFalse(session.valid(ticket));
        assertFalse(scheduler.ready(intent, snapshot()));
        assertFalse(scheduler.reserve(intent(2L, new Cell(1, 64, 0), 100), ticket, request(1, 1.0)));

        scheduler.invalidate(ticket);
        assertEquals(0, scheduler.leaseCount());
        assertTrue(scheduler.holder(cell).isEmpty());
    }

    @Test
    void endedActionGenerationIsNeverDispatchable() {
        Intent intent = intent(1L, cell, 100);
        assertTrue(scheduler.reserve(intent, ticket, request(0, 1.0)));
        session.respawn();
        assertFalse(session.valid(ticket));
        assertFalse(scheduler.ready(intent, snapshot()));
        assertFalse(scheduler.reserve(intent(2L, new Cell(1, 64, 0), 100), ticket, request(1, 1.0)));

        risk.actionGenerationEnded(ticket.gen());
        assertEquals(CombatRiskBudget.State.RETIRED, risk.state(new RiskId(1L)));
        scheduler.invalidate(ticket);
        assertEquals(0, scheduler.leaseCount());
    }

    @Test
    void snapshotFromAnotherConnectionIsNotReady() {
        Intent intent = intent(1L, cell, 100);
        assertTrue(scheduler.reserve(intent, ticket, request(0, 1.0)));
        assertFalse(scheduler.ready(intent,
            new Snapshot(new ConnectionKey(999L), 0L, List.of())));
        assertTrue(scheduler.ready(intent, snapshot()));
    }

    @Test
    void dependenciesMustAlreadyBeSent() {
        Intent first = intent(1L, cell, 100);
        Intent second = dependentIntent(2L, new Cell(1, 64, 0), 100, Set.of(first));
        assertTrue(scheduler.reserve(first, ticket, request(0, 1.0)));
        assertTrue(scheduler.reserve(second, ticket, request(0, 1.0)));
        assertTrue(scheduler.ready(first, snapshot()));
        assertFalse(scheduler.ready(second, snapshot()));

        scheduler.sent(first, 0);
        assertTrue(scheduler.ready(second, snapshot()));
    }

    @Test
    void invalidateDropsUnsentLeasesAndKeepsSentRiskAsDebt() {
        Intent first = intent(1L, cell, 100);
        Intent second = intent(2L, new Cell(1, 64, 0), 100);
        assertTrue(scheduler.reserve(first, ticket, request(0, 1.0, 2, false, false, false)));
        assertTrue(scheduler.reserve(second, ticket, request(0, 1.0, 2, false, false, false)));
        scheduler.sent(first, 0);

        scheduler.invalidate(ticket);
        assertEquals(1, scheduler.leaseCount());
        assertTrue(scheduler.holder(cell).isPresent());
        assertTrue(scheduler.holder(new Cell(1, 64, 0)).isEmpty());
        assertEquals(CombatRiskBudget.State.SENT, risk.state(new RiskId(1L)));
        assertEquals(CombatRiskBudget.State.RETIRED, risk.state(new RiskId(2L)));
        assertEquals(2, scheduler.packetUsed(0));
    }

    @Test
    void releasePlanReleasesCellsButKeepsSentDebt() {
        Intent intent = intent(1L, cell, 100);
        assertTrue(scheduler.reserve(intent, ticket, request(0, 1.0, 2, true, true, true)));
        scheduler.sent(intent, 0);
        scheduler.release(1L);

        assertEquals(0, scheduler.leaseCount());
        assertTrue(scheduler.heldCells().isEmpty());
        assertTrue(scheduler.heldEntities().isEmpty());
        assertFalse(scheduler.hotbarSlotHeld());
        assertTrue(scheduler.holder(cell).isEmpty());
        assertEquals(CombatRiskBudget.State.SENT, risk.state(new RiskId(1L)));
        assertEquals(1, risk.debt(self).count());
    }

    @Test
    void releasePlanReleasesUnsentRiskAndPacketCapacity() {
        Intent intent = intent(1L, cell, 100);
        assertTrue(scheduler.reserve(intent, ticket, request(0, 1.0, 2, true, true, true)));
        scheduler.release(1L);

        assertEquals(0, scheduler.leaseCount());
        assertEquals(0, scheduler.packetUsed(0));
        assertEquals(CombatRiskBudget.State.RETIRED, risk.state(new RiskId(1L)));
        assertFalse(scheduler.hotbarSlotHeld());
        assertTrue(risk.debt(self).count() == 0);
    }

    @Test
    void releaseIgnoresUnknownPlanAndNullsAreNoOps() {
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket, request(0, 1.0)));
        scheduler.release(99L);
        assertEquals(1, scheduler.leaseCount());
        scheduler.invalidate(null);
        assertTrue(scheduler.holder(null).isEmpty());
        assertFalse(scheduler.ready(null, snapshot()));
        assertFalse(scheduler.ready(intent(1L, cell, 100), null));
    }

    @Test
    void sentWithoutLeaseThrowsAndNegativeTickIsRejected() {
        assertThrows(IllegalStateException.class,
            () -> scheduler.sent(intent(9L, new Cell(9, 64, 0), 100), 0));
        Intent intent = intent(1L, cell, 100);
        assertTrue(scheduler.reserve(intent, ticket, request(0, 1.0)));
        assertThrows(IllegalArgumentException.class, () -> scheduler.sent(intent, -1));
    }

    @Test
    void reserveClampsPacketWindowToTheAdvancedClock() {
        scheduler = new CombatScheduler(session, risk, new PacketLedger(40, 20));
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket, request(0, 1.0, 3, false,
            false, false)));
        scheduler.tick(5);
        Intent second = intent(2L, new Cell(1, 64, 0), 100);
        assertTrue(scheduler.reserve(second, ticket, request(2, 1.0, 2, false, false, false)));
        assertEquals(5, scheduler.packetUsed(5));
    }

    @Test
    void holderOnlyReportsTheOwningLease() {
        assertTrue(scheduler.reserve(intent(1L, cell, 100), ticket, request(0, 1.0)));
        Optional<Lease> found = scheduler.holder(cell);
        assertTrue(found.isPresent());
        assertEquals(1L, found.orElseThrow().plan());
        assertTrue(scheduler.holder(new Cell(9, 64, 9)).isEmpty());
    }
}
