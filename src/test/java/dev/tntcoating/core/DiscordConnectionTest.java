package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DiscordConnectionTest {
    @Test void acceptsNumericIdsAndAnAvailableToken() {
        assertNull(DiscordConnection.problem("secret", "123", "456"));
    }

    @Test void rejectsMissingTokenWithoutDisclosingIt() {
        assertEquals("Paste the bot token first.", DiscordConnection.problem("", "123", "456"));
    }

    @Test void rejectsNonNumericGuildAndChannelIds() {
        assertEquals("guild-id must be the server ID (numbers only).", DiscordConnection.problem("secret", "server", "456"));
        assertEquals("ign-channel-id must be the channel ID (numbers only).", DiscordConnection.problem("secret", "123", "#ign"));
        assertNotNull(DiscordConnection.problem("secret", "", "456"));
        assertNotNull(DiscordConnection.problem("secret", "123", ""));
    }

    @Test void stripsOptionalBotPrefixAndSurroundingSpace() {
        assertEquals("secret", DiscordConnection.token("  bOt secret  "));
        assertEquals("secret", DiscordConnection.token(" secret "));
        assertEquals("", DiscordConnection.token("Bot "));
    }

    @Test void preservesExistingTokenWhenInputIsBlank() {
        assertEquals("old-secret", DiscordConnection.replacementToken("  ", "Bot old-secret"));
        assertEquals("new-secret", DiscordConnection.replacementToken("Bot new-secret", "old-secret"));
        assertEquals("old-secret", DiscordConnection.replacementToken(null, "old-secret"));
    }
}
