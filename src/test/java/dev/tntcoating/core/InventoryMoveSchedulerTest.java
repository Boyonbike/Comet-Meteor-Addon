package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InventoryMoveSchedulerTest {
    private static final InventoryMoveScheduler.Context CONTEXT =
        new InventoryMoveScheduler.Context(new Object(), new Object(), new Object(), new Object());
    private static long ms(long value) { return value * 1_000_000L; }

    @Test void retainsInitialAndNormalDelaysWithoutBlockingOrCatchingUp() {
        var mover = new InventoryMoveScheduler<String>();
        mover.start(CONTEXT, 0, 3, true, 0);
        assertNull(mover.poll(CONTEXT, 0, 1, 50, () -> 20, slot -> "stack"));
        assertNull(mover.poll(CONTEXT, ms(49), 1, 50, () -> 20, slot -> "stack"));
        var first = mover.poll(CONTEXT, ms(50), 2, 50, () -> 20, slot -> "stack");
        assertEquals(0, first.slot());
        mover.complete(first, ms(50));
        assertNull(mover.poll(CONTEXT, ms(69), 2, 50, () -> 20, slot -> "stack"));
        var second = mover.poll(CONTEXT, ms(1000), 20, 50, () -> 20, slot -> "stack");
        assertEquals(1, second.slot());
        mover.complete(second, ms(1000));
        assertNull(mover.poll(CONTEXT, ms(1000), 20, 50, () -> 20, slot -> "stack"));
        assertEquals(2, mover.poll(CONTEXT, ms(1020), 21, 50, () -> 20, slot -> "stack").slot());
    }

    @Test void zeroInitialUsesNormalRandomizedDelayOncePerEligibleStack() {
        var mover = new InventoryMoveScheduler<String>();
        var samples = new AtomicInteger();
        mover.start(CONTEXT, 0, 4, true, 0);
        assertNull(mover.poll(CONTEXT, 0, 1, 0, () -> 20 + samples.getAndIncrement(),
            slot -> slot < 2 ? null : "eligible"));
        assertNull(mover.poll(CONTEXT, ms(19), 1, 0, () -> fail("Resampled pending delay"), slot -> "eligible"));
        var first = mover.poll(CONTEXT, ms(20), 1, 0, () -> fail("Resampled pending delay"), slot -> "eligible");
        assertEquals(2, first.slot());
        mover.complete(first, ms(20));
        assertNull(mover.poll(CONTEXT, ms(40), 1, 0, () -> 20 + samples.getAndIncrement(), slot -> "eligible"));
        assertEquals(3, mover.poll(CONTEXT, ms(41), 1, 0, () -> fail("Resampled pending delay"), slot -> "eligible").slot());
        assertEquals(2, samples.get());
    }

    @Test void repeatedInventoryPacketsDoNotRestartDelayOrDuplicateTheRequest() {
        var mover = new InventoryMoveScheduler<String>();
        assertTrue(mover.start(CONTEXT, 0, 2, true, 0));
        assertNull(mover.poll(CONTEXT, 0, 1, 50, () -> 20, slot -> "stack"));
        assertFalse(mover.start(CONTEXT, 0, 2, true, ms(49)));
        var action = mover.poll(CONTEXT, ms(50), 1, 50, () -> 20, slot -> "stack");
        assertEquals(0, action.slot());
        assertFalse(mover.start(CONTEXT, 0, 2, true, ms(51)));
        assertNull(mover.poll(CONTEXT, ms(100), 2, 50, () -> 20, slot -> "stack"));
        mover.complete(action, ms(100));
        assertEquals(1, mover.poll(CONTEXT, ms(120), 3, 0, () -> 20, slot -> "stack").slot());
    }

    @Test void switchingPlayerWorldScreenOrMenuCancelsEvenWhenContainerIdsCouldMatch() {
        var alternatives = new InventoryMoveScheduler.Context[] {
            new InventoryMoveScheduler.Context(new Object(), CONTEXT.world(), CONTEXT.screen(), CONTEXT.menu()),
            new InventoryMoveScheduler.Context(CONTEXT.player(), new Object(), CONTEXT.screen(), CONTEXT.menu()),
            new InventoryMoveScheduler.Context(CONTEXT.player(), CONTEXT.world(), new Object(), CONTEXT.menu()),
            new InventoryMoveScheduler.Context(CONTEXT.player(), CONTEXT.world(), CONTEXT.screen(), new Object())
        };
        for (var changed : alternatives) {
            var mover = new InventoryMoveScheduler<String>();
            mover.start(CONTEXT, 0, 2, true, 0);
            var action = mover.poll(CONTEXT, 0, 1, 0, () -> 0, slot -> "stack");
            assertFalse(mover.isCurrent(action, changed));
            assertNull(mover.poll(changed, ms(1000), 2, 0, () -> 0, slot -> fail("Stale menu read")));
            assertNull(mover.poll(CONTEXT, ms(1001), 2, 0, () -> 0, slot -> fail("Old menu resumed")));
        }
    }

    @Test void closingOrCancellingInvalidatesQueuedRotationCallbacksPermanently() {
        var mover = new InventoryMoveScheduler<String>();
        mover.start(CONTEXT, 0, 1, true, 0);
        var action = mover.poll(CONTEXT, 0, 1, 0, () -> 0, slot -> "old");
        assertTrue(mover.isCurrent(action, CONTEXT));
        mover.cancel();
        assertFalse(mover.isCurrent(action, CONTEXT));
        mover.start(CONTEXT, 0, 1, true, 0);
        var replacement = mover.poll(CONTEXT, 0, 1, 0, () -> 0, slot -> "new");
        mover.complete(action, 0);
        assertTrue(mover.isCurrent(replacement, CONTEXT));
        assertFalse(mover.isCurrent(action, CONTEXT));
        assertNull(mover.poll(null, ms(20), 2, 0, () -> 0, slot -> fail("Closed container read")));
        assertFalse(mover.isCurrent(replacement, CONTEXT));
    }

    @Test void changingFromStealToDumpReplacesTheJobAndItsPendingActions() {
        var mover = new InventoryMoveScheduler<String>();
        mover.start(CONTEXT, 0, 27, true, 0);
        var steal = mover.poll(CONTEXT, 0, 1, 0, () -> 0, slot -> "steal");
        assertTrue(mover.start(CONTEXT, 27, 63, false, 0));
        assertFalse(mover.isCurrent(steal, CONTEXT));
        var dump = mover.poll(CONTEXT, 0, 1, 0, () -> 0, slot -> "dump");
        assertEquals(27, dump.slot());
        assertFalse(dump.steal());
    }

    @Test void delayedCandidateKeepsItsOriginalSnapshotUntilExecution() {
        var mover = new InventoryMoveScheduler<String>();
        mover.start(CONTEXT, 0, 1, true, 0);
        assertNull(mover.poll(CONTEXT, 0, 1, 50, () -> 0, slot -> "expected stack"));
        var action = mover.poll(CONTEXT, ms(50), 2, 50, () -> 0,
            slot -> fail("Changed slot was silently recaptured"));
        assertEquals("expected stack", action.snapshot());
        // Integration compares this snapshot against the live stack before emitting a click.
    }

    @Test void zeroDelayIsBoundedAcrossTickRenderAndRepeatedRequests() {
        var mover = new InventoryMoveScheduler<String>();
        mover.start(CONTEXT, 0, 100, true, 0);
        for (int i = 0; i < InventoryMoveScheduler.MAX_ACTIONS_PER_TICK; i++) {
            var action = mover.poll(CONTEXT, 0, 1, 0, () -> 0, slot -> "stack");
            assertEquals(i, action.slot());
            mover.complete(action, 0);
        }
        assertNull(mover.poll(CONTEXT, 0, 1, 0, () -> 0, slot -> fail("Budget exhausted")));
        mover.cancel();
        mover.start(CONTEXT, 0, 100, true, 0);
        assertNull(mover.poll(CONTEXT, 0, 1, 0, () -> 0, slot -> fail("Request bypassed budget")));
        assertEquals(0, mover.poll(CONTEXT, 0, 2, 0, () -> 0, slot -> "stack").slot());
    }

    @Test void emptyFilteredAndInvalidRangesEmitNoActions() {
        var mover = new InventoryMoveScheduler<String>();
        assertFalse(mover.start(CONTEXT, -1, 10, true, 0));
        assertFalse(mover.start(CONTEXT, 5, 5, true, 0));
        assertFalse(mover.start(null, 0, 5, true, 0));
        assertTrue(mover.start(CONTEXT, 0, 63, true, 0));
        assertNull(mover.poll(CONTEXT, 0, 1, 0, () -> fail("Filtered slots used delay"), slot -> null));
        assertFalse(mover.isValid(CONTEXT));
    }
}
