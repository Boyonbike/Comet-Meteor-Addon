package dev.tntcoating.core.scaffold;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class OrdinaryRotationSlotTest {
    @Test void onlyOneIntentAndQueueOrHoldWinsAfterLatePreHandlers() {
        var slot = new OrdinaryRotationSlot();
        assertTrue(slot.prepare(60, 70, () -> true, () -> fail("busy")));
        assertFalse(slot.prepare(30, 40, () -> true, () -> {}));
        assertNull(slot.begin(true)); // queue or retained hold found after ALL Pre listeners
        assertFalse(slot.prepared());
    }
    @Test void verifiedSendAndLiveGenerationAreRequired() {
        var slot = new OrdinaryRotationSlot();
        var valid = new AtomicBoolean(true);
        var called = new AtomicBoolean();
        slot.prepare(60, 70, valid::get, () -> called.set(true));
        assertNotNull(slot.begin(false));
        assertNull(slot.finish(true)); // no successful observed ordinary movement send
        assertFalse(called.get());
        slot.prepare(60, 70, valid::get, () -> called.set(true));
        slot.begin(false); slot.observeMovement(true, 60, 70);
        valid.set(false);
        assertNull(slot.finish(true));
    }
    @Test void observedFacingIncludesNonRotationPacketsAndExceptionCancels() {
        var slot = new OrdinaryRotationSlot();
        var called = new AtomicBoolean();
        slot.observeMovement(true, 420, 70);
        assertTrue(slot.orientationMatches(60, 70));
        slot.prepare(60, 70, () -> true, () -> called.set(true));
        slot.begin(false); slot.observeMovement(false, 0, 0);
        Runnable callback = slot.finish(true);
        assertNotNull(callback);
        assertFalse(called.get()); // adapter restores visual view before invoking returned callback
        callback.run(); assertTrue(called.get());
        slot.prepare(60, 70, () -> true, () -> fail("exception"));
        slot.begin(false); slot.observeMovement(true, 60, 70);
        assertNull(slot.finish(false));
    }
    @Test void cameraOrCancellationSkippingVanillaYawReadDropsPreparedIntent() {
        var slot = new OrdinaryRotationSlot();
        slot.prepare(20, 40, () -> true, () -> fail("no ordinary movement slot"));
        assertNull(slot.finish(true));
        assertFalse(slot.prepared());
        assertTrue(slot.prepare(30, 40, () -> true, () -> {}));
    }
    @Test void cancellationAndMismatchedFacingNeverDispatch() {
        var slot = new OrdinaryRotationSlot();
        slot.prepare(20, 40, () -> true, () -> fail("wrong facing"));
        slot.begin(false); slot.observeMovement(true, 21, 40);
        assertNull(slot.finish(true));
        slot.prepare(20, 40, () -> true, () -> fail("cancelled"));
        slot.begin(false); slot.observeMovement(true, 20, 40); slot.cancel();
        assertNull(slot.finish(true));
        slot.clear(); assertFalse(slot.orientationMatches(20, 40));
    }
}
