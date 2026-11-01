/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Phase 3.4 state machine: one placement, one spawn, at most one duplicate attack, terminal evidence. */
final class DirectSequenceTest {
    private static final ConnectionKey CONN = new ConnectionKey(1L);
    private static final Cell CELL = new Cell(0, 64, 0);
    private static final EntityKey CRYSTAL = new EntityKey(CONN, 7, new UUID(0L, 7L));
    private static final Vec POSITION = new Vec(0.5, 64.5, 0.5);

    @Test
    void happyPathBindsOneSpawnAttacksAndFinishesOnRemoval() {
        DirectSequence sequence = placed();
        assertEquals(DirectSequence.State.PLACE_SENT, sequence.state());
        assertTrue(sequence.bind(CRYSTAL, POSITION));
        assertEquals(DirectSequence.State.SPAWN_OBSERVED, sequence.state());
        assertTrue(sequence.owns(CRYSTAL));

        assertTrue(sequence.attackSent(CRYSTAL, 12));
        assertEquals(DirectSequence.State.ATTACK_SENT, sequence.state());
        assertEquals(1, sequence.attacks());
        assertTrue(sequence.removalObserved(CRYSTAL, 14));
        assertEquals(DirectSequence.State.REMOVAL_OBSERVED, sequence.state());
        assertTrue(sequence.finish());
        assertEquals(DirectSequence.State.IDLE, sequence.state());
        assertNull(sequence.entity());
        assertTrue(sequence.consistent());
    }

    @Test
    void aSecondBindOrAnEarlyAttackIsRefused() {
        DirectSequence sequence = new DirectSequence();
        assertFalse(sequence.bind(CRYSTAL, POSITION), "cannot bind before a placement was sent");

        assertTrue(sequence.beginPlacement(CELL, new HazardId(1), new PlacementId(2), new RiskId(3), 10));
        assertTrue(sequence.bind(CRYSTAL, POSITION));
        assertFalse(sequence.bind(new EntityKey(CONN, 8, new UUID(0L, 8L)), POSITION),
            "a duplicate spawn hook must not re-target the sequence");
        assertFalse(sequence.attackSent(new EntityKey(CONN, 8, new UUID(0L, 8L)), 11));
        assertTrue(sequence.attackSent(CRYSTAL, 11));
        assertTrue(sequence.attackSent(CRYSTAL, 12), "the second allowed attempt is recorded");
        assertEquals(2, sequence.attacks());
    }

    @Test
    void duplicateEvidenceIsIdempotentAndCannotStartASecondSequence() {
        DirectSequence sequence = placed();
        sequence.bind(CRYSTAL, POSITION);
        sequence.attackSent(CRYSTAL, 12);
        assertTrue(sequence.removalObserved(CRYSTAL, 14));
        assertFalse(sequence.removalObserved(CRYSTAL, 15), "a second removal is ignored");
        assertFalse(sequence.beginPlacement(new Cell(4, 64, 0), new HazardId(5), new PlacementId(6),
            new RiskId(7), 16), "a terminal sequence must be finished first");
        assertFalse(sequence.abort("late removal"));
        assertTrue(sequence.finish());
        assertTrue(sequence.beginPlacement(new Cell(4, 64, 0), new HazardId(5), new PlacementId(6),
            new RiskId(7), 17));
    }

    @Test
    void abortedSequenceKeepsItsIdentityUntilReset() {
        DirectSequence sequence = placed();
        assertTrue(sequence.abort("spawn rejected"));
        assertEquals(DirectSequence.State.ABORTED, sequence.state());
        assertEquals("spawn rejected", sequence.abortReason());
        assertFalse(sequence.bind(CRYSTAL, POSITION));
        assertTrue(sequence.reset());
        assertEquals(DirectSequence.State.IDLE, sequence.state());
        assertNull(sequence.hazard());
        assertNull(sequence.risk());
    }

    @Test
    void stateInvariantAlwaysHolds() {
        DirectSequence sequence = placed();
        assertTrue(sequence.consistent());
        sequence.bind(CRYSTAL, POSITION);
        assertTrue(sequence.consistent());
        sequence.attackSent(CRYSTAL, 12);
        assertTrue(sequence.consistent());
        sequence.removalObserved(CRYSTAL, 13);
        assertTrue(sequence.consistent());
        sequence.finish();
        assertTrue(sequence.consistent());
    }

    private static DirectSequence placed() {
        DirectSequence sequence = new DirectSequence();
        assertTrue(sequence.beginPlacement(CELL, new HazardId(1), new PlacementId(2), new RiskId(3), 10));
        return sequence;
    }
}
