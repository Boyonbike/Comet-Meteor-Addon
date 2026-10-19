package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ChatSenderTest {
    private static final Predicate<String> ONLINE = name -> Set.of("alice", "bob").contains(name.toLowerCase());

    @Test void publicAndPrivateChatFromOnlinePlayersIsRecognised() {
        for (String line : new String[] {"<Alice> home deleted", "[VIP] Alice: your home was renamed",
            "§7» §fBob: teleported in 5 seconds", "From Alice: Homes: fake", "Alice whispers to you: request accepted",
            "[Alice -> me] teleportation cancelled"}) {
            assertTrue(ChatSender.fromPlayer(line, ONLINE), line);
        }
    }

    @Test void serverRepliesThatLookLikeChatStillReachTheParsers() {
        for (String line : new String[] {"Homes: base, farm", "Error: no home called base",
            "§6[TPA] §fAlice has requested to teleport to you.", "Teleport request from Alice", "Teleporting in 3 seconds",
            "Mallory: Alice's teleport request has expired."}) {
            assertFalse(ChatSender.fromPlayer(line, ONLINE), line);
        }
    }

    @Test void apparentSenderReadsTheNameOnly() {
        assertEquals("Alice", ChatSender.apparentSender("[Owner] [VIP] <Alice> hi"));
        assertEquals("Homes", ChatSender.apparentSender("Homes: base"));
        assertNull(ChatSender.apparentSender("Alice has requested to teleport to you."));
        assertEquals("plain text", ChatSender.plain("§aplain §ltext"));
    }
}
