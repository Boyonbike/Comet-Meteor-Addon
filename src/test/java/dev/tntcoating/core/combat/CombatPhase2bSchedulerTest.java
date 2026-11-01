/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Phase 2b carry-over A: the held lease, not the caller's Intent, owns expiry and dependencies. */
class CombatPhase2bSchedulerTest {
    private CombatSession session;
    private CombatScheduler scheduler;
    private Ticket ticket;
    private EntityKey self;
    private Intent dependency;
    private Intent held;

    @BeforeEach
    void setUp() {
        session = new CombatSession();
        session.open();
        ticket = session.ticket(1L);
        CombatRiskBudget risk = RiskTestSupport.conservative();
        scheduler = new CombatScheduler(session, risk, new PacketLedger(40, 20));
        self = new EntityKey(ticket.conn(), 1, UUID.nameUUIDFromBytes(
            "phase2b-self".getBytes(StandardCharsets.UTF_8)));
        dependency = RiskTestSupport.intent(9L, 1L, new Cell(5, 64, 5), 100,
            Set.of(new Cell(5, 64, 5)), Set.of(), Set.of());
        held = RiskTestSupport.intent(1L, 1L, new Cell(0, 64, 0), 5,
            Set.of(new Cell(0, 64, 0)), Set.of(), Set.of(dependency));
    }

    private SafetyState safety() {
        return new SafetyState(ticket.conn(), ticket.gen(), self,
            RiskTestSupport.known(20.0, 0.0, 0L), Map.of(), RiskTestSupport.SAFETY);
    }

    private RiskRequest request(int tick) {
        return new RiskRequest(RiskTestSupport.exposure(6.0), RiskTestSupport.arrival(), safety(),
            null, null, 1, tick, false, false, false);
    }

    private Snapshot snapshot() {
        return new Snapshot(ticket.conn(), 3L, List.of());
    }

    @Test
    void forgedLaterExpiryCannotExtendHeldLease() {
        assertTrue(scheduler.reserve(held, ticket, request(0)));
        // Advance the scheduler clock past the held expiry without running tick(): a second reservation in
        // a later tick does that. The lease is still stored, so only the held Intent can decide readiness.
        Intent second = RiskTestSupport.intent(2L, 1L, new Cell(1, 64, 0), 100,
            Set.of(new Cell(1, 64, 0)), Set.of(), Set.of());
        assertTrue(scheduler.reserve(second, ticket, request(10)));
        Intent forged = RiskTestSupport.intent(1L, 1L, new Cell(0, 64, 0), 100,
            Set.of(new Cell(0, 64, 0)), Set.of(), Set.of());

        assertFalse(scheduler.ready(forged, snapshot()));
    }

    @Test
    void forgedIntentWithNoDependenciesCannotSkipHeldDependencies() {
        assertTrue(scheduler.reserve(held, ticket, request(0)));
        Intent forged = RiskTestSupport.intent(1L, 1L, new Cell(0, 64, 0), 100,
            Set.of(new Cell(0, 64, 0)), Set.of(), Set.of());

        assertFalse(scheduler.ready(forged, snapshot()));
    }

    @Test
    void declaredBlockLocksAreTheOnlyCellOwnershipTheHolderReports() {
        Cell locked = new Cell(3, 64, 3);
        Cell sweptOnly = new Cell(4, 64, 3);
        EntityKey crystal = new EntityKey(ticket.conn(), 7, UUID.nameUUIDFromBytes(
            "phase2b-crystal".getBytes(StandardCharsets.UTF_8)));
        Intent intent = new Intent(12L, 1L, Strategy.DIRECT, Action.PLACE_CRYSTAL, locked, null,
            Set.of(locked), Set.of(crystal), Set.of(), 100);

        assertTrue(scheduler.reserve(intent, ticket, request(0)));
        assertTrue(scheduler.holder(locked).isPresent());
        assertFalse(scheduler.holder(sweptOnly).isPresent(),
            "swept/blast footprint cells are advisory, not exclusive ownership");
        assertTrue(scheduler.heldEntities().contains(crystal));
    }

    @Test
    void genuineIntentBecomesReadyOnceDependencyIsSent() {
        assertTrue(scheduler.reserve(held, ticket, request(0)));
        assertFalse(scheduler.ready(held, snapshot()));

        // The dependency needs its own request but no effect on the tested intent's lease.
        Intent dependencyIntent = dependency;
        assertTrue(scheduler.reserve(dependencyIntent, ticket,
            new RiskRequest(RiskTestSupport.exposure(0.0), RiskTestSupport.arrival(), safety(),
                null, null, 1, 0, false, false, false)));
        scheduler.sent(dependencyIntent, 0);

        assertTrue(scheduler.ready(held, snapshot()));
    }
}
