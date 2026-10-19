package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ChatServerSettingsTest {
    private static List<ChatServerSettings.Entry> leaves(List<ChatServerSettings.Entry> entries) {
        List<ChatServerSettings.Entry> result = new ArrayList<>();
        for (var entry : entries) {
            if (entry.menu()) result.addAll(leaves(entry.children()));
            else result.add(entry);
        }
        return result;
    }

    @Test void providesTheDocumentedServerPreferenceCommands() {
        var commands = leaves(ChatServerSettings.groups()).stream().map(ChatServerSettings.Entry::command).toList();
        for (String expected : List.of("/togglechat", "/togglewhispers", "/togglespam on", "/toggleranked off",
            "/bots chat off", "/bots whispers on", "/bots connections off", "/bots deaths on", "/togglebots off",
            "/connectionmsgs color", "/deathmsgs off", "/advancements chat personal", "/advancements chat others",
            "/advancements chat none", "/advancements toast off", "/toggletpa", "/chatcolor", "/namecolor",
            "/hotspot notify actionbar", "/hotspot color", "/buildermode", "/pvpmode")) {
            assertTrue(commands.contains(expected), expected);
        }
    }

    @Test void settingsRequiringNamesOrValuesStayOpenForInput() {
        var entries = leaves(ChatServerSettings.groups());
        for (String command : List.of("/skin ", "/sethome ", "/homerename ", "/hotspot name ", "/buildermode home ", "/hotspot notify ")) {
            assertTrue(entries.stream().anyMatch(entry -> command.equals(entry.command()) && entry.needsInput()), command);
        }
        assertTrue(entries.stream().filter(entry -> !entry.needsInput()).allMatch(entry -> !entry.command().endsWith(" ")));
    }

    @Test void everyMenuHasUniqueLabelsAndEveryLeafIsASingleCommand() { verify(ChatServerSettings.groups()); }

    private static void verify(List<ChatServerSettings.Entry> entries) {
        assertEquals(entries.size(), entries.stream().map(ChatServerSettings.Entry::label).distinct().count());
        for (var entry : entries) {
            assertFalse(entry.label().isBlank());
            if (entry.menu()) {
                assertNull(entry.command());
                verify(entry.children());
            } else {
                assertNotNull(entry.command());
                assertTrue(entry.command().startsWith("/"));
                assertFalse(entry.command().contains("\n"));
                assertFalse(entry.command().contains(";"));
            }
        }
    }

    @Test void onlyCommandsTheServerOffersRemainAndEmptyMenusDisappear() {
        var offered = java.util.Set.of("togglechat", "homes");
        var groups = ChatServerSettings.available(ChatServerSettings.groups(), offered::contains);
        assertEquals(java.util.List.of("Chat", "Homes & TPA"), groups.stream().map(ChatServerSettings.Entry::label).toList());
        assertEquals(java.util.List.of("Toggle chat"), groups.getFirst().children().stream().map(ChatServerSettings.Entry::label).toList());
        assertEquals(java.util.List.of("Home list"), groups.get(1).children().stream().map(ChatServerSettings.Entry::label).toList());
        assertTrue(ChatServerSettings.available(ChatServerSettings.groups(), root -> false).isEmpty());
        assertEquals(ChatServerSettings.groups(), ChatServerSettings.available(ChatServerSettings.groups(), root -> true));
    }

    @Test void rootCommandIgnoresArgumentsAndMenusHaveNone() {
        var bots = ChatServerSettings.groups().get(1);
        assertNull(bots.rootCommand());
        assertEquals("togglebots", bots.children().getFirst().children().getFirst().rootCommand());
        assertEquals("bots", bots.children().get(1).children().getFirst().rootCommand());
    }
}
