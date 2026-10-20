package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChatToolsDataTest {
    @Test void favoriteHomesStayWithTheCorrectServerAndAccount() {
        var entries = ChatHomeFavorites.toggle(List.of(), "server-a/account-1", "Base");
        entries = ChatHomeFavorites.toggle(entries, "server-b/account-1", "Farm");
        entries = ChatHomeFavorites.toggle(entries, "server-a/account-2", "Nether");
        assertEquals(List.of("Base"), ChatHomeFavorites.names(entries, "server-a/account-1"));
        assertEquals(List.of("Farm"), ChatHomeFavorites.names(entries, "server-b/account-1"));
        assertEquals(List.of("Nether"), ChatHomeFavorites.names(entries, "server-a/account-2"));
        assertTrue(ChatHomeFavorites.names(entries, "server-c/account-1").isEmpty());
        entries = ChatHomeFavorites.toggle(entries, "server-a/account-1", "BASE");
        assertTrue(ChatHomeFavorites.names(entries, "server-a/account-1").isEmpty());
        assertEquals(List.of("Farm"), ChatHomeFavorites.names(entries, "server-b/account-1"));
    }

    @Test void confirmedHomeRenamesAndDeletesUpdateFavoritesWithoutAffectingOtherServers() {
        var entries = ChatHomeFavorites.toggle(List.of(), "a", "Base");
        entries = ChatHomeFavorites.toggle(entries, "b", "Base");
        entries = ChatHomeFavorites.edit(entries, "a", ChatHomeEdit.parse("homerename base NewBase"));
        assertEquals(List.of("NewBase"), ChatHomeFavorites.names(entries, "a"));
        assertEquals(List.of("Base"), ChatHomeFavorites.names(entries, "b"));
        entries = ChatHomeFavorites.edit(entries, "a", ChatHomeEdit.parse("sethome Other"));
        assertEquals(List.of("NewBase"), ChatHomeFavorites.names(entries, "a"), "Creating a home does not automatically star it");
        entries = ChatHomeFavorites.edit(entries, "a", ChatHomeEdit.parse("delhome newbase"));
        assertTrue(ChatHomeFavorites.names(entries, "a").isEmpty());
        assertEquals(List.of("Base"), ChatHomeFavorites.names(entries, "b"));
    }

    @Test void savedCooldownsStayWithTheirServerAndAccount() {
        var home = ChatTeleportState.Kind.Home;
        var tpa = ChatTeleportState.Kind.TPA;
        var sixb = new ChatSavedCooldowns.Saved(java.util.Map.of(home, 600_000L, tpa, 300_000L), java.util.Map.of(home, 480_000L, tpa, 0L));
        var entries = ChatSavedCooldowns.write(List.of(), "6b6t.org/account-1", sixb);
        entries = ChatSavedCooldowns.write(entries, "other.net/account-1",
            new ChatSavedCooldowns.Saved(java.util.Map.of(home, 30_000L), java.util.Map.of()));
        assertEquals(sixb, ChatSavedCooldowns.read(entries, "6b6t.org/account-1"), "Manual zero (no cooldown) is kept");
        assertEquals(java.util.Map.of(home, 30_000L), ChatSavedCooldowns.read(entries, "other.net/account-1").learned());
        assertEquals(ChatSavedCooldowns.Saved.NONE, ChatSavedCooldowns.read(entries, "6b6t.org/account-2"));
        entries = ChatSavedCooldowns.write(entries, "6b6t.org/account-1", new ChatSavedCooldowns.Saved(java.util.Map.of(home, 300_000L), java.util.Map.of()));
        assertEquals(new ChatSavedCooldowns.Saved(java.util.Map.of(home, 300_000L), java.util.Map.of()),
            ChatSavedCooldowns.read(entries, "6b6t.org/account-1"), "Going back to Auto removes the manual entry");
        assertEquals(java.util.Map.of(home, 30_000L), ChatSavedCooldowns.read(entries, "other.net/account-1").learned());
        var broken = new java.util.ArrayList<>(entries);
        String prefix = entries.getLast().substring(0, entries.getLast().indexOf('|') + 1);
        broken.addAll(List.of(prefix + "Warp|5000", prefix + "TPA|soon", prefix + "TPA|0", prefix + "TPA", prefix + "TPA|5000|maybe",
            prefix + "Home|-1|manual"));
        assertEquals(new ChatSavedCooldowns.Saved(java.util.Map.of(home, 300_000L), java.util.Map.of()), ChatSavedCooldowns.read(broken, "6b6t.org/account-1"));
    }

    @Test void cooldownStepperSuitsShortAndLongCooldowns() {
        assertEquals(35, ChatSavedCooldowns.step(30, true));
        assertEquals(60, ChatSavedCooldowns.step(55, true));
        assertEquals(90, ChatSavedCooldowns.step(60, true));
        assertEquals(55, ChatSavedCooldowns.step(60, false));
        assertEquals(60, ChatSavedCooldowns.step(61, false));
        assertEquals(600, ChatSavedCooldowns.step(599, true), "A learned 9m 59s rounds to whole steps");
        assertEquals(570, ChatSavedCooldowns.step(599, false));
        assertEquals(660, ChatSavedCooldowns.step(600, true));
        assertEquals(570, ChatSavedCooldowns.step(600, false));
        assertEquals(0, ChatSavedCooldowns.step(3, false));
        assertEquals(0, ChatSavedCooldowns.step(0, false));
        assertEquals(86_400, ChatSavedCooldowns.step(86_400, true));
    }

    @Test void invalidHomeNamesCannotBecomeFavoriteCommands() {
        var entries = ChatHomeFavorites.toggle(List.of(), "a", "Good");
        for (String name : List.of("/spawn", "base\nspawn", "base other", "..", "base;spawn"))
            assertEquals(entries, ChatHomeFavorites.toggle(entries, "a", name), name);
    }

    @Test void recentsFollowInteractionOrderAndMergeUsernameCase() {
        var recents = new ChatRecentPlayers();
        recents.touch("Alice"); recents.touch("Bob"); recents.touch("ALICE"); recents.touch("bad name");
        assertEquals(List.of("ALICE", "Bob"), recents.names());
        var snapshot = recents.names();
        recents.touch("Charlie");
        assertEquals(List.of("ALICE", "Bob"), snapshot);
        assertEquals(List.of("Charlie", "ALICE", "Bob"), recents.names());
        for (int i = 0; i < 101; i++) recents.touch("Player" + i);
        assertEquals(100, recents.names().size());
        assertEquals("Player100", recents.names().getFirst());
        recents.clear();
        assertTrue(recents.names().isEmpty());
    }

    @Test void countdownsStayAttachedToTheMatchingPlayerOrHome() {
        var home = new ChatTeleportState.Timer(ChatTeleportState.Kind.Home, ChatTeleportState.Phase.Teleport, "Alice", 5_000, 20_000);
        var tpa = new ChatTeleportState.Timer(ChatTeleportState.Kind.TPA, ChatTeleportState.Phase.Request, "Bob", 30_000, 30_000);
        var incoming = new ChatTpaRequests.Request("BOB", false, 10_000);
        var timers = List.of(home, tpa);
        assertEquals("Teleporting in 4s", ChatCountdowns.home("alice", timers, 1_001));
        assertEquals("", ChatCountdowns.player("Alice", timers, List.of(incoming), 1_001));
        assertEquals("Request pending 29s \u00b7 Incoming request 9s", ChatCountdowns.player("Bob", timers, List.of(incoming), 1_001));
        assertEquals("", ChatCountdowns.home("Bob", timers, 1_001));
        assertEquals(List.of("Home Alice: teleport 4s", "TPA Bob: request 29s", "Request from BOB: 9s"),
            ChatCountdowns.lines(timers, List.of(incoming), 1_001));
    }

    @Test void countdownsDoNotInventDurationsOrClaimTeleportCompletion() {
        var state = new ChatTeleportState();
        state.sent("home base", 0);
        assertEquals(List.of("Home base: awaiting server"), ChatCountdowns.lines(state.timers(1), List.of(), 1));
        state.message("Teleporting in 2 seconds", 10);
        assertEquals(List.of("Home base: teleport 1s"), ChatCountdowns.lines(state.timers(1_011), List.of(), 1_011));
        assertEquals(List.of("Home base: awaiting server"), ChatCountdowns.lines(state.timers(2_010), List.of(), 2_010));
        state.message("You have been teleported home", 2_020);
        assertTrue(ChatCountdowns.lines(state.timers(2_021), List.of(), 2_021).isEmpty());
    }
}
