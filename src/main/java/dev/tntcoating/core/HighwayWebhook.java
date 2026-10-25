/*
 * Part of Comet's Highway Fly module.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import meteordevelopment.meteorclient.MeteorClient;

/**
 * Posts one Discord webhook message for a Highway Fly event. The message always has the same shape, so the different
 * events are easy to scan in one channel: a short title, a plain description, the player's context and any event
 * fields that matter.
 */
public final class HighwayWebhook {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String FOOTER = "Comet Highway Fly";
    private static final Pattern MENTION =
        Pattern.compile("^(?:<@&(\\d{5,25})>|<@!?(\\d{5,25})>|(\\d{5,25}))$");

    /** The events worth telling a player about while they are away or busy flying. */
    public enum Event {
        STARTED("🚀 Flight started", 0x57F287),
        RESUMED("✈️ Flight resumed", 0x57F287),
        STATUS("📊 Flight status", 0x5865F2),
        DIMENSION("🌍 Dimension changed", 0xFAA61A),
        LAVA_AVOIDANCE("🔥 Lava avoidance", 0xFAA61A),
        DEATH("☠️ You died", 0xED4245),
        FINISHED("🏁 Route finished", 0x57F287),
        STOPPED("🛑 Route stopped", 0xFAA61A),
        SAFETY_LOGOUT("🛡️ Safety logout", 0xED4245),
        DISCONNECTED("🔌 Disconnected", 0xFAA61A),
        RECOVERY("🔄 Lobby recovery", 0x5865F2),
        LOGIN_FAILED("🔑 Login failed", 0xED4245),
        NEEDS_HELP("⚠️ Needs attention", 0xFAA61A),
        PORTAL("🌀 Portal", 0x5865F2),
        TEST("🔔 Webhook test", 0x5865F2);

        private final String title;
        private final int color;

        Event(String title, int color) {
            this.title = title;
            this.color = color;
        }

        public String title() {
            return title;
        }

        public int color() {
            return color;
        }
    }

    /** Who and where the event happened; null when there is no player context, such as a main-menu recovery. */
    public record Context(String player, String server, String dimension, int x, int y, int z) {}

    /**
     * An extra field for an event, such as a destination or a reason. The value is escaped as Discord markdown unless
     * {@code code} is set, in which case it is wrapped in an inline code block instead.
     */
    public record Field(String name, String value, boolean inline, boolean code) {
        public Field(String name, String value, boolean inline) {
            this(name, value, inline, false);
        }
    }

    private HighwayWebhook() {}

    /** Whether a configured URL is safe to post to. Blank and non-Discord URLs are ignored. */
    public static boolean configured(String url) {
        if (url == null) return false;
        String trimmed = url.strip();
        return !trimmed.isEmpty() && DiscordWebhook.isWebhookUrl(trimmed);
    }

    public static void send(String url, Event event, String description, Context context) {
        send(url, event, description, context, List.of());
    }

    /** Builds the message now, on the caller's thread, then posts it without blocking the game. */
    public static void send(String url, Event event, String description, Context context, List<Field> extra) {
        send(url, event, description, context, extra, "");
    }

    /**
     * Same as above, optionally pinging one Discord user or role. The mention is restricted to a numeric id, so no
     * arbitrary content (such as {@code @everyone}) can ever be injected.
     */
    public static void send(String url, Event event, String description, Context context, List<Field> extra,
        String mention) {
        if (!configured(url)) return;
        JsonObject payload = payload(event, description, context, extra, Instant.now(), mention);
        HttpRequest request = HttpRequest.newBuilder(DiscordWebhook.confirmedUri(url))
            .timeout(TIMEOUT)
            .header("User-Agent", FOOTER)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();
        WebhookDelivery.send(request).thenAccept(result -> {
            if (result.outcome() != WebhookDelivery.Outcome.DELIVERED) {
                // Never log the endpoint, response body or transport exception: they can contain the webhook token.
                MeteorClient.LOG.warn("Highway Fly webhook '{}' was not delivered: {} (HTTP {})", event.title(),
                    result.outcome(), result.status());
            }
        });
    }

    /** Package-private so the message shape can be tested without an HTTP server. */
    static JsonObject payload(Event event, String description, Context context, List<Field> extra, Instant time) {
        return payload(event, description, context, extra, time, "");
    }

    static JsonObject payload(Event event, String description, Context context, List<Field> extra, Instant time,
        String mention) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", FOOTER);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        Mention target = parseMention(mention);
        if (target != null) {
            payload.addProperty("content", target.role() ? "<@&" + target.id() + ">" : "<@" + target.id() + ">");
            JsonArray ids = new JsonArray();
            ids.add(target.id());
            mentions.add(target.role() ? "roles" : "users", ids);
        }
        payload.add("allowed_mentions", mentions);

        JsonObject embed = new JsonObject();
        embed.addProperty("title", event.title());
        embed.addProperty("description", DiscordWebhook.escape(description == null ? "" : description));
        embed.addProperty("color", event.color());

        JsonArray fields = new JsonArray();
        if (context != null) {
            if (context.player() != null && !context.player().isBlank()) {
                String player = DiscordWebhook.escape(context.player());
                fields.add(field("👤 Player", player, true));
                JsonObject thumbnail = new JsonObject();
                thumbnail.addProperty("url", "https://mc-heads.net/avatar/"
                    + URLEncoder.encode(context.player(), StandardCharsets.UTF_8) + "/128");
                embed.add("thumbnail", thumbnail);
            }
            if (context.server() != null && !context.server().isBlank()) {
                fields.add(field("🌐 Server", DiscordWebhook.escape(context.server()), true));
            }
            if (context.dimension() != null && !context.dimension().isBlank()) {
                fields.add(field("🌍 Dimension", DiscordWebhook.escape(context.dimension()), true));
            }
            fields.add(field("📍 Position", "`" + context.x() + " " + context.y() + " " + context.z() + "`", true));
        }
        if (extra != null) {
            for (Field entry : extra) {
                if (entry == null || entry.name() == null || entry.value() == null || entry.value().isBlank()) continue;
                String value = entry.code() ? "`" + entry.value() + "`" : DiscordWebhook.escape(entry.value());
                fields.add(field(entry.name(), value, entry.inline()));
            }
        }
        if (!fields.isEmpty()) embed.add("fields", fields);

        JsonObject footer = new JsonObject();
        footer.addProperty("text", FOOTER + (context == null || context.server() == null || context.server().isBlank()
            ? "" : " · " + DiscordWebhook.escape(context.server())));
        embed.add("footer", footer);
        embed.addProperty("timestamp", time.toString());
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        return payload;
    }

    /** A numeric Discord user or role id extracted from a raw id or a copy-pasted mention. */
    record Mention(String id, boolean role) {}

    static Mention parseMention(String raw) {
        if (raw == null) return null;
        String value = raw.strip();
        if (value.isEmpty()) return null;
        Matcher matcher = MENTION.matcher(value);
        if (!matcher.matches()) return null;
        String id = matcher.group(1);
        if (id == null) id = matcher.group(2);
        if (id == null) id = matcher.group(3);
        return new Mention(id, matcher.group(1) != null);
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value);
        field.addProperty("inline", inline);
        return field;
    }
}
