/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatSessionTest {
    private static final UUID ENTITY = UUID.fromString("c951e91e-667a-3489-bc2c-fff4bec3d0cb");

    @Test
    void identicalNumericIdsAcrossWorldEpochsAreDistinctEntityKeys() {
        CombatSession session = new CombatSession();
        WorldKey first = session.open();
        EntityKey firstEntity = new EntityKey(first.connection(), 7, ENTITY);

        session.close();
        WorldKey second = session.open();
        EntityKey secondEntity = new EntityKey(second.connection(), 7, ENTITY);

        assertNotEquals(first.connection(), second.connection());
        assertNotEquals(firstEntity, secondEntity);
    }

    @Test
    void reconfigureInvalidatesOutstandingTickets() {
        CombatSession session = new CombatSession();
        session.open();
        Ticket ticket = session.ticket(11);

        assertTrue(session.valid(ticket));
        session.reconfigure();

        assertFalse(session.valid(ticket), "a config change must invalidate queued work");
        Ticket fresh = session.ticket(12);
        assertTrue(session.valid(fresh));
    }

    @Test
    void closeThenOpenNeverValidatesAnOldTicket() {
        CombatSession session = new CombatSession();
        session.open();
        Ticket old = session.ticket(1);

        session.close();
        assertFalse(session.valid(old), "a closed session validates nothing");

        session.open();
        assertFalse(session.valid(old), "a reopened session is a new connection epoch");
        assertTrue(session.valid(session.ticket(1)));
    }

    @Test
    void actionGenerationEndsOnDeathRespawnConfigChangeAndDisable() {
        CombatSession session = new CombatSession();
        session.open();
        Ticket ticket = session.ticket(1);

        ActionGeneration before = session.actionGeneration();
        session.death();
        ActionGeneration afterDeath = session.actionGeneration();
        assertNotEquals(before, afterDeath);
        assertFalse(session.valid(ticket), "death ends every in-flight action");

        Ticket afterDeathTicket = session.ticket(2);
        session.respawn();
        assertNotEquals(afterDeath, session.actionGeneration());
        assertFalse(session.valid(afterDeathTicket));

        Ticket afterRespawnTicket = session.ticket(3);
        session.reconfigure();
        assertFalse(session.valid(afterRespawnTicket));

        Ticket afterConfigTicket = session.ticket(4);
        session.disable();
        assertFalse(session.valid(afterConfigTicket));
    }

    @Test
    void aTicketFromAnEarlierActionGenerationIsNeverValid() {
        CombatSession session = new CombatSession();
        session.open();
        Ticket old = session.ticket(1);

        session.death();
        Ticket fresh = session.ticket(2);

        assertFalse(session.valid(old));
        assertTrue(session.valid(fresh));
        assertFalse(session.valid(null));
    }

    @Test
    void ticketsBeforeOpenAreRejected() {
        CombatSession session = new CombatSession();
        assertThrows(IllegalStateException.class, () -> session.ticket(1));
        assertFalse(session.isOpen());
    }

    @Test
    void closeIsIdempotentAndNeverRequiresOrTouchesARuntime() {
        // CombatSession has no runtime, packet or callback dependency by construction: closing only
        // changes session state. The purity guard proves the class cannot reach a send path.
        CombatSession session = new CombatSession();
        WorldKey key = session.open();
        assertNotNull(key.connection());

        session.close();
        session.close();

        assertFalse(session.isOpen());
        assertThrows(IllegalStateException.class, () -> session.ticket(1));
    }

    @Test
    void countersAreMonotonicAndTicketsKeepTheirPlanIdentity() {
        CombatSession session = new CombatSession();
        session.open();
        Ticket first = session.ticket(41);
        Ticket second = session.ticket(42);

        assertEquals(41, first.plan());
        assertEquals(42, second.plan());
        assertTrue(second.config() >= first.config());
        assertEquals(first.config(), second.config(), "a ticket carries the config generation it was made in");
    }

    @Test
    void aDimensionChangeMintsANewConnectionEpochAndEndsInFlightWork() {
        CombatSession session = new CombatSession();
        WorldKey before = session.open();
        Ticket ticket = session.ticket(1);

        ConnectionKey changed = session.dimensionChanged();

        assertNotEquals(before.connection(), changed, "a different world is a different entity epoch");
        assertEquals(changed, session.connection());
        assertFalse(session.valid(ticket), "in-flight actions do not survive a dimension change");
        assertTrue(session.isOpen(), "the connection stays open; only the world epoch changes");
        assertTrue(session.valid(session.ticket(2)));
    }
}
