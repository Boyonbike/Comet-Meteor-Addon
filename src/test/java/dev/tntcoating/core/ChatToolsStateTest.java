package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ChatToolsStateTest {
    @Test void commandStartsWaitingAndServerSuppliesHomeCountdown() {
        var state = new ChatTeleportState();
        state.sent("home base", 1_000);
        assertEquals("Home base: awaiting server", state.timers(1_000).getFirst().label(1_000));
        state.message("Teleportation will commence in 5 seconds. Don't move.", 1_100);
        assertEquals("Home base: teleport 4s", state.timers(2_101).getFirst().label(2_101));
        assertEquals("Home base: awaiting server", state.timers(6_100).getFirst().label(6_100));
        assertTrue(state.timers(21_100).isEmpty());
    }

    @Test void futureTeleportIsNotMistakenForCompletion() {
        var state = new ChatTeleportState();
        state.sent("/home base", 0);
        state.message("You will be teleported in 3 seconds", 10);
        assertEquals(ChatTeleportState.Phase.Teleport, state.timers(10).getFirst().phase());
        state.message("You have been teleported to your home", 100);
        assertTrue(state.timers(100).isEmpty());
    }

    @Test void tracksRequestExpiryThenAcceptanceAndTeleportCountdown() {
        var state = new ChatTeleportState();
        state.sent("tpa Alice", 1_000);
        state.message("Teleport request sent. This request expires after 2 minutes.", 1_010);
        assertEquals("TPA Alice: request 120s", state.timers(1_010).getFirst().label(1_010));
        state.message("Teleport request accepted", 2_000);
        assertEquals(ChatTeleportState.Phase.Waiting, state.timers(2_000).getFirst().phase());
        state.message("Teleporting in 3 seconds", 2_010);
        assertEquals("TPA Alice: teleport 3s", state.timers(2_010).getFirst().label(2_010));
        state.message("Teleportation cancelled because you moved", 2_050);
        assertTrue(state.timers(2_050).isEmpty());
    }

    @Test void homeAndTpaTimersRemainIndependentAndCancelCommandsWork() {
        var state = new ChatTeleportState();
        state.sent("home base", 0);
        state.message("Home teleporting in 10 seconds", 10);
        state.sent("tpa Alice", 20);
        state.message("Your teleport request expires after 120 seconds", 30);
        assertEquals(2, state.timers(40).size());
        state.sent("tpacancel", 50);
        assertEquals(ChatTeleportState.Kind.Home, state.timers(50).getFirst().kind());
        state.sent("homecancel", 60);
        assertTrue(state.timers(60).isEmpty());
    }

    @Test void serverCooldownCanBeReportedWithoutRepeatingTheCommandName() {
        var state = new ChatTeleportState();
        state.sent("essentials:home base", 1_000);
        state.message("You must wait 30 seconds before you can use this command again.", 1_010);
        assertEquals(ChatTeleportState.Phase.Cooldown, state.timers(1_010).getFirst().phase());
        assertEquals("Home base: cooldown 20s", state.timers(11_010).getFirst().label(11_010));
        assertTrue(state.timers(31_010).isEmpty());
    }

    @Test void cooldownCanFollowSuccessOrExplainWhyTeleportIsUnavailable() {
        var state = new ChatTeleportState();
        state.sent("home base", 0);
        state.message("You have been teleported home", 10);
        state.message("Home cooldown: 30 seconds", 20);
        assertEquals("Home CD 30s", state.timers(20).getFirst().summary(20));
        state.sent("tpa Alice", 30);
        state.message("You can't teleport for another 10 seconds", 40);
        assertEquals(ChatTeleportState.Phase.Cooldown, state.timers(40).getLast().phase());
    }

    @Test void compactCountdownKeepsSecondsVisibleAheadOfLongTargetNames() {
        var state = new ChatTeleportState();
        state.sent("home a_very_long_home_name", 0);
        state.message("Teleporting in 5 seconds", 10);
        assertEquals("Home 5s", state.timers(10).getFirst().summary(10));
    }

    @Test void ignoresUnrelatedDurationsAndCommandsAndDoesNotRetainAcrossSessions() {
        var state = new ChatTeleportState();
        state.message("Teleporting in 10 seconds", 0);
        assertTrue(state.timers(0).isEmpty());
        state.sent("msg Alice hello", 0);
        assertTrue(state.timers(0).isEmpty());
        state.sent("home base", 0);
        state.message("Restart in 30 seconds", 10);
        assertEquals(ChatTeleportState.Phase.Waiting, state.timers(10).getFirst().phase());
        state.message("Home coordinates: 50 70 30", 10);
        assertEquals(ChatTeleportState.Phase.Waiting, state.timers(10).getFirst().phase());
        state.clear();
        assertTrue(state.timers(20).isEmpty());
    }

    @Test void failuresAndTimeoutsClearPendingCommands() {
        var state = new ChatTeleportState();
        state.sent("home missing", 0);
        state.message("That home does not exist", 10);
        assertTrue(state.timers(10).isEmpty());
        state.sent("tpa Alice", 20);
        state.message("Teleport request denied", 30);
        assertTrue(state.timers(30).isEmpty());
        state.sent("home base", 40);
        assertTrue(state.timers(120_040).isEmpty());
    }

    @Test void mergesHomesWithoutDuplicateButtonsOrCommandInjection() {
        assertEquals(List.of("Base", "farm", "nether", "基地"), ChatHomeNames.merge(
            List.of("Base", "farm"), List.of("base", "nether", "基地", "/home", "base;spawn", "base\nspawn", " ", "..")));
    }

    @Test void parsesExplicitHomeListsButIgnoresNormalChat() {
        assertEquals(List.of("Base", "farm", "nether"), ChatHomeNames.fromMessage("§6Your homes (3/5): §fBase, farm, nether"));
        assertTrue(ChatHomeNames.fromMessage("Alice: my homes are Base, farm").isEmpty());
        assertTrue(ChatHomeNames.fromMessage("Homes: none.").isEmpty());
    }

    @Test void everyOptionIsReachableAndEveryPageFitsEvenWithLongLabels() {
        for (int available : List.of(1, 12, 70, 200, 800)) {
            var pages = ChatHudPages.pack(List.of(40, 500, 10, 80, 120, 25), available);
            List<Integer> indices = new ArrayList<>();
            for (var page : pages) {
                int width = page.stream().mapToInt(ChatHudPages.Item::width).sum() + Math.max(0, page.size() - 1) * 2;
                assertTrue(width <= available);
                page.forEach(item -> indices.add(item.index()));
            }
            assertEquals(List.of(0, 1, 2, 3, 4, 5), indices);
        }
        assertEquals(List.of(List.of()), ChatHudPages.pack(List.of(), 100));
    }

    @Test void hudMatchesChatBackgroundRatherThanWindowWidth() {
        assertEquals(new ChatHudBounds(0, 332, 203), ChatHudBounds.of(320, 1, 960, 240));
        assertEquals(new ChatHudBounds(0, 186, 203), ChatHudBounds.of(180, 0.5, 960, 240));
        assertEquals(320, ChatHudBounds.of(320, 1, 320, 240).right());
        assertEquals(52, ChatHudBounds.of(40, 1, 960, 240).width());
        assertEquals(342, ChatHudBounds.of(320, 1, 960, 240, 10).width());
        assertEquals(331, ChatHudBounds.of(320, 0.5, 960, 240, 10).width());
    }

    @Test void narrowChatKeepsEveryCompactControlInsideItsEdges() {
        for (int chatWidth : List.of(40, 100, 180, 320)) for (double scale : List.of(0.25, 0.5, 0.75, 1.0)) {
            var bounds = ChatHudBounds.of(chatWidth, scale, 960, 240);
            int available = bounds.width() - 26; // 1px margins, 24px navigation.
            var pages = ChatHudPages.pack(List.of(25, 38, 60, 40, 120, 40), available);
            List<Integer> indices = new ArrayList<>();
            for (var page : pages) {
                int rowWidth = page.stream().mapToInt(ChatHudPages.Item::width).sum() + (page.size() - 1) * 2;
                assertTrue(rowWidth <= available);
                page.forEach(item -> indices.add(item.index()));
            }
            assertEquals(List.of(0, 1, 2, 3, 4, 5), indices);
            assertTrue(bounds.top() >= 200 && bounds.top() + 25 <= 240,
                "Two closed-chat timers must fit below the messages.");
        }
    }

    @Test void successfulHomeEditsUpdateNamesWithoutChangingOtherHomes() {
        var rename = ChatHomeEdit.parse("/homerename Base new_base");
        assertNotNull(rename);
        assertEquals(List.of("new_base", "Farm"), rename.apply(List.of("base", "Farm")));
        assertTrue(rename.confirmedBy("Home Base has been renamed to new_base."));
        assertFalse(rename.confirmedBy("Home could not be renamed: name already exists."));
        assertFalse(rename.confirmedBy("Home renamed failed due to an error."));
        var delete = ChatHomeEdit.parse("delhome BASE");
        assertNotNull(delete);
        assertTrue(delete.confirmedBy("Successfully deleted home Base."));
        assertFalse(delete.confirmedBy("Home not found; nothing was deleted."));
        assertEquals(List.of("Farm"), delete.apply(List.of("base", "Farm")));
    }

    @Test void incompleteOrUnrelatedCommandsCannotChangeCachedHomes() {
        assertNull(ChatHomeEdit.parse("homerename base "));
        assertNull(ChatHomeEdit.parse("homerename base other extra"));
        assertNull(ChatHomeEdit.parse("homerename base /spawn"));
        assertNull(ChatHomeEdit.parse("home base"));
        assertNull(ChatHomeEdit.parse("delhome"));
        assertNotNull(ChatHomeEdit.parse("/plugin:homerename base new_base"));
        assertFalse(ChatHomeEdit.parse("delhome base").confirmedBy("Player has been removed."));
    }

    @Test void sixB6tAcceptAndDenyAliasesAndCancelAliasUpdateTpaState() {
        var state = new ChatTeleportState();
        state.sent("tpy Alice", 0);
        assertEquals(ChatTeleportState.Kind.TPA, state.timers(10).getFirst().kind());
        state.sent("tpn Alice", 20);
        assertTrue(state.timers(20).isEmpty());
        state.sent("tpa Alice", 30);
        state.sent("tpcancel", 40);
        assertTrue(state.timers(40).isEmpty());
    }

    @Test void cancelChoicesFollowTheMatchingActiveTeleportOrOutgoingRequest() {
        var state = new ChatTeleportState();
        assertFalse(state.canCancel(ChatTeleportState.Kind.Home, 0));
        assertFalse(state.canCancel(ChatTeleportState.Kind.TPA, 0));
        state.sent("home base", 10);
        assertTrue(state.canCancel(ChatTeleportState.Kind.Home, 10));
        assertFalse(state.canCancel(ChatTeleportState.Kind.TPA, 10));
        state.sent("tpa Alice", 20);
        state.message("Your teleport request expires after 30 seconds", 30);
        assertTrue(state.canCancel(ChatTeleportState.Kind.Home, 30));
        assertTrue(state.canCancel(ChatTeleportState.Kind.TPA, 30));
        state.sent("homecancel", 40);
        assertFalse(state.canCancel(ChatTeleportState.Kind.Home, 40));
        assertTrue(state.canCancel(ChatTeleportState.Kind.TPA, 40));
        assertFalse(state.canCancel(ChatTeleportState.Kind.TPA, 30_030));
    }

    @Test void cooldownAndElapsedWarmupCannotBeCancelledEvenWhileWaitingForServerCompletion() {
        var state = new ChatTeleportState();
        state.sent("home base", 0);
        state.message("Home cooldown: 30 seconds", 10);
        assertFalse(state.canCancel(ChatTeleportState.Kind.Home, 10));
        state.sent("tpa Alice", 20);
        state.message("Teleporting in 3 seconds", 30);
        assertTrue(state.canCancel(ChatTeleportState.Kind.TPA, 3_029));
        assertFalse(state.canCancel(ChatTeleportState.Kind.TPA, 3_030));
        assertFalse(state.timers(3_030).isEmpty());
        assertFalse(state.canCancel(ChatTeleportState.Kind.TPA, 3_031));
    }

    @Test void completionFailureAndSessionResetRemoveCancellationAvailability() {
        var state = new ChatTeleportState();
        state.sent("home base", 0);
        state.message("You have been teleported home", 10);
        assertFalse(state.canCancel(ChatTeleportState.Kind.Home, 10));
        state.sent("home missing", 20);
        state.message("That home does not exist", 30);
        assertFalse(state.canCancel(ChatTeleportState.Kind.Home, 30));
        state.sent("tpa Alice", 40);
        assertFalse(state.canCancel(ChatTeleportState.Kind.TPA, 120_040));
        state.sent("home base", 120_050);
        state.clear();
        assertFalse(state.canCancel(ChatTeleportState.Kind.Home, 120_050));
    }
}
