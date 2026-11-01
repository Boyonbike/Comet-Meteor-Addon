/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Phase 3.2/3.6: a verified break may share the risk the placement already reserved. */
class CombatSchedulerAttachTest {
    private CombatSession session;
    private CombatRiskBudget risk;
    private PacketLedger packets;
    private CombatScheduler scheduler;
    private Ticket ticket;
    private EntityKey self;

    @BeforeEach
    void setUp() {
        session = new CombatSession();
        session.open();
        ticket = session.ticket(1L);
        risk = RiskTestSupport.conservative();
        packets = new PacketLedger(40, 20);
        scheduler = new CombatScheduler(session, risk, packets);
        self = new EntityKey(ticket.conn(), 1, UUID.nameUUIDFromBytes(
            "attach-self".getBytes(StandardCharsets.UTF_8)));
    }

    private SafetyState safety() {
        return new SafetyState(ticket.conn(), ticket.gen(), self,
            RiskTestSupport.known(20.0, 0.0, 0L), Map.of(), RiskTestSupport.SAFETY);
    }

    private RiskRequest request(int tick, int cost) {
        return new RiskRequest(RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(),
            safety(), null, null, cost, tick, false, false, false);
    }

    private Snapshot snapshot() {
        return new Snapshot(ticket.conn(), 2L, List.of());
    }

    private Intent place(long id) {
        return RiskTestSupport.intent(id, 1L, new Cell((int) id, 64, 0), 20,
            Set.of(new Cell((int) id, 64, 0)), Set.of(), Set.of());
    }

    private Intent attack(long id, EntityKey crystal) {
        return new Intent(id, 1L, Strategy.DIRECT, Action.ATTACK_CRYSTAL, null, crystal,
            Set.of(), Set.of(crystal), Set.of(), 20);
    }

    private Footprint footprint(EntityKey crystal) {
        return new Footprint(Set.of(), Box.fromCell(new Cell(0, 64, 0)),
            Box.fromCell(new Cell(0, 64, 0)), Set.of(crystal));
    }

    private RiskId sendFirst(Intent intent) {
        assertTrue(scheduler.reserve(intent, ticket, request(0, 2)));
        RiskId id = scheduler.riskId(1L, intent.id()).orElseThrow();
        assertTrue(scheduler.dispatchStarted(1L, intent.id()));
        scheduler.sent(intent, 0, 2L);
        assertSame(CombatRiskBudget.State.SENT, risk.state(id));
        assertTrue(scheduler.releaseSent(1L, intent.id()));
        return id;
    }

    @Test
    void attachReusesTheSentRiskWithoutReservingAgain() {
        Intent placement = place(1L);
        RiskId id = sendFirst(placement);
        double debtBefore = risk.debt(self).bound();
        int countBefore = risk.debt(self).count();

        Intent breakIntent = attack(2L, RiskTestSupport.CRYSTAL);
        assertTrue(scheduler.attach(breakIntent, ticket, id, 2, 1, false, false, false,
            footprint(RiskTestSupport.CRYSTAL)));
        assertEquals(id, scheduler.riskId(1L, 2L).orElseThrow());
        assertTrue(scheduler.ready(breakIntent, snapshot()));
        // A sent risk is already DISPATCHING-equivalent; dispatchStarted is a no-op.
        assertTrue(scheduler.dispatchStarted(1L, 2L));
        scheduler.sent(breakIntent, 1, 2L);
        assertSame(CombatRiskBudget.State.SENT, risk.state(id));
        assertEquals(countBefore, risk.debt(self).count());
        assertEquals(debtBefore, risk.debt(self).bound(), 1e-9);
    }

    @Test
    void attachRefusesAReservedRisk() {
        Intent placement = place(1L);
        assertTrue(scheduler.reserve(placement, ticket, request(0, 2)));
        RiskId id = scheduler.riskId(1L, 1L).orElseThrow();
        assertSame(CombatRiskBudget.State.RESERVED, risk.state(id));

        assertFalse(scheduler.attach(attack(2L, RiskTestSupport.CRYSTAL), ticket, id, 2, 1,
            false, false, false, footprint(RiskTestSupport.CRYSTAL)));
    }

    @Test
    void attachRefusesUnknownRiskAndForeignTicket() {
        assertFalse(scheduler.attach(attack(2L, RiskTestSupport.CRYSTAL), ticket,
            new RiskId(999L), 2, 1, false, false, false, footprint(RiskTestSupport.CRYSTAL)));

        Intent placement = place(1L);
        RiskId id = sendFirst(placement);
        Ticket foreign = new Ticket(new ConnectionKey(88L), ticket.gen(), ticket.config(), 1L);
        assertFalse(scheduler.attach(attack(2L, RiskTestSupport.CRYSTAL), foreign, id, 2, 1,
            false, false, false, footprint(RiskTestSupport.CRYSTAL)));
    }

    @Test
    void attachRecountsPacketsAndRefusesWhenTheBudgetIsFull() {
        Intent placement = place(1L);
        RiskId id = sendFirst(placement);
        PacketLedger tiny = new PacketLedger(2, 20);
        CombatScheduler limited = new CombatScheduler(session, risk, tiny);
        assertTrue(limited.attach(attack(2L, RiskTestSupport.CRYSTAL), ticket, id, 2, 1,
            false, false, false, footprint(RiskTestSupport.CRYSTAL)));
        // The first attachment consumed the whole budget: a second intent cannot attach.
        assertFalse(limited.attach(attack(3L, RiskTestSupport.CRYSTAL), ticket, id, 2, 1,
            false, false, false, footprint(RiskTestSupport.CRYSTAL)));
    }

    @Test
    void attachStaysRefusedForARetiredRisk() {
        Intent placement = place(1L);
        RiskId id = sendFirst(placement);
        risk.connectionClosed(ticket.conn());
        assertSame(CombatRiskBudget.State.RETIRED, risk.state(id));
        // A retired risk is not attachable.
        assertFalse(scheduler.attach(attack(2L, RiskTestSupport.CRYSTAL), ticket, id, 2, 1,
            false, false, false, footprint(RiskTestSupport.CRYSTAL)));
    }
}
