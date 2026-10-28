package dev.tntcoating.core;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Validation shared by the Discord settings form and sync entry point. The connection is one public relay URL; the
 * relay holds the bot token and fixes the guild and channel, so nothing secret is stored here.
 */
public final class DiscordConnection {
    private DiscordConnection() {}

    public static String problem(String relay) {
        if (relay == null || relay.isBlank()) return "Paste the clan's relay URL first.";
        if (relay(relay).isEmpty()) return "The relay URL must be a plain https:// link, such as https://comet-ign-relay.example.workers.dev.";
        return null;
    }

    /** The relay's base URL without trailing slashes, or "" when it is not a plain https URL. */
    public static String relay(String input) {
        String relay = input == null ? "" : input.strip();
        while (relay.endsWith("/")) relay = relay.substring(0, relay.length() - 1);
        try {
            URI uri = new URI(relay);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) return "";
            return relay;
        } catch (URISyntaxException exception) {
            return "";
        }
    }
}
