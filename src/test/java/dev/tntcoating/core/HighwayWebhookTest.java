package dev.tntcoating.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighwayWebhookTest {
    @Test
    void buildsAnEmbedWithContextAndEventFields() {
        JsonObject payload = HighwayWebhook.payload(HighwayWebhook.Event.DEATH,
            "You died at 12 64 -8.", new HighwayWebhook.Context("Steve", "mc.example.net", "Overworld", 12, 64, -8),
            List.of(new HighwayWebhook.Field("Cause", "lava", true)), Instant.EPOCH);

        assertEquals("Comet Highway Fly", payload.get("username").getAsString());
        assertEquals(0, payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());

        JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("☠️ You died", embed.get("title").getAsString());
        assertEquals(0xED4245, embed.get("color").getAsInt());
        assertEquals("You died at 12 64 -8.", embed.get("description").getAsString());
        assertEquals("Comet Highway Fly · mc.example.net",
            embed.getAsJsonObject("footer").get("text").getAsString());
        assertEquals(Instant.EPOCH.toString(), embed.get("timestamp").getAsString());
        assertTrue(embed.getAsJsonObject("thumbnail").get("url").getAsString().contains("Steve"));

        JsonArray fields = embed.getAsJsonArray("fields");
        assertEquals(5, fields.size());
        assertEquals("Steve", fields.get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("mc.example.net", fields.get(1).getAsJsonObject().get("value").getAsString());
        assertEquals("Overworld", fields.get(2).getAsJsonObject().get("value").getAsString());
        assertEquals("`12 64 -8`", fields.get(3).getAsJsonObject().get("value").getAsString());
        assertEquals("Cause", fields.get(4).getAsJsonObject().get("name").getAsString());
        assertEquals("lava", fields.get(4).getAsJsonObject().get("value").getAsString());
    }

    @Test
    void reportsTheLavaAvoidanceHandoffWithItsReason() {
        JsonObject payload = HighwayWebhook.payload(HighwayWebhook.Event.LAVA_AVOIDANCE,
            "Lava detected; handing control to Baritone.",
            new HighwayWebhook.Context("Steve", "mc.example.net", "Nether", 12, 64, -8),
            List.of(new HighwayWebhook.Field("Reason", "Lava ahead", false)), Instant.EPOCH);
        JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();

        assertEquals("🔥 Lava avoidance", embed.get("title").getAsString());
        assertEquals("Lava detected; handing control to Baritone.", embed.get("description").getAsString());
        assertEquals("Lava ahead",
            embed.getAsJsonArray("fields").get(4).getAsJsonObject().get("value").getAsString());
    }

    @Test
    void reportsPeriodicStatusWithTheCurrentFlightDetails() {
        JsonObject payload = HighwayWebhook.payload(HighwayWebhook.Event.STATUS,
            "Still flying toward the destination.",
            new HighwayWebhook.Context("Steve", "mc.example.net", "Nether", 120, 64, -80),
            List.of(new HighwayWebhook.Field("Distance remaining", "1,200 blocks", true)), Instant.EPOCH);
        JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();

        assertEquals("📊 Flight status", embed.get("title").getAsString());
        assertEquals("`120 64 -80`",
            embed.getAsJsonArray("fields").get(3).getAsJsonObject().get("value").getAsString());
        assertEquals("1,200 blocks",
            embed.getAsJsonArray("fields").get(4).getAsJsonObject().get("value").getAsString());
    }

    @Test
    void wrapsCodeFieldsInBackticks() {
        JsonObject payload = HighwayWebhook.payload(HighwayWebhook.Event.PORTAL,
            "Found the portal.",
            new HighwayWebhook.Context("Steve", "mc.example.net", "Nether", 1, 64, 2),
            List.of(new HighwayWebhook.Field("Portal", "12 64 -8", true, true)), Instant.EPOCH);
        JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();

        assertEquals("`12 64 -8`",
            embed.getAsJsonArray("fields").get(4).getAsJsonObject().get("value").getAsString());
    }

    @Test
    void escapesMarkdownInDynamicTextAndNeverPings() {
        JsonObject payload = HighwayWebhook.payload(HighwayWebhook.Event.DIMENSION,
            "@everyone **moved**", new HighwayWebhook.Context("Big_Dub_", "server_name", "minecraft:the_nether",
            1, 2, 3), List.of(new HighwayWebhook.Field("Reason", "Your **base** was lost", false)), Instant.EPOCH);

        JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("@everyone \\*\\*moved\\*\\*", embed.get("description").getAsString());
        assertEquals("Big\\_Dub\\_", embed.getAsJsonArray("fields").get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("server\\_name", embed.getAsJsonArray("fields").get(1).getAsJsonObject().get("value").getAsString());
        assertEquals("Your \\*\\*base\\*\\* was lost",
            embed.getAsJsonArray("fields").get(4).getAsJsonObject().get("value").getAsString());
        assertEquals(0, payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
    }

    @Test
    void pingsAConfiguredRoleWithoutLettingTheDescriptionPing() {
        JsonObject payload = HighwayWebhook.payload(HighwayWebhook.Event.NEEDS_HELP, "@everyone please look",
            null, List.of(), Instant.EPOCH, "<@&123456789012345678>");

        assertEquals("<@&123456789012345678>", payload.get("content").getAsString());
        JsonObject mentions = payload.getAsJsonObject("allowed_mentions");
        assertEquals(0, mentions.getAsJsonArray("parse").size());
        assertEquals("123456789012345678", mentions.getAsJsonArray("roles").get(0).getAsString());
        assertFalse(mentions.has("users"));
        JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("@everyone please look", embed.get("description").getAsString());
    }

    @Test
    void mentionsAcceptRawIdsAndRejectArbitraryText() {
        assertEquals(new HighwayWebhook.Mention("123456789012345678", false),
            HighwayWebhook.parseMention("<@123456789012345678>"));
        assertEquals(new HighwayWebhook.Mention("123456789012345678", false),
            HighwayWebhook.parseMention("123456789012345678"));
        assertEquals(new HighwayWebhook.Mention("123456789012345678", true),
            HighwayWebhook.parseMention("<@&123456789012345678>"));
        assertEquals(new HighwayWebhook.Mention("123456789012345678", false),
            HighwayWebhook.parseMention("<@!123456789012345678>"));

        for (String invalid : new String[] {null, "", "@everyone", "<@everyone>", "123", "<@123 456>", "<@123", "123>"}) {
            assertEquals(null, HighwayWebhook.parseMention(invalid));
        }
    }

    @Test
    void leavesOutBlankContextFields() {
        JsonObject payload = HighwayWebhook.payload(HighwayWebhook.Event.STOPPED, "Route stopped.",
            new HighwayWebhook.Context("Steve", "", "", 0, 0, 0), List.of(), Instant.EPOCH);
        JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
        JsonArray fields = embed.getAsJsonArray("fields");

        assertEquals(2, fields.size());
        assertEquals("👤 Player", fields.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("📍 Position", fields.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("Comet Highway Fly", embed.getAsJsonObject("footer").get("text").getAsString());
    }

    @Test
    void omitsFieldsWithoutContext() {
        JsonObject payload = HighwayWebhook.payload(HighwayWebhook.Event.NEEDS_HELP, "Waiting for you.", null, null,
            Instant.EPOCH);
        JsonObject embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();

        assertFalse(embed.has("fields"));
        assertEquals("Comet Highway Fly", embed.getAsJsonObject("footer").get("text").getAsString());
    }

    @Test
    void onlyAcceptsDiscordWebhookUrls() {
        assertTrue(HighwayWebhook.configured("https://discord.com/api/webhooks/123456789012345678/token"));
        assertTrue(HighwayWebhook.configured("https://ptb.discord.com/api/v10/webhooks/123456789012345678/token"));
        assertTrue(HighwayWebhook.configured("  https://discord.com/api/webhooks/123456789012345678/token  "));
        assertFalse(HighwayWebhook.configured(""));
        assertFalse(HighwayWebhook.configured(null));
        assertFalse(HighwayWebhook.configured(" https://example.com/webhook "));
    }
}
