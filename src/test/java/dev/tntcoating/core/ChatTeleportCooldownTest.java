package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChatTeleportCooldownTest {
    @Test void homeWarmupHandsOffToCooldownOnlyAfterConfirmedSuccess() {
        var state = state(30, 15);
        state.sent("home Base", 0);
        state.message("Teleporting in 5 seconds", 100);
        assertEquals(ChatTeleportState.Phase.Teleport, state.timers(100).getFirst().phase());
        assertEquals(ChatTeleportState.Phase.Waiting, state.timers(5_100).getFirst().phase());
        state.message("You have been teleported home", 5_200);
        var timer = state.timers(5_200).getFirst();
        assertEquals(ChatTeleportState.Phase.Cooldown, timer.phase());
        assertEquals("Base", timer.target());
        assertTrue(timer.estimated());
        assertEquals("Home Base: cooldown ~30s", timer.label(5_200));
        assertEquals("Cooldown ~20s", timer.status(15_200));
        assertFalse(state.canCancel(ChatTeleportState.Kind.Home, 5_201));
        assertTrue(state.timers(35_200).isEmpty());
    }

    @Test void homeAndTpUseSeparateDurationsAndTargets() {
        var state = state(60, 20);
        state.sent("home base", 0);
        state.sent("tpa Alice", 10);
        state.message("You have been teleported home", 20);
        state.message("Teleport request accepted", 30);
        state.message("Teleportation successful", 40);
        var timers = state.timers(40);
        assertEquals(2, timers.size());
        assertEquals("Home base: cooldown ~1m", timers.getFirst().label(40));
        assertEquals("TPA Alice: cooldown ~20s", timers.getLast().label(40));
        assertEquals("Cooldown ~1m", ChatCountdowns.home("BASE", timers, 40));
        assertEquals("Cooldown ~20s", ChatCountdowns.player("alice", timers, List.of(), 40));
        assertEquals(List.of("Home base: cooldown ~1m", "TPA Alice: cooldown ~20s"), ChatCountdowns.lines(timers, List.of(), 40));
    }

    @Test void serverDurationOverridesTheEstimateAndKeepsTheCompletedTarget() {
        var state = state(30, 30);
        state.sent("home Base", 0);
        state.message("Teleported home", 100);
        state.message("Home cooldown: 1 minute", 200);
        var timer = state.timers(200).getFirst();
        assertFalse(timer.estimated());
        assertEquals("Base", timer.target());
        assertEquals("Home Base: cooldown 1m", timer.label(200));
        assertEquals(60_200, timer.deadline());
    }

    @Test void aServerSuccessAndCooldownCanArriveInTheSameMessage() {
        var state = state(5, 5);
        state.sent("tpa Alice", 0);
        state.message("You have been teleported. You can use this command again in 45 seconds.", 100);
        assertEquals("TPA Alice: cooldown 45s", state.timers(100).getFirst().label(100));
        assertFalse(state.timers(100).getFirst().estimated());
    }

    @Test void warmupAndCooldownOnOneLineUseTheirOwnDurations() {
        for (String reply : List.of("Home teleporting in 5 seconds. Cooldown: 30 seconds.",
            "Cooldown: 30 seconds. Home teleporting in 5 seconds.")) {
            var state = state(10, 10);
            state.sent("home Base", 0);
            state.message(reply, 100);
            assertEquals("Home Base: teleport 5s", state.timers(100).getFirst().label(100), reply);
            state.message("Home teleportation complete", 5_200);
            assertEquals("Home Base: cooldown 30s", state.timers(5_200).getFirst().label(5_200), reply);
        }
    }

    @Test void remainingTimeAfterARetryCalibratesFutureCooldowns() {
        var state = state(5, 5);
        state.sent("home Base", 0);
        state.message("Teleported home", 1_000);
        state.sent("home Other", 11_000);
        state.message("You must wait 20 seconds before you can use this command again.", 11_000);
        assertEquals("Home Other: cooldown 20s", state.timers(11_000).getFirst().label(11_000));
        state.sent("home Farm", 40_000);
        state.message("Teleported home", 40_100);
        assertEquals("Home Farm: cooldown ~30s", state.timers(40_100).getFirst().label(40_100));
    }

    @Test void retryAndCancelCommandsDoNotEraseTheExistingCooldown() {
        var state = state(30, 30);
        state.sent("home Base", 0);
        state.message("Teleported home", 100);
        long deadline = state.timers(100).getFirst().deadline();
        state.sent("home Other", 1_000);
        assertEquals(deadline, state.timers(1_000).getFirst().deadline());
        state.sent("homecancel", 2_000);
        assertEquals(deadline, state.timers(2_000).getFirst().deadline());
        state.sent("tpa Alice", 3_000);
        state.message("TPA teleportation successful", 3_100);
        state.sent("tpacancel", 3_200);
        assertEquals(2, state.timers(3_200).size());
    }

    @Test void duplicateSuccessAndRejectedRetriesCannotRestartCooldown() {
        var state = state(30, 30);
        state.sent("home Base", 0);
        state.message("Teleported home", 100);
        state.message("You have been teleported home", 1_000);
        assertEquals(30_100, state.timers(1_000).getFirst().deadline());
        state.sent("home Other", 2_000);
        state.message("Home cooldown: 28 seconds", 2_100);
        state.message("You have been teleported home", 2_200);
        assertEquals(30_100, state.timers(2_200).getFirst().deadline());
        assertFalse(state.timers(2_200).getFirst().estimated());
    }

    @Test void failuresAndUnconfirmedWarmupsNeverStartEstimatedCooldowns() {
        for (String reply : List.of("Teleportation cancelled because you moved", "Teleport request denied", "Teleport request expired",
            "Home does not exist", "You have not been teleported home", "You were not teleported home")) {
            var state = state(30, 30);
            state.sent(reply.startsWith("Teleport request") ? "tpa Alice" : "home Base", 0);
            state.message(reply, 100);
            assertTrue(state.timers(100).isEmpty(), reply);
        }
        var state = state(30, 30);
        state.sent("home Base", 0);
        state.message("Teleporting in 2 seconds", 100);
        assertEquals(ChatTeleportState.Phase.Waiting, state.timers(2_100).getFirst().phase());
        assertTrue(state.timers(17_100).isEmpty());
    }

    @Test void futureAndUnrelatedWaitMessagesCannotConfirmOrChangeTeleportState() {
        var state = state(30, 30);
        state.sent("home Base", 0);
        state.message("Please wait 10 seconds for the server restart.", 50);
        assertEquals(ChatTeleportState.Phase.Waiting, state.timers(50).getFirst().phase());
        state.message("You will be teleported in 3 seconds", 100);
        assertEquals(ChatTeleportState.Phase.Teleport, state.timers(100).getFirst().phase());
        assertFalse(state.timers(100).getFirst().estimated());
    }

    @Test void serverCanEndCooldownEarly() {
        for (String reply : List.of("Your home cooldown has expired.", "Home cooldown: 0 seconds", "You can teleport home again.")) {
            var state = state(30, 30);
            state.sent("home Base", 0);
            state.message("Teleported home", 100);
            state.message(reply, 1_000);
            assertTrue(state.timers(1_000).isEmpty(), reply);
        }
        var state = state(30, 30);
        state.sent("home Base", 0);
        state.message("Teleported home", 100);
        state.message("Your home cooldown has not expired.", 1_000);
        assertFalse(state.timers(1_000).isEmpty());
        state.message("Your home cooldown isn't over yet.", 1_100);
        assertFalse(state.timers(1_100).isEmpty());
    }

    @Test void disablingFallbackAndChangingServersDoNotRetainLearnedDurations() {
        var state = state(0, 0);
        state.sent("home Base", 0);
        state.message("Teleported home", 100);
        assertTrue(state.timers(100).isEmpty());
        state.message("Home cooldown: 30 seconds", 200);
        assertFalse(state.timers(200).isEmpty());
        state.clear();
        state.sent("home Base", 1_000);
        state.message("Teleported home", 1_100);
        assertTrue(state.timers(1_100).isEmpty());
        state.cooldowns(5, 0);
        state.clear();
        state.sent("home Farm", 2_000);
        state.message("Teleported home", 2_100);
        assertEquals("Home Farm: cooldown ~5s", state.timers(2_100).getFirst().label(2_100));
    }

    @Test void lateAcceptedRequestsStillHandOffToWarmupAndCooldown() {
        var state = state(30, 30);
        state.sent("tpa Alice", 0);
        state.message("Your teleport request expires after 3 minutes", 100);
        state.message("Teleport request accepted", 179_000);
        state.message("Teleporting in 3 seconds", 179_100);
        assertEquals(ChatTeleportState.Phase.Teleport, state.timers(179_100).getFirst().phase());
        state.message("Teleportation successful", 182_200);
        assertEquals("TPA Alice: cooldown ~30s", state.timers(182_200).getFirst().label(182_200));
    }

    // Exact lines from 6b6t's chat log.
    @Test void sixbsixtHomeTeleportStartsItsCooldownWhenItHappens() {
        var state = state(30, 30);
        state.sent("home 5k5k", 0);
        state.message("Teleporting to 5k5k in 15 seconds. [Cancel]", 100);
        assertEquals("Home 5k5k: teleport 15s", state.timers(100).getFirst().label(100));
        state.message("Teleporting to: 5k5k", 15_100);
        var timer = state.timers(15_100).getFirst();
        assertEquals(ChatTeleportState.Phase.Cooldown, timer.phase());
        assertEquals("Home 5k5k: cooldown ~30s", timer.label(15_100));
    }

    @Test void sixbsixtTpaTeleportStartsItsCooldown() {
        var state = state(30, 30);
        state.sent("tpa uriauria", 0);
        state.message("Request sent to: uriauria", 50);
        state.message("Please wait for uriauria to accept or deny your request.", 60);
        state.message("Your request sent to uriauria was accepted!", 5_000);
        state.message("Teleporting in 15 seconds... [Cancel]", 5_000);
        assertEquals("TPA uriauria: teleport 15s", state.timers(5_000).getFirst().label(5_000));
        state.message("Teleported to destination!", 20_000);
        assertEquals("TPA uriauria: cooldown ~30s", state.timers(20_000).getFirst().label(20_000));
    }

    @Test void movingToAnotherNetworkServerAtTheEndOfAWarmupCompletesTheTeleport() {
        var state = state(30, 30);
        state.sent("tpa uriauria", 0);
        state.message("Teleporting in 15 seconds... [Cancel]", 1_000);
        state.transferred(5_000);
        assertEquals(ChatTeleportState.Phase.Teleport, state.timers(5_000).getFirst().phase(), "A switch mid-warmup is not the teleport");
        state.transferred(16_500);
        assertEquals("TPA uriauria: cooldown ~30s", state.timers(16_500).getFirst().label(16_500));
        state.sent("tpa uriauria", 20_000);
        state.message("You have to wait 4m 30s to teleport again.", 20_000);
        assertEquals("TPA uriauria: cooldown 4m 30s", state.timers(20_000).getFirst().label(20_000));
        assertEquals(java.util.Map.of(ChatTeleportState.Kind.TPA, 273_500L), state.learned());
        var idle = state(30, 30);
        idle.transferred(1_000);
        assertTrue(idle.timers(1_000).isEmpty());
    }

    @Test void compoundWaitTimesAreReadWhole() {
        for (var reply : List.of(new Object[] {"You have to wait 9m 35s to teleport again.", "9m 35s"},
            new Object[] {"You have to wait 0m 34s to teleport again. Lower your cooldown - https://6b6t.org/shop", "34s"},
            new Object[] {"You must wait 1 minute and 5 seconds before teleporting again.", "1m 5s"},
            new Object[] {"Teleport cooldown: 1h 2m", "1h 2m"})) {
            var state = state(30, 30);
            state.sent("home base", 0);
            state.message((String) reply[0], 100);
            assertEquals("Home base: cooldown " + reply[1], state.timers(100).getFirst().label(100), (String) reply[0]);
            assertFalse(state.timers(100).getFirst().estimated());
        }
    }

    // 6b6t words both cooldowns "to teleport again", yet a TPA still succeeds during a home cooldown: they are separate.
    @Test void aGenericWaitBelongsToTheRetriedCommandOnly() {
        var state = state(30, 30);
        state.sent("home 5k5k", 0);
        state.message("Teleporting to 5k5k in 15 seconds. [Cancel]", 0);
        state.message("Teleporting to: 5k5k", 15_000);
        state.sent("tpa Alice", 60_000);
        state.message("Teleporting in 15 seconds... [Cancel]", 60_000);
        state.message("Teleported to destination!", 75_000);
        state.sent("home 5k5k", 100_000);
        state.message("You have to wait 3m 35s to teleport again.", 100_000);
        var timers = state.timers(100_000);
        assertEquals("Home 5k5k: cooldown 3m 35s", timers.getFirst().label(100_000));
        assertEquals("TPA Alice: cooldown ~5s", timers.getLast().label(100_000));
        assertEquals(java.util.Map.of(ChatTeleportState.Kind.Home, 300_000L), state.learned());
        state.sent("home base", 400_000);
        state.message("Teleporting to: base", 415_000);
        assertEquals("Home base: cooldown ~5m", state.timers(415_000).getFirst().label(415_000));
    }

    @Test void learnedCooldownsCanBeRestoredIntoANewSession() {
        var state = state(30, 30);
        state.learned(java.util.Map.of(ChatTeleportState.Kind.Home, 600_000L, ChatTeleportState.Kind.TPA, -5L));
        assertEquals(java.util.Map.of(ChatTeleportState.Kind.Home, 600_000L), state.learned());
        state.sent("home base", 0);
        state.message("Teleporting to: base", 15_000);
        assertEquals("Home base: cooldown ~10m", state.timers(15_000).getFirst().label(15_000));
        state.clear();
        assertTrue(state.learned().isEmpty());
    }

    @Test void aManualLengthReplacesLearnedAndFallbackButNotTheServer() {
        var home = ChatTeleportState.Kind.Home;
        var state = state(30, 30);
        state.learned(java.util.Map.of(home, 600_000L));
        state.manual(home, 480_000L, 0);
        state.sent("home base", 0);
        state.message("Teleporting to: base", 15_000);
        assertEquals("Home base: cooldown ~8m", state.timers(15_000).getFirst().label(15_000));
        state.sent("home base", 60_000);
        state.message("You have to wait 9m 0s to teleport again.", 60_000);
        assertEquals("Home base: cooldown 9m", state.timers(60_000).getFirst().label(60_000), "The server's own time always wins");
        state.manual(home, 300_000L, 61_000);
        assertEquals("Home base: cooldown 8m 59s", state.timers(61_000).getFirst().label(61_000), "A reported countdown is never re-timed");
        assertEquals(java.util.Map.of(home, 300_000L), state.manual());
        state.manual(home, null, 61_000);
        assertTrue(state.manual().isEmpty());
        state.sent("home farm", 700_000);
        state.message("Teleporting to: farm", 715_000);
        assertEquals("Home farm: cooldown ~9m 45s", state.timers(715_000).getFirst().label(715_000), "Back on Auto, the learned length is used");
        state.clear();
        assertTrue(state.manual().isEmpty());
    }

    @Test void changingTheManualLengthReTimesTheEstimateFromTheTeleport() {
        var tpa = ChatTeleportState.Kind.TPA;
        var state = state(30, 30);
        state.sent("tpa Alice", 0);
        state.message("Teleported to destination!", 10_000);
        assertEquals("TPA Alice: cooldown ~30s", state.timers(10_000).getFirst().label(10_000));
        state.manual(tpa, 300_000L, 20_000);
        assertEquals("TPA Alice: cooldown ~4m 50s", state.timers(20_000).getFirst().label(20_000));
        state.manual(tpa, 60_000L, 80_000);
        assertTrue(state.timers(80_000).isEmpty(), "A reduction that has already run out ends the countdown");
        state.manual(tpa, 600_000L, 100_000);
        assertEquals("TPA Alice: cooldown ~8m 30s", state.timers(100_000).getFirst().label(100_000), "An estimate that ran out too early comes back");
        state.manual(tpa, 0L, 110_000);
        assertTrue(state.timers(110_000).isEmpty(), "Zero means no cooldown");
        state.sent("tpa Bob", 120_000);
        state.message("Teleported to destination!", 130_000);
        assertTrue(state.timers(130_000).isEmpty());
    }

    @Test void remainingTimeReadsInTheLargestUsefulUnits() {
        assertEquals("0s", ChatTeleportState.remaining(-50));
        assertEquals("1s", ChatTeleportState.remaining(1));
        assertEquals("59s", ChatTeleportState.remaining(59_000));
        assertEquals("1m", ChatTeleportState.remaining(60_000));
        assertEquals("9m 35s", ChatTeleportState.remaining(575_000));
        assertEquals("1h", ChatTeleportState.remaining(3_600_000));
        assertEquals("1h 5m", ChatTeleportState.remaining(3_841_000));
    }

    private static ChatTeleportState state(int home, int tp) {
        var state = new ChatTeleportState();
        state.cooldowns(home, tp);
        return state;
    }
}
