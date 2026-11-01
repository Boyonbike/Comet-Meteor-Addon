/*
 * Original Comet test by Boyonabike.
 * Independent: written from the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Phase 4.1: the shared per-tick work budget is deterministic and externally drivable. */
class TickBudgetTest {
    @Test
    void productionDefaultIsOneMillisecond() {
        assertEquals(1.0, TickBudget.DEFAULT_MILLIS);
    }

    @Test
    void fakeClockExhaustsTheDeadline() {
        AtomicLong nanos = new AtomicLong(5_000_000L);
        TickBudget budget = TickBudget.afterMillis(1.0, nanos::get);

        assertFalse(budget.exhausted());
        assertEquals(1_000_000L, budget.remainingNanos());

        nanos.set(5_999_999L);
        assertFalse(budget.exhausted());
        nanos.set(6_000_000L);
        assertTrue(budget.exhausted());
        assertEquals(0L, budget.remainingNanos());
    }

    @Test
    void workLimitCountsEveryPhaseAndOvershootsObservably() {
        TickBudget budget = TickBudget.workLimited(3);

        assertTrue(budget.spend(TickBudget.Phase.QUEUE_DRAIN, 2));
        assertFalse(budget.spend(TickBudget.Phase.VALIDATION));
        assertTrue(budget.exhausted());
        assertFalse(budget.spend(TickBudget.Phase.PLANNING));

        assertEquals(4L, budget.work());
        assertEquals(2L, budget.phaseWork(TickBudget.Phase.QUEUE_DRAIN));
        assertEquals(1L, budget.phaseWork(TickBudget.Phase.VALIDATION));
        assertEquals(1L, budget.phaseWork(TickBudget.Phase.PLANNING));
        assertEquals(0L, budget.phaseWork(TickBudget.Phase.SNAPSHOT_CAPTURE));
    }

    @Test
    void deferralIsCountedButNeverAbortsWork() {
        TickBudget budget = TickBudget.workLimited(1);

        assertFalse(budget.spend(TickBudget.Phase.REVISION_STAMPING));
        assertTrue(budget.exhausted());
        budget.defer();
        budget.defer();

        assertEquals(2L, budget.deferrals());
        assertEquals(1L, budget.work());
    }

    @Test
    void unlimitedBudgetNeverExhausts() {
        TickBudget budget = TickBudget.unlimited();

        for (int i = 0; i < 1_000; i++) {
            assertTrue(budget.spend(TickBudget.Phase.PLANNING));
        }

        assertFalse(budget.exhausted());
        assertEquals(1_000L, budget.work());
        assertEquals(Long.MAX_VALUE, budget.remainingNanos());
    }

    @Test
    void combinedBudgetUsesBothLimits() {
        AtomicLong nanos = new AtomicLong();
        TickBudget budget = TickBudget.of(nanos::get, 500_000L, 2);

        assertTrue(budget.spend(TickBudget.Phase.SNAPSHOT_CAPTURE));
        assertFalse(budget.exhausted());
        nanos.set(500_000L);
        assertTrue(budget.exhausted());
        assertEquals(0L, budget.remainingNanos());
        assertEquals(0L, budget.startNanos());
    }

    @Test
    void rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> TickBudget.afterMillis(0.0, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> TickBudget.afterNanos(0L, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> TickBudget.workLimited(0L));
        assertThrows(IllegalArgumentException.class, () -> TickBudget.of(() -> 0L, -1L, 1L));
        TickBudget budget = TickBudget.unlimited();
        assertThrows(IllegalArgumentException.class,
            () -> budget.spend(TickBudget.Phase.PLANNING, -1L));
    }
}
