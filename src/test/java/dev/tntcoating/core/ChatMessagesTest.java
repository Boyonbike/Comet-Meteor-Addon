package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChatMessagesTest {
    @Test void recognisesPrivateFormatsAndKeepsTheBodyIntact() {
        ChatMessages messages = new ChatMessages();
        for (String raw : List.of("From Alice: hello: world", "§7[VIP] §fFROM Alice: hello: world",
            "Alice whispers: hello: world", "Alice whispers to you: hello: world", "[Alice -> me] hello: world",
            "[Alice → you] hello: world")) {
            var message = messages.parse(raw, "Self", 123);
            assertNotNull(message, raw);
            assertEquals("Alice", message.player(), raw);
            assertEquals("hello: world", message.text(), raw);
            assertTrue(message.incoming(), raw);
            assertEquals(123, message.time());
        }
        for (String raw : List.of("To Alice: hi", "to Alice: hi", "[me -> Alice] hi", "You whisper to Alice: hi")) {
            var message = messages.parse(raw, "Self", 0);
            assertNotNull(message, raw);
            assertEquals("Alice", message.player());
            assertEquals("hi", message.text());
            assertFalse(message.incoming());
        }
    }

    @Test void recognisesPlayerHeadSpritesBeforeNames() {
        ChatMessages messages = new ChatMessages();
        for (String raw : List.of("You whisper to [unknown player head] uriauria: hey", "To [unknown player head] uriauria: hey")) {
            var message = messages.parse(raw, "Self", 0);
            assertNotNull(message, raw);
            assertEquals("uriauria", message.player(), raw);
            assertEquals("hey", message.text(), raw);
            assertFalse(message.incoming(), raw);
        }
        for (String raw : List.of("[unknown player head] uriauria whispers: ely fly", "From [unknown player head] uriauria: ely fly")) {
            var message = messages.parse(raw, "Self", 0);
            assertNotNull(message, raw);
            assertEquals("uriauria", message.player(), raw);
            assertEquals("ely fly", message.text(), raw);
            assertTrue(message.incoming(), raw);
        }
        assertNull(messages.parse("<Mallory> You whisper to [unknown player head] Alice: fake", "Self", 0));
    }

    @Test void publicChatCannotCreateConversationsByQuotingPrivateMessages() {
        ChatMessages messages = new ChatMessages();
        for (String raw : List.of("<Mallory> From Alice: fake", "[VIP] Mallory: Alice whispers: fake",
            "Server: From Alice: fake", "From NameTooLong123456: fake", "Homes: base", "[Alice -> Bob] public"))
            assertNull(messages.parse(raw, "Self", 0), raw);
        assertNull(messages.parse("From SELF: self message", "Self", 0));
    }

    @Test void customFormatsWorkAndInvalidSettingsDoNotBreakDefaults() {
        ChatMessages messages = new ChatMessages();
        messages.patterns(List.of("[", "no capture groups", "^PM (\\w+) > (.*)$", "^(invalid-name): (.*)$"),
            List.of("^SENT (\\w+) > (.*)$"));
        assertTrue(messages.parse("PM Alice > hello", "Self", 0).incoming());
        assertFalse(messages.parse("SENT Alice > hello", "Self", 0).incoming());
        assertNotNull(messages.parse("From Bob: still works", "Self", 0));
        assertNull(messages.parse("invalid-name: rejected", "Self", 0));
        messages.patterns(List.of(), List.of());
        assertNull(messages.parse("PM Alice > removed", "Self", 0));
    }

    @Test void conversationsMergeCaseAndUnreadOnlyCountsIncomingMessages() {
        ChatMessages messages = new ChatMessages();
        messages.add(new ChatMessages.Message("Alice", "one", true, 1), false);
        var snapshot = messages.conversation("ALICE");
        messages.add(new ChatMessages.Message("ALICE", "two", true, 2), false);
        messages.add(new ChatMessages.Message("Bob", "sent", false, 3), false);
        assertEquals(List.of("Bob", "Alice"), messages.recent());
        assertEquals(2, messages.unread());
        assertEquals(2, messages.unread("alice"));
        assertEquals(2, messages.conversation("alice").messages().size());
        assertEquals(1, snapshot.messages().size(), "Snapshots cannot change under a UI reader");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.messages().clear());
        messages.read("aLiCe");
        assertEquals(0, messages.unread());
        messages.add(new ChatMessages.Message("Alice", "visible", true, 4), true);
        assertEquals(0, messages.unread());
        assertEquals(3, messages.conversation("alice").messages().size());
    }

    @Test void historyIsBoundedAndDisconnectClearsEverything() {
        ChatMessages messages = new ChatMessages();
        for (int i = 0; i < ChatMessages.MAX_MESSAGES + 5; i++)
            messages.add(new ChatMessages.Message("Alice", "message " + i, true, i), false);
        assertEquals(ChatMessages.MAX_MESSAGES, messages.conversation("Alice").messages().size());
        assertEquals("message 5", messages.conversation("Alice").messages().getFirst().text());
        for (int i = 0; i < ChatMessages.MAX_CONVERSATIONS; i++)
            messages.add(new ChatMessages.Message("Player" + i, "hello", true, i), false);
        assertEquals(ChatMessages.MAX_CONVERSATIONS, messages.recent().size());
        assertTrue(messages.conversation("Alice").messages().isEmpty());
        messages.clear();
        assertTrue(messages.recent().isEmpty());
        assertEquals(0, messages.unread());
        assertTrue(messages.conversation("Player0").messages().isEmpty());
    }
}
