package dev.tntcoating.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comet's Discord webhook messages: a call to arms pings the clan to join a live grief, and a stash report logs a stash
 * with the clan's stash bot, which reads its fields.
 */
public final class DiscordWebhook {
    /** The call to arms' side bar, a fire red-orange. */
    static final int CALL_TO_ARMS_COLOR = 0xE8432E;
    /** The stash report's side bar, chest gold. */
    static final int STASH_REPORT_COLOR = 0xD4A017;
    /**
     * How the stash bot tells a stash report from a call to arms posted through the same webhook. It reads "grief alert"
     * because the bot already deployed matches the footer of the single module that came before the split.
     */
    public static final String STASH_REPORT_FOOTER = "Comet grief alert";
    static final String CALL_TO_ARMS_FOOTER = "Comet call to arms";
    /** The attached screenshot's file name, which the embed shows by referring to it. */
    public static final String SCREENSHOT = "screenshot.png";
    /** Screenshots wider than this are scaled down, to stay well inside Discord's upload limit. */
    static final int MAX_SCREENSHOT_WIDTH = 2560;
    private static final Pattern WEBHOOK = Pattern.compile("https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/\\d+/[\\w-]+/?");
    /** A bare role ID or a pasted role mention, <@&id>. */
    private static final Pattern ROLE = Pattern.compile("(?:<@&)?(\\d{15,21})>?");
    /** A bare user ID or a pasted user mention, <@id> or <@!id>. */
    private static final Pattern USER = Pattern.compile("(?:<@!?)?(\\d{15,21})>?");
    private static final Pattern MARKDOWN = Pattern.compile("[\\\\*_~`|>]");

    private DiscordWebhook() {}

    /** Who sent it, where they are, and what is loaded around them. The Discord ID is null in a call to arms. */
    public record Alert(String player, String discordId, int x, int y, int z, String dimension, int chestBlocks, String server) {}

    /** The parts of a message each player can leave out. The screenshot is captured and attached separately, with {@link #withScreenshot}. */
    public enum Part { NAME, FACE, COORDINATES, DIMENSION, DUBS, TPA, CALL_TO_ACTION, SERVER, SCREENSHOT }

    /** Only Discord webhook URLs are accepted, so a typo can't post your coordinates to another site. */
    public static boolean isWebhookUrl(String url) {
        return WEBHOOK.matcher(url.strip()).matches();
    }

    /** Ask Discord to confirm message creation, including validation errors, before reporting success. */
    public static URI confirmedUri(String url) {
        if (url == null || !isWebhookUrl(url)) throw new IllegalArgumentException("Invalid Discord webhook URL");
        return URI.create(url.strip() + "?wait=true");
    }

    /** The mention for the ping setting: @everyone, @here or a role from its ID or pasted mention; "" when blank for no ping, null when it is none of these. */
    public static String mention(String setting) {
        String text = setting.strip();
        if (text.startsWith("@")) text = text.substring(1);
        if (text.isEmpty()) return "";
        if (text.equalsIgnoreCase("everyone") || text.equalsIgnoreCase("here")) return "@" + text.toLowerCase(Locale.ROOT);
        Matcher matcher = ROLE.matcher(text);
        return matcher.matches() ? "<@&" + matcher.group(1) + ">" : null;
    }

    /** The Discord user ID from a pasted ID or user mention, or null when it is blank or neither. */
    public static String userId(String setting) {
        Matcher matcher = USER.matcher(setting.strip());
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** "Overworld", "Nether" or "The End"; other dimensions keep their ID. */
    public static String dimensionName(String id) {
        return switch (id) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
            default -> id;
        };
    }

    /** A call to arms with only the chosen parts, pinging a mention from {@link #mention}. A blank mention posts without a ping. */
    public static JsonObject callToArms(Alert alert, Set<Part> parts, String mention, String callToAction, Instant time) {
        String name = escape(alert.player());
        JsonObject payload = new JsonObject();
        // Mentions only ping from the message text, not from inside an embed.
        String join = parts.contains(Part.NAME) ? "join " + name + " now!" : "join now!";
        payload.addProperty("content", (mention.isEmpty() ? "" : mention + " ") + "🔥 **Live grief**, " + join);
        payload.add("allowed_mentions", allowedMentions(mention));
        JsonObject embed = embed("🔥 Live grief in progress", CALL_TO_ARMS_COLOR, alert, parts);
        if (parts.contains(Part.CALL_TO_ACTION) && !callToAction.isBlank()) embed.addProperty("description", callToAction.strip());
        JsonArray fields = new JsonArray();
        if (parts.contains(Part.NAME)) fields.add(field("👤 Player", name, true));
        if (parts.contains(Part.DUBS)) fields.add(dubs(alert));
        if (parts.contains(Part.DIMENSION)) fields.add(dimension(alert));
        if (parts.contains(Part.COORDINATES)) fields.add(coordinates(alert));
        // A code block: one click or long-press selects just the command, ready to paste into chat.
        if (parts.contains(Part.TPA)) fields.add(field("⚡ Join the grief", "```\n/tpa " + alert.player() + "\n```", false));
        return finish(payload, embed, fields, CALL_TO_ARMS_FOOTER, alert, parts, time);
    }

