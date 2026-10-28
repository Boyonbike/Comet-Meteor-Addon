package dev.tntcoating.core;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoatWebhookTest {
    @Test void reportsExactEgapsAndBoatBrandingWithoutElytra() {
        var payload = BoatWebhook.payload(HighwayWebhook.Event.STATUS, "Boat status", null,
            List.of(), 129, Instant.EPOCH, "");
        String json = payload.toString();
        assertTrue(json.contains("Comet Auto Boat Fly"));
        assertTrue(json.contains("eGaps remaining"));
        assertTrue(json.contains("129"));
        assertFalse(json.toLowerCase().contains("elytra"));
        assertFalse(json.contains("Highway Fly"));
    }
    @Test void emptySuppliesReportZeroAndMentionsStayRestricted() {
        var payload = BoatWebhook.payload(HighwayWebhook.Event.STARTED, "Started", null,
            List.of(), 0, Instant.EPOCH, "@everyone");
        var embed = payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("0", embed.getAsJsonArray("fields").get(0).getAsJsonObject().get("value").getAsString());
        assertFalse(payload.has("content"));
    }
}
