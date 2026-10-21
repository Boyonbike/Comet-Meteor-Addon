package dev.tntcoating.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Discord webhook message Grief Alert posts: a ping, then an embed with who, where, how many dubs and how to join. */
public final class GriefWebhook {
    /** The embed's side bar, a fire red-orange. */
    static final int COLOR = 0xE8432E;
    /** The attached screenshot's file name, which the embed shows by referring to it. */
    public static final String SCREENSHOT = "grief.png";
    /** Screenshots wider than this are scaled down, to stay well inside Discord's upload limit. */
    static final int MAX_SCREENSHOT_WIDTH = 2560;
    private static final Pattern WEBHOOK = Pattern.compile("https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/\\d+/[\\w-]+/?");
    /** A bare role ID or a pasted role mention, <@&id>. */
    private static final Pattern ROLE = Pattern.compile("(?:<@&)?(\\d{15,21})>?");
    /** A bare user ID or a pasted user mention, <@id> or <@!id>. */
    private static final Pattern USER = Pattern.compile("(?:<@!?)?(\\d{15,21})>?");
    private static final Pattern MARKDOWN = Pattern.compile("[\\\\*_~`|>]");

    private GriefWebhook() {}

    /** Who is griefing, where, and what is loaded around them. */
    public record Alert(String player, String discordId, int x, int y, int z, String dimension, int chestBlocks, String server) {}

    /** The parts of the alert each player can leave out. The screenshot is captured and attached separately, with {@link #withScreenshot}. */
    public enum Part { NAME, FACE, COORDINATES, DIMENSION, DUBS, TPA, CALL_TO_ACTION, SERVER, SCREENSHOT }

    /** Only Discord webhook URLs are accepted, so a typo can't post your coordinates to another site. */
    public static boolean isWebhookUrl(String url) {
        return WEBHOOK.matcher(url.strip()).matches();
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

    /** The webhook request body with only the chosen parts, pinging a mention from {@link #mention}. A blank mention posts without a ping. */
    public static JsonObject payload(Alert alert, Set<Part> parts, String mention, String callToAction, Instant time) {
        String name = escape(alert.player());
        JsonObject payload = new JsonObject();
        // Mentions only ping from the message text, not from inside an embed.
        String join = parts.contains(Part.NAME) ? "join " + name + " now!" : "join now!";
        payload.addProperty("content", (mention.isEmpty() ? "" : mention + " ") + "🔥 **Live grief**, " + join);
        // Only the chosen mention can ping: a role ping never lets an @everyone typed into the call to action through.
        JsonObject mentions = new JsonObject();
        JsonArray parse = new JsonArray();
        // Discord's "everyone" covers @here too.
        if (mention.startsWith("@")) parse.add("everyone");
        mentions.add("parse", parse);
        JsonArray roles = new JsonArray();
        if (mention.startsWith("<@&")) roles.add(mention.substring(3, mention.length() - 1));
        mentions.add("roles", roles);
        payload.add("allowed_mentions", mentions);

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🔥 Live grief in progress");
        if (parts.contains(Part.CALL_TO_ACTION) && !callToAction.isBlank()) embed.addProperty("description", callToAction.strip());
        embed.addProperty("color", COLOR);
        if (parts.contains(Part.FACE)) {
            JsonObject thumbnail = new JsonObject();
            thumbnail.addProperty("url", "https://mc-heads.net/avatar/" + URLEncoder.encode(alert.player(), StandardCharsets.UTF_8) + "/128");
            embed.add("thumbnail", thumbnail);
        }
        JsonArray fields = new JsonArray();
        if (parts.contains(Part.NAME)) fields.add(field("👤 Player", name, true));
        // Always present: the clan's stash bot credits the alert to this account. Embeds never ping it.
        fields.add(field("💬 Discord", "<@" + alert.discordId() + ">", true));
        if (parts.contains(Part.DUBS)) fields.add(field("📦 Dubs", "**" + DubCount.format(alert.chestBlocks()) + "**", true));
        if (parts.contains(Part.DIMENSION)) fields.add(field("🌍 Dimension", escape(alert.dimension()), true));
        if (parts.contains(Part.COORDINATES)) fields.add(field("📍 Coordinates", "`" + alert.x() + " " + alert.y() + " " + alert.z() + "`", true));
        // A code block: one click or long-press selects just the command, ready to paste into chat.
        if (parts.contains(Part.TPA)) fields.add(field("⚡ Join the grief", "```\n/tpa " + alert.player() + "\n```", false));
        embed.add("fields", fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", !parts.contains(Part.SERVER) || alert.server().isBlank() ? "Comet grief alert" : "Comet grief alert · " + alert.server());
        embed.add("footer", footer);
        embed.addProperty("timestamp", time.toString());
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        return payload;
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
