package dev.tntcoating.core;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetHomeSchedulerTest {
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final SetHomeScheduler.Location location = new SetHomeScheduler.Location(12, 70, -34, "minecraft:the_nether");
    private SetHomeScheduler fresh() { return new SetHomeScheduler(clock::get, SetHomeScheduler.State.empty()); }

    @Test void learnsTheUsersExactReplyFromAConfirmedCycle() {
        var s = fresh();
        s.sent("Test", location, false, 10_000);
        assertEquals(SetHomeScheduler.Reply.SUCCESS, s.message("Successfully moved Home: Test", 60_000));
        clock.addAndGet(6_000);
        s.sent("Test", location, false, 10_000);
        assertEquals(SetHomeScheduler.Reply.COOLDOWN, s.message("You have to wait 4m 54s to teleport again.", 60_000));
        assertEquals(300_000, s.state().learnedMillis());
        assertEquals(294_000, s.snapshot(false, 60_000, 10_000).cooldownMillis());
        clock.addAndGet(294_000);
        assertEquals("BUFFER", s.snapshot(false, 60_000, 10_000).phase());
        assertEquals(10_000, s.snapshot(false, 60_000, 10_000).bufferMillis());
        clock.addAndGet(10_000);
        assertTrue(s.ready(false, 60_000, 10_000));
    }

    @Test void zeroSecondsStartsAFreshBufferAndRetryWithoutSpinning() {
        var s = fresh(); s.sent("Test", location, false, 10_000);
        s.message("You have to wait 0m 00s to teleport again.", 60_000);
        assertEquals(60_000, s.snapshot(false, 60_000, 10_000).waitMillis());
        clock.addAndGet(60_000);
        assertTrue(s.ready(false, 60_000, 10_000));
        s.sent("Test", location, false, 10_000);
        s.message("You have to wait 0m 00s to teleport again.", 0);
        assertEquals(1_000, s.snapshot(false, 60_000, 0).waitMillis());
    }

    @Test void clickingIsNotSuccessAndWrongOrDuplicateRepliesAreIgnored() {
        var s = fresh(); s.sent("Test", location, true, 10_000);
        assertFalse(s.ready(true, 5_000, 0));
        s.clicked(location);
        assertTrue(s.state().history().isEmpty());
        assertEquals(SetHomeScheduler.Reply.IGNORED, s.message("Successfully moved Home: Other", 60_000));
        assertEquals(SetHomeScheduler.Reply.SUCCESS, s.message("§aSuccessfully moved Home: test", 60_000));
        assertEquals(SetHomeScheduler.Reply.IGNORED, s.message("Successfully moved Home: Test", 60_000));
        assertEquals(1, s.state().history().size());
        assertEquals(location, s.state().history().getFirst().location());
    }

    @Test void unsolicitedTeleportMessagesAreIgnoredAndTimeoutsUseRealTime() {
        var s = fresh();
        assertEquals(SetHomeScheduler.Reply.IGNORED, s.message("You have to wait 4m 54s to teleport again.", 60_000));
        s.sent("Test", location, true, 10_000);
        clock.addAndGet(10_001); s.expire();
        assertEquals(SetHomeScheduler.Reply.IGNORED, s.message("Successfully moved Home: Test", 60_000));
        assertTrue(s.state().history().isEmpty());
        assertFalse(s.ready(false, 60_000, 10_000));
    }

    @Test void fixedModeIgnoresServerCooldownButDoesNotOverlapARequest() {
        var s = fresh(); s.sent("Test", location, false, 10_000);
        s.message("You have to wait 4m 54s to teleport again.", 60_000);
        clock.addAndGet(5_000);
        assertTrue(s.ready(true, 5_000, 10_000));
        assertFalse(s.ready(false, 5_000, 10_000));
        s.sent("Test", location, true, 10_000);
        clock.addAndGet(5_000);
        assertFalse(s.ready(true, 5_000, 10_000));
    }

    @Test void restoredStateRetainsCooldownBufferAndSentTimestampButCannotConfirmOldAttempts() {
        var s = fresh(); s.sent("Test", location, false, 10_000);
        s.message("You have to wait 4m 54s to teleport again.", 60_000);
        clock.addAndGet(100_000);
        var restored = new SetHomeScheduler(clock::get, s.state());
        assertEquals(194_000, restored.snapshot(false, 60_000, 10_000).cooldownMillis());
        assertEquals(SetHomeScheduler.Reply.IGNORED, restored.message("Successfully moved Home: Test", 60_000));
        clock.addAndGet(194_000);
        restored = new SetHomeScheduler(clock::get, restored.state());
        assertEquals(10_000, restored.snapshot(false, 60_000, 10_000).waitMillis());
        clock.addAndGet(20_000);
        assertTrue(restored.ready(false, 60_000, 10_000));
        restored.sent("Other", location, false, 10_000);
        assertEquals(294_000, restored.state().learnedMillis());
        assertFalse(restored.ready(true, 300_000, 0));
    }

    @Test void anExpiredOldCooldownCannotMakeAnUnansweredAttemptRepeatEarly() {
        var s = fresh(); s.sent("Test", location, false, 10_000);
        s.message("You have to wait 0m 00s to teleport again.", 1_000);
        clock.addAndGet(10_000);
        s.sent("Test", location, false, 10_000);
        clock.addAndGet(15_000); s.expire();
        assertFalse(s.ready(false, 60_000, 10_000));
        assertEquals(45_000, s.snapshot(false, 60_000, 10_000).waitMillis());
    }

    @Test void zeroBufferAndAnAttemptAtTheExactOldDeadlineStillUseFallbackOnTimeout() {
        var s = fresh(); s.sent("Test", location, false, 10_000);
        s.message("You have to wait 0m 01s to teleport again.", 60_000);
        clock.addAndGet(1_000);
        s.sent("Test", location, false, 10_000);
        clock.addAndGet(15_000); s.expire();
        assertEquals(45_000, s.snapshot(false, 60_000, 0).waitMillis());
    }

    @Test void overrideLocationAndResponseDeadlineAreCapturedAtClickTime() {
        var s = fresh(); s.sent("Test", location, true, 10_000);
        clock.addAndGet(9_000);
        var moved = new SetHomeScheduler.Location(50, 70, -80, "minecraft:the_nether");
        s.clicked(moved);
        assertEquals(15_000, s.snapshot(false, 60_000, 10_000).responseMillis());
        clock.addAndGet(14_999);
        assertEquals(SetHomeScheduler.Reply.SUCCESS, s.message("Successfully moved Home: Test", 60_000));
        assertEquals(moved, s.state().history().getFirst().location());
        assertEquals(1_009_000, s.state().successActionAt());
    }

    @Test void cancelledRequestsCannotBeConfirmedAndMalformedNamesCannotBeSent() {
        var s = fresh(); s.sent("Test", location, true, 10_000);
        s.cancel();
        assertEquals(SetHomeScheduler.Reply.IGNORED, s.message("Successfully moved Home: Test", 60_000));
        assertThrows(IllegalArgumentException.class, () -> s.sent("Test other", location, false, 10_000));
    }

    @Test void settingsRecalculateFromOriginalSendAndHistoryIsBounded() {
        var s = fresh();
        for (int i = 0; i < 105; i++) {
            s.sent("Test", location, false, 10_000);
            s.message("Successfully moved Home: Test", 60_000);
            clock.addAndGet(1_000);
        }
        assertEquals(100, s.state().history().size());
        assertEquals(4_000, s.snapshot(true, 5_000, 10_000).waitMillis());
        assertEquals(59_000, s.snapshot(true, 60_000, 10_000).waitMillis());
    }
}