    /**
     * A stash report for the clan's stash bot, which credits the stash to the Discord account and logs the dubs, so both
     * are always included. Like /submit-stash it never carries coordinates, and nothing in it pings anyone.
     */
    public static JsonObject stashReport(Alert alert, Set<Part> parts, Instant time) {
        JsonObject payload = new JsonObject();
        payload.add("allowed_mentions", allowedMentions(""));
        JsonObject embed = embed("📦 Stash report", STASH_REPORT_COLOR, alert, parts);
        JsonArray fields = new JsonArray();
        if (parts.contains(Part.NAME)) fields.add(field("👤 Player", escape(alert.player()), true));
        // Embeds never ping, so this shows the account without notifying it.
        fields.add(field("💬 Discord", "<@" + alert.discordId() + ">", true));
        fields.add(dubs(alert));
        if (parts.contains(Part.DIMENSION)) fields.add(dimension(alert));
        return finish(payload, embed, fields, STASH_REPORT_FOOTER, alert, parts, time);
    }

    /** A copy of the payload that shows the attached {@link #SCREENSHOT} as the embed's image. */
    public static JsonObject withScreenshot(JsonObject payload) {
        JsonObject copy = payload.deepCopy();
        JsonObject image = new JsonObject();
        image.addProperty("url", "attachment://" + SCREENSHOT);
        copy.getAsJsonArray("embeds").get(0).getAsJsonObject().add("image", image);
        JsonObject attachment = new JsonObject();
        attachment.addProperty("id", 0);
        attachment.addProperty("filename", SCREENSHOT);
        JsonArray attachments = new JsonArray();
        attachments.add(attachment);
        copy.add("attachments", attachments);
        return copy;
    }

    /** A multipart/form-data body carrying the payload and the PNG, as Discord takes webhook uploads. */
    public static byte[] multipart(String boundary, JsonObject payload, byte[] png) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(png.length + 1024);
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n"
            + payload + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"" + SCREENSHOT
            + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(png);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    /** The smallest factor that brings the screenshot within the width limit. Minecraft only scales by factors that divide both sides; 1 when none does. */
    public static int downscale(int width, int height) {
        for (int factor = 1; factor <= 8; factor++) {
            if (width % factor == 0 && height % factor == 0 && width / factor <= MAX_SCREENSHOT_WIDTH) return factor;
        }
        return 1;
    }

    /** Only the chosen mention can ping: a role ping never lets an @everyone typed into the call to action through. */
    private static JsonObject allowedMentions(String mention) {
        JsonObject mentions = new JsonObject();
        JsonArray parse = new JsonArray();
        // Discord's "everyone" covers @here too.
        if (mention.startsWith("@")) parse.add("everyone");
        mentions.add("parse", parse);
        JsonArray roles = new JsonArray();
        if (mention.startsWith("<@&")) roles.add(mention.substring(3, mention.length() - 1));
        mentions.add("roles", roles);
        return mentions;
    }

    private static JsonObject embed(String title, int color, Alert alert, Set<Part> parts) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("color", color);
        if (parts.contains(Part.FACE)) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", "https://mc-heads.net/avatar/" + URLEncoder.encode(alert.player(), StandardCharsets.UTF_8) + "/128");
            embed.add("thumbnail", thumbnail);
        }
        return embed;
    }

    private static JsonObject finish(JsonObject payload, JsonObject embed, JsonArray fields, String footerText, Alert alert, Set<Part> parts, Instant time) {
        if (!fields.isEmpty()) embed.add("fields", fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", !parts.contains(Part.SERVER) || alert.server().isBlank() ? footerText : footerText + " · " + alert.server());
        embed.add("footer", footer);
        embed.addProperty("timestamp", time.toString());
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        return payload;
    }

    private static JsonObject dubs(Alert alert) {
        return field("📦 Dubs", "**" + DubCount.format(alert.chestBlocks()) + "**", true);
    }

    private static JsonObject dimension(Alert alert) {
        return field("🌍 Dimension", escape(alert.dimension()), true);
    }

    private static JsonObject coordinates(Alert alert) {
        return field("📍 Coordinates", "`" + alert.x() + " " + alert.y() + " " + alert.z() + "`", true);
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value);
        field.addProperty("inline", inline);
        return field;
    }

    /** Text shown as typed: an underscore in a name like Big_Dub_ would otherwise italicise it. */
    static String escape(String text) {
        return MARKDOWN.matcher(text).replaceAll("\\\\$0");
    }
}
