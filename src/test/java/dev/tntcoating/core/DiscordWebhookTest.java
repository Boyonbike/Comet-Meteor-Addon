package dev.tntcoating.core;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GriefWebhookTest {
    private static final GriefWebhook.Alert ALERT = new GriefWebhook.Alert("Big_Dub_", "998877665544332211", 1234, 64, -5678, "Overworld", 25, "6b6t.org");
    private static final Instant TIME = Instant.parse("2026-10-21T18:30:00Z");
    private static final Set<GriefWebhook.Part> ALL = EnumSet.allOf(GriefWebhook.Part.class);

    @Test void onlyDiscordWebhookUrlsAreAccepted() {
        assertTrue(GriefWebhook.isWebhookUrl("https://discord.com/api/webhooks/123456789012345678/abc-DEF_123"));
        assertTrue(GriefWebhook.isWebhookUrl(" https://canary.discord.com/api/v10/webhooks/1/token/ "));
        assertTrue(GriefWebhook.isWebhookUrl("https://discordapp.com/api/webhooks/1/token"));
        assertFalse(GriefWebhook.isWebhookUrl(""));
        assertFalse(GriefWebhook.isWebhookUrl("http://discord.com/api/webhooks/1/token"));
        assertFalse(GriefWebhook.isWebhookUrl("https://discord.com.evil.example/api/webhooks/1/token"));
        assertFalse(GriefWebhook.isWebhookUrl("https://discord.com/api/webhooks/1"));
    }

    @Test void pingAcceptsEveryoneHereOrARoleAndBlankMeansNoPing() {
        assertEquals("@everyone", GriefWebhook.mention("everyone"));
        assertEquals("@everyone", GriefWebhook.mention(" @Everyone "));
        assertEquals("@here", GriefWebhook.mention("here"));
        assertEquals("<@&112233445566778899>", GriefWebhook.mention(" 112233445566778899 "));
        assertEquals("<@&112233445566778899>", GriefWebhook.mention("<@&112233445566778899>"));
        assertEquals("", GriefWebhook.mention("  "));
        assertNull(GriefWebhook.mention("@Grief"));
        assertNull(GriefWebhook.mention("<@112233445566778899>"));
    }

    @Test void everyoneAndHereAreAllowedToPing() {
        for (String mention : new String[] {"@everyone", "@here"}) {
            JsonObject payload = GriefWebhook.payload(ALERT, ALL, mention, "", TIME);
            assertTrue(payload.get("content").getAsString().startsWith(mention + " "));
            JsonObject mentions = payload.getAsJsonObject("allowed_mentions");
            assertEquals("everyone", mentions.getAsJsonArray("parse").get(0).getAsString());
            assertTrue(mentions.getAsJsonArray("roles").isEmpty());
        }
    }

    @Test void dimensionsGetReadableNames() {
        assertEquals("Overworld", GriefWebhook.dimensionName("minecraft:overworld"));
        assertEquals("Nether", GriefWebhook.dimensionName("minecraft:the_nether"));
        assertEquals("The End", GriefWebhook.dimensionName("minecraft:the_end"));
        assertEquals("custom:mine", GriefWebhook.dimensionName("custom:mine"));
    }

    @Test void pingsOnlyTheConfiguredRoleFromTheMessageText() {
        JsonObject payload = GriefWebhook.payload(ALERT, ALL, "<@&112233445566778899>", "Come @everyone", TIME);
        assertTrue(payload.get("content").getAsString().startsWith("<@&112233445566778899> "));
        JsonObject mentions = payload.getAsJsonObject("allowed_mentions");
        assertTrue(mentions.getAsJsonArray("parse").isEmpty(), "@everyone in the call to action must not ping.");
        assertEquals("112233445566778899", mentions.getAsJsonArray("roles").get(0).getAsString());
    }

    @Test void blankPingPostsWithoutAPing() {
        JsonObject payload = GriefWebhook.payload(ALERT, ALL, "", "", TIME);
        assertTrue(payload.get("content").getAsString().startsWith("🔥"));
        assertTrue(payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty());
        assertTrue(payload.getAsJsonObject("allowed_mentions").getAsJsonArray("roles").isEmpty());
        assertFalse(embed(payload).has("description"));
    }

    @Test void embedCarriesNameCoordinatesDubsAndACopyableTpa() {
        JsonObject embed = embed(GriefWebhook.payload(ALERT, ALL, "", "TPA in!", TIME));
        assertEquals("TPA in!", embed.get("description").getAsString());
        assertEquals(GriefWebhook.COLOR, embed.get("color").getAsInt());
        assertEquals("2026-10-21T18:30:00Z", embed.get("timestamp").getAsString());
        assertEquals("Comet grief alert · 6b6t.org", embed.getAsJsonObject("footer").get("text").getAsString());
        assertEquals("https://mc-heads.net/avatar/Big_Dub_/128", embed.getAsJsonObject("thumbnail").get("url").getAsString());
        var fields = embed.getAsJsonArray("fields");
        assertEquals("Big\\_Dub\\_", value(fields, 0), "Underscores are escaped so the name isn't italicised.");
        assertEquals("<@998877665544332211>", value(fields, 1));
        assertEquals("**12.5**", value(fields, 2));
        assertEquals("Overworld", value(fields, 3));
        assertEquals("`1234 64 -5678`", value(fields, 4));
        assertEquals("```\n/tpa Big_Dub_\n```", value(fields, 5), "Code blocks show the name as typed.");
        assertFalse(embed.has("image"), "The screenshot is only referenced once it is attached.");
    }

    @Test void footerOmitsAMissingServer() {
        var singleplayer = new GriefWebhook.Alert("Steve", "998877665544332211", 0, 0, 0, "Overworld", 0, "");
        assertEquals("Comet grief alert", embed(GriefWebhook.payload(singleplayer, ALL, "", "", TIME)).getAsJsonObject("footer").get("text").getAsString());
    }

    @Test void switchedOffPartsAreLeftOut() {
        Set<GriefWebhook.Part> parts = EnumSet.of(GriefWebhook.Part.COORDINATES, GriefWebhook.Part.TPA);
        JsonObject payload = GriefWebhook.payload(ALERT, parts, "@everyone", "TPA in!", TIME);
        assertEquals("@everyone 🔥 **Live grief**, join now!", payload.get("content").getAsString());
        JsonObject embed = embed(payload);
        assertFalse(embed.has("description"));
        assertFalse(embed.has("thumbnail"));
        assertEquals("Comet grief alert", embed.getAsJsonObject("footer").get("text").getAsString());
        var fields = embed.getAsJsonArray("fields");
        assertEquals(3, fields.size());
        assertEquals("<@998877665544332211>", value(fields, 0));
        assertEquals("`1234 64 -5678`", value(fields, 1));
        assertEquals("```\n/tpa Big_Dub_\n```", value(fields, 2));
    }

    @Test void nothingSwitchedOnStillPostsTheTitleAndDiscordAccount() {
        JsonObject embed = embed(GriefWebhook.payload(ALERT, EnumSet.noneOf(GriefWebhook.Part.class), "", "TPA in!", TIME));
        assertEquals("🔥 Live grief in progress", embed.get("title").getAsString());
        var fields = embed.getAsJsonArray("fields");
        assertEquals(1, fields.size(), "The stash bot always needs the Discord account.");
        assertEquals("💬 Discord", fields.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test void userIdAcceptsAnIdOrAMentionAndIsRequired() {
        assertEquals("998877665544332211", GriefWebhook.userId(" 998877665544332211 "));
        assertEquals("998877665544332211", GriefWebhook.userId("<@998877665544332211>"));
        assertEquals("998877665544332211", GriefWebhook.userId("<@!998877665544332211>"));
        assertNull(GriefWebhook.userId(""));
        assertNull(GriefWebhook.userId("boyonabike"));
        assertNull(GriefWebhook.userId("<@&998877665544332211>"), "A role is not a user.");
    }

    @Test void screenshotIsShownAsTheEmbedImageAndDeclaredAsAnAttachment() {
        JsonObject payload = GriefWebhook.payload(ALERT, ALL, "", "", TIME);
        JsonObject withScreenshot = GriefWebhook.withScreenshot(payload);
        assertEquals("attachment://grief.png", embed(withScreenshot).getAsJsonObject("image").get("url").getAsString());
        JsonObject attachment = withScreenshot.getAsJsonArray("attachments").get(0).getAsJsonObject();
        assertEquals(0, attachment.get("id").getAsInt());
        assertEquals("grief.png", attachment.get("filename").getAsString());
        assertFalse(embed(payload).has("image"), "The alert without the screenshot is left as it was, to send if capturing fails.");
    }

    @Test void multipartCarriesThePayloadThenThePng() {
        JsonObject payload = GriefWebhook.payload(ALERT, ALL, "", "", TIME);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF};
        byte[] body = GriefWebhook.multipart("b0undary", payload, png);
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--b0undary\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n{"));
        assertTrue(text.contains("\r\n--b0undary\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"grief.png\"\r\nContent-Type: image/png\r\n\r\n"));
        assertTrue(text.endsWith(new String(png, StandardCharsets.ISO_8859_1) + "\r\n--b0undary--\r\n"));
        String json = new String(body, StandardCharsets.UTF_8);
        assertTrue(json.contains(payload.toString()), "The payload is sent as UTF-8, emoji included.");
    }

    @Test void screenshotsAreScaledDownOnlyWhenWiderThanTheLimitAndByAnEvenDivisor() {
        assertEquals(1, GriefWebhook.downscale(1920, 1080));
        assertEquals(1, GriefWebhook.downscale(2560, 1440));
        assertEquals(2, GriefWebhook.downscale(3840, 2160));
        assertEquals(2, GriefWebhook.downscale(3440, 1440));
        assertEquals(3, GriefWebhook.downscale(5760, 3240));
        assertEquals(1, GriefWebhook.downscale(3841, 2161), "No factor divides an odd size, so it is sent full size.");
    }

    private static JsonObject embed(JsonObject payload) {
        return payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    private static String value(com.google.gson.JsonArray fields, int index) {
        return fields.get(index).getAsJsonObject().get("value").getAsString();
    }
}
