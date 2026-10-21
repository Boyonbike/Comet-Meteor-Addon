package dev.tntcoating.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscordWebhookTest {
    private static final DiscordWebhook.Alert ALERT = new DiscordWebhook.Alert("Big_Dub_", "998877665544332211", 1234, 64, -5678, "Overworld", 25, "6b6t.org");
    private static final Instant TIME = Instant.parse("2026-10-21T18:30:00Z");
    private static final Set<DiscordWebhook.Part> ALL = EnumSet.allOf(DiscordWebhook.Part.class);
    private static final Set<DiscordWebhook.Part> NONE = EnumSet.noneOf(DiscordWebhook.Part.class);

    @Test void onlyDiscordWebhookUrlsAreAccepted() {
        assertTrue(DiscordWebhook.isWebhookUrl("https://discord.com/api/webhooks/123456789012345678/abc-DEF_123"));
        assertTrue(DiscordWebhook.isWebhookUrl(" https://canary.discord.com/api/v10/webhooks/1/token/ "));
        assertTrue(DiscordWebhook.isWebhookUrl("https://discordapp.com/api/webhooks/1/token"));
        assertFalse(DiscordWebhook.isWebhookUrl(""));
        assertFalse(DiscordWebhook.isWebhookUrl("http://discord.com/api/webhooks/1/token"));
        assertFalse(DiscordWebhook.isWebhookUrl("https://discord.com.evil.example/api/webhooks/1/token"));
        assertFalse(DiscordWebhook.isWebhookUrl("https://discord.com/api/webhooks/1"));
    }

    @Test void pingAcceptsEveryoneHereOrARoleAndBlankMeansNoPing() {
        assertEquals("@everyone", DiscordWebhook.mention("everyone"));
        assertEquals("@everyone", DiscordWebhook.mention(" @Everyone "));
        assertEquals("@here", DiscordWebhook.mention("here"));
        assertEquals("<@&112233445566778899>", DiscordWebhook.mention(" 112233445566778899 "));
        assertEquals("<@&112233445566778899>", DiscordWebhook.mention("<@&112233445566778899>"));
        assertEquals("", DiscordWebhook.mention("  "));
        assertNull(DiscordWebhook.mention("@Grief"));
        assertNull(DiscordWebhook.mention("<@112233445566778899>"));
    }

    @Test void userIdAcceptsAnIdOrAMention() {
        assertEquals("998877665544332211", DiscordWebhook.userId(" 998877665544332211 "));
        assertEquals("998877665544332211", DiscordWebhook.userId("<@998877665544332211>"));
        assertEquals("998877665544332211", DiscordWebhook.userId("<@!998877665544332211>"));
        assertNull(DiscordWebhook.userId(""));
        assertNull(DiscordWebhook.userId("boyonabike"));
        assertNull(DiscordWebhook.userId("<@&998877665544332211>"), "A role is not a user.");
    }

    @Test void dimensionsGetReadableNames() {
        assertEquals("Overworld", DiscordWebhook.dimensionName("minecraft:overworld"));
        assertEquals("Nether", DiscordWebhook.dimensionName("minecraft:the_nether"));
        assertEquals("The End", DiscordWebhook.dimensionName("minecraft:the_end"));
        assertEquals("custom:mine", DiscordWebhook.dimensionName("custom:mine"));
    }

    @Test void callToArmsPingsEveryoneOrHere() {
        for (String mention : new String[] {"@everyone", "@here"}) {
            JsonObject payload = DiscordWebhook.callToArms(ALERT, ALL, mention, "", TIME);
            assertTrue(payload.get("content").getAsString().startsWith(mention + " "));
            JsonObject mentions = payload.getAsJsonObject("allowed_mentions");
            assertEquals("everyone", mentions.getAsJsonArray("parse").get(0).getAsString());
            assertTrue(mentions.getAsJsonArray("roles").isEmpty());
        }
    }

    @Test void callToArmsPingsOnlyTheConfiguredRole() {
        JsonObject payload = DiscordWebhook.callToArms(ALERT, ALL, "<@&112233445566778899>", "Come @everyone", TIME);
        assertTrue(payload.get("content").getAsString().startsWith("<@&112233445566778899> "));
        JsonObject mentions = payload.getAsJsonObject("allowed_mentions");
        assertTrue(mentions.getAsJsonArray("parse").isEmpty(), "@everyone in the call to action must not ping.");
        assertEquals("112233445566778899", mentions.getAsJsonArray("roles").get(0).getAsString());
    }

    @Test void callToArmsWithABlankPingPingsNobody() {
        JsonObject payload = DiscordWebhook.callToArms(ALERT, ALL, "", "", TIME);
        assertTrue(payload.get("content").getAsString().startsWith("🔥"));
        assertTrue(payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty());
        assertTrue(payload.getAsJsonObject("allowed_mentions").getAsJsonArray("roles").isEmpty());
        assertFalse(embed(payload).has("description"));
    }

    @Test void callToArmsCarriesNameCoordinatesDubsAndACopyableTpa() {
        JsonObject embed = embed(DiscordWebhook.callToArms(ALERT, ALL, "", "TPA in!", TIME));
        assertEquals("🔥 Live grief in progress", embed.get("title").getAsString());
        assertEquals("TPA in!", embed.get("description").getAsString());
        assertEquals(DiscordWebhook.CALL_TO_ARMS_COLOR, embed.get("color").getAsInt());
        assertEquals("2026-10-21T18:30:00Z", embed.get("timestamp").getAsString());
        assertEquals("Comet call to arms · 6b6t.org", footer(embed));
        assertEquals("https://mc-heads.net/avatar/Big_Dub_/128", embed.getAsJsonObject("thumbnail").get("url").getAsString());
        JsonArray fields = embed.getAsJsonArray("fields");
        assertEquals("Big\\_Dub\\_", value(fields, 0), "Underscores are escaped so the name isn't italicised.");
        assertEquals("**12.5**", value(fields, 1));
        assertEquals("Overworld", value(fields, 2));
        assertEquals("`1234 64 -5678`", value(fields, 3));
        assertEquals("```\n/tpa Big_Dub_\n```", value(fields, 4), "Code blocks show the name as typed.");
        assertEquals(5, fields.size(), "A call to arms doesn't carry the Discord account.");
        assertFalse(embed.has("image"), "The screenshot is only referenced once it is attached.");
    }

    @Test void callToArmsLeavesOutSwitchedOffParts() {
        JsonObject payload = DiscordWebhook.callToArms(ALERT, EnumSet.of(DiscordWebhook.Part.COORDINATES, DiscordWebhook.Part.TPA), "@everyone", "TPA in!", TIME);
        assertEquals("@everyone 🔥 **Live grief**, join now!", payload.get("content").getAsString());
        JsonObject embed = embed(payload);
        assertFalse(embed.has("description"));
        assertFalse(embed.has("thumbnail"));
        assertEquals("Comet call to arms", footer(embed));
        JsonArray fields = embed.getAsJsonArray("fields");
        assertEquals(2, fields.size());
        assertEquals("`1234 64 -5678`", value(fields, 0));
        assertEquals("```\n/tpa Big_Dub_\n```", value(fields, 1));
        assertFalse(embed(DiscordWebhook.callToArms(ALERT, NONE, "", "TPA in!", TIME)).has("fields"));
    }

    @Test void stashReportPingsNobody() {
        JsonObject payload = DiscordWebhook.stashReport(ALERT, ALL, TIME);
        assertFalse(payload.has("content"), "No @everyone or headline: the report is for the stash bot.");
        JsonObject mentions = payload.getAsJsonObject("allowed_mentions");
        assertTrue(mentions.getAsJsonArray("parse").isEmpty());
        assertTrue(mentions.getAsJsonArray("roles").isEmpty());
    }

    @Test void stashReportCarriesWhatTheStashBotReads() {
        JsonObject embed = embed(DiscordWebhook.stashReport(ALERT, ALL, TIME));
        assertEquals("📦 Stash report", embed.get("title").getAsString());
        assertEquals(DiscordWebhook.STASH_REPORT_COLOR, embed.get("color").getAsInt());
        assertEquals("Comet stash report · 6b6t.org", footer(embed));
        assertFalse(embed.has("description"));
        JsonArray fields = embed.getAsJsonArray("fields");
        // The stash bot finds each field by a word in its name.
        assertEquals("Big\\_Dub\\_", valueNamed(fields, "Player"));
        assertEquals("<@998877665544332211>", valueNamed(fields, "Discord"));
        assertEquals("**12.5**", valueNamed(fields, "Dubs"));
        assertEquals("Overworld", valueNamed(fields, "Dimension"));
        assertEquals("`1234 64 -5678`", valueNamed(fields, "Coordinates"));
        assertEquals(5, fields.size(), "No /tpa or call to action in a stash report.");
    }

    @Test void stashReportAlwaysKeepsTheDiscordAccountAndDubs() {
        JsonObject embed = embed(DiscordWebhook.stashReport(ALERT, NONE, TIME));
        JsonArray fields = embed.getAsJsonArray("fields");
        assertEquals(2, fields.size());
        assertEquals("<@998877665544332211>", valueNamed(fields, "Discord"));
        assertEquals("**12.5**", valueNamed(fields, "Dubs"));
        assertEquals("Comet stash report", footer(embed));
        assertFalse(embed.has("thumbnail"));
    }

    @Test void onlyTheStashReportFooterIsTheOneTheStashBotLogs() {
        assertTrue(footer(embed(DiscordWebhook.stashReport(ALERT, ALL, TIME))).startsWith(DiscordWebhook.STASH_REPORT_FOOTER));
        assertFalse(footer(embed(DiscordWebhook.callToArms(ALERT, ALL, "@everyone", "", TIME))).startsWith(DiscordWebhook.STASH_REPORT_FOOTER),
            "A call to arms through the same webhook must not be logged as a stash.");
    }

    @Test void screenshotIsShownAsTheEmbedImageAndDeclaredAsAnAttachment() {
        JsonObject payload = DiscordWebhook.stashReport(ALERT, ALL, TIME);
        JsonObject withScreenshot = DiscordWebhook.withScreenshot(payload);
        assertEquals("attachment://screenshot.png", embed(withScreenshot).getAsJsonObject("image").get("url").getAsString());
        JsonObject attachment = withScreenshot.getAsJsonArray("attachments").get(0).getAsJsonObject();
        assertEquals(0, attachment.get("id").getAsInt());
        assertEquals("screenshot.png", attachment.get("filename").getAsString());
        assertFalse(embed(payload).has("image"), "The message without the screenshot is left as it was, to send if capturing fails.");
    }

    @Test void multipartCarriesThePayloadThenThePng() {
        JsonObject payload = DiscordWebhook.callToArms(ALERT, ALL, "", "", TIME);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF};
        byte[] body = DiscordWebhook.multipart("b0undary", payload, png);
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--b0undary\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n{"));
        assertTrue(text.contains("\r\n--b0undary\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"screenshot.png\"\r\nContent-Type: image/png\r\n\r\n"));
        assertTrue(text.endsWith(new String(png, StandardCharsets.ISO_8859_1) + "\r\n--b0undary--\r\n"));
        assertTrue(new String(body, StandardCharsets.UTF_8).contains(payload.toString()), "The payload is sent as UTF-8, emoji included.");
    }

    @Test void screenshotsAreScaledDownOnlyWhenWiderThanTheLimitAndByAnEvenDivisor() {
        assertEquals(1, DiscordWebhook.downscale(1920, 1080));
        assertEquals(1, DiscordWebhook.downscale(2560, 1440));
        assertEquals(2, DiscordWebhook.downscale(3840, 2160));
        assertEquals(2, DiscordWebhook.downscale(3440, 1440));
        assertEquals(3, DiscordWebhook.downscale(5760, 3240));
        assertEquals(1, DiscordWebhook.downscale(3841, 2161), "No factor divides an odd size, so it is sent full size.");
    }

    private static JsonObject embed(JsonObject payload) {
        return payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    private static String footer(JsonObject embed) {
        return embed.getAsJsonObject("footer").get("text").getAsString();
    }

    private static String value(JsonArray fields, int index) {
        return fields.get(index).getAsJsonObject().get("value").getAsString();
    }

    private static String valueNamed(JsonArray fields, String word) {
        for (var field : fields) {
            if (field.getAsJsonObject().get("name").getAsString().contains(word)) return field.getAsJsonObject().get("value").getAsString();
        }
        return fail("No field named " + word);
    }
}
