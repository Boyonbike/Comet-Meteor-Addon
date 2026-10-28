package dev.tntcoating.core;

/** Validation shared by the Discord settings form and sync entry point. Never echoes credentials. */
public final class DiscordConnection {
    private DiscordConnection() {}

    public static String problem(String token, String guild, String channel) {
        if (token == null || token.isBlank()) return "Paste the bot token first.";
        if (guild == null || !guild.matches("[0-9]+")) return "guild-id must be the server ID (numbers only).";
        if (channel == null || !channel.matches("[0-9]+")) return "ign-channel-id must be the channel ID (numbers only).";
        return null;
    }

    public static String token(String input) {
        String token = input == null ? "" : input.strip();
        if (token.equalsIgnoreCase("bot")) return "";
        if (token.regionMatches(true, 0, "bot ", 0, 4)) token = token.substring(4).strip();
        return token;
    }

    /** The form intentionally keeps the stored token when its password field is empty. */
    public static String replacementToken(String input, String existing) {
        return token(input == null || input.isBlank() ? existing : input);
    }
}
