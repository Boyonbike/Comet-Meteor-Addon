package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DiscordConnectionTest {
    @Test void acceptsAPlainHttpsRelay() {
        assertNull(DiscordConnection.problem("https://comet-ign-relay.example.workers.dev"));
    }

    @Test void explainsAMissingRelay() {
        assertEquals("Paste the clan's relay URL first.", DiscordConnection.problem(""));
        assertEquals("Paste the clan's relay URL first.", DiscordConnection.problem(null));
    }

    @Test void refusesAnythingButAPlainHttpsLink() {
        assertNotNull(DiscordConnection.problem("http://relay.example.com"));
        assertNotNull(DiscordConnection.problem("relay.example.com"));
        assertNotNull(DiscordConnection.problem("https://user:secret@relay.example.com"));
        assertNotNull(DiscordConnection.problem("https://relay.example.com/?key=1"));
        assertNotNull(DiscordConnection.problem("https://relay.example.com/#ign"));
        assertNotNull(DiscordConnection.problem("https://relay example.com"));
    }

    @Test void normalisesTrailingSlashesAndSpaces() {
        assertEquals("https://relay.example.com", DiscordConnection.relay("  https://relay.example.com//  "));
        assertEquals("https://relay.example.com/clan", DiscordConnection.relay("https://relay.example.com/clan/"));
        assertEquals("", DiscordConnection.relay("ftp://relay.example.com"));
    }
}
