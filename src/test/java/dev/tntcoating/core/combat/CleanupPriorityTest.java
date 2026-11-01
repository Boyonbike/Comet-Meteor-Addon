/*
 * Original Comet test written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Phase 6.5 cleanup starvation hook: the wait counts server ticks, owns the next free slot after 20, and a
 * wait never crosses a generation or connection boundary.
 */
final class CleanupPriorityTest {
    private static final ConnectionKey CONN = new ConnectionKey(1L);
    private static final ActionGeneration GEN = new ActionGeneration(4L);
    private static final EntityKey VICTIM = ChoiceFixtures.entity(7);

    @Test
    void idleStateHasNoWait() {
        CleanupPriority priority = new CleanupPriority();

        CleanupPriority.State state = priority.state();

        assertFalse(state.waiting());
        assertFalse(state.due());
        assertEquals(0, state.waitedTicks());
        assertFalse(priority.prioritise());
    }

    @Test
    void cleanupIsDueAfterTwentyServerTicksOfWaiting() {
        CleanupPriority priority = new CleanupPriority();
        priority.request(100, new CleanupPriority.Deadline(CONN, GEN), VICTIM);

        for (int tick = 101; tick < 120; tick++) {
            assertFalse(priority.sample(tick), "not due before 20 ticks at tick " + tick);
        }
        assertEquals(19, priority.state(119).waitedTicks());

        assertTrue(priority.sample(120));
        assertTrue(priority.state(120).due());
        assertTrue(priority.prioritise(), "a due cleanup owns the next free slot");
    }

    @Test
    void repeatingTheRequestDoesNotResetTheWait() {
        CleanupPriority priority = new CleanupPriority();
        priority.request(100, new CleanupPriority.Deadline(CONN, GEN), VICTIM);
        priority.sample(110);

        priority.request(110, new CleanupPriority.Deadline(CONN, GEN), VICTIM);

        assertTrue(priority.sample(120), "the clock started at the first request, not the latest");
    }

    @Test
    void aDifferentVictimStartsANewWait() {
        CleanupPriority priority = new CleanupPriority();
        priority.request(100, new CleanupPriority.Deadline(CONN, GEN), VICTIM);
        priority.sample(110);

        priority.request(110, new CleanupPriority.Deadline(CONN, GEN), ChoiceFixtures.entity(8));

        assertFalse(priority.sample(120));
        assertTrue(priority.sample(130));
    }

    @Test
    void aWaitNeverCrossesAGenerationOrConnectionBoundary() {
        CleanupPriority priority = new CleanupPriority();
        priority.request(100, new CleanupPriority.Deadline(CONN, GEN), VICTIM);

        assertFalse(priority.expired(CONN, GEN));
        assertTrue(priority.expired(CONN, new ActionGeneration(5L)));
        assertTrue(priority.expired(new ConnectionKey(2L), GEN));

        CleanupPriority otherConnection = new CleanupPriority();
        otherConnection.request(100, new CleanupPriority.Deadline(CONN, GEN), VICTIM);
        assertTrue(otherConnection.expired(new ConnectionKey(2L), GEN));
    }

    @Test
    void aWaitIsNotPrioritisedWhileTheCleanupCannotActuallyReserve() {
        CleanupPriority priority = new CleanupPriority();
        priority.request(100, new CleanupPriority.Deadline(CONN, GEN), VICTIM);
        priority.sample(120);

        priority.feasibility(() -> false);
        assertFalse(priority.prioritise(), "no yield when the cleanup packet cannot be reserved");
        assertTrue(priority.state().due(), "the wait itself is still due");

        priority.feasibility(() -> true);
        assertTrue(priority.prioritise());

        priority.feasibility(null);
        assertTrue(priority.prioritise(), "the default probe never blocks a due wait");
    }

    @Test
    void clearThrowsTheWaitAwayWithoutTouchingAnythingElse() {
        CleanupPriority priority = new CleanupPriority();
        priority.request(100, new CleanupPriority.Deadline(CONN, GEN), VICTIM);
        priority.sample(120);

        priority.clear();

        assertFalse(priority.state().waiting());
        assertFalse(priority.prioritise());
        // The wait can be restarted from scratch after a reconcile.
        priority.request(130, new CleanupPriority.Deadline(CONN, GEN), VICTIM);
        assertFalse(priority.sample(140));
        assertTrue(priority.sample(150));
    }
}
