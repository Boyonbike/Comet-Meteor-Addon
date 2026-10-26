package dev.tntcoating.core.dashboard;

import dev.tntcoating.core.SetHomeScheduler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SetHomeTelemetryTest {
    @Test void historyAndCountdownsRemainInIdleSnapshotsWithoutChangingSchemaTwoFields() {
        var scheduler = new SetHomeScheduler(() -> 1_000_000L, SetHomeScheduler.State.empty());
        scheduler.sent("Test", new SetHomeScheduler.Location(12, 70, -34, "minecraft:the_nether"), false, 10_000);
        scheduler.message("Successfully moved Home: Test", 60_000);
        var status = scheduler.snapshot(false, 60_000, 10_000);
        var home = new HighwayTelemetry.Home("Auto detect", "Test", status.waiting(), status.waitMillis(),
            60_000, false, "", false, status, true, "test-account", "example.org", scheduler.state().history());
        var idle = HighwayTelemetry.idle(home);
        assertFalse(idle.active());
        assertEquals(1, idle.home().history().size());
        var json = com.google.gson.JsonParser.parseString(DashboardJson.toJson(idle)).getAsJsonObject();
        var h = json.getAsJsonObject("home");
        assertEquals(60_000, h.get("intervalMillis").getAsLong());
        assertEquals(60_000, h.get("waitMillis").getAsLong());
        assertTrue(h.get("paused").getAsBoolean());
        assertEquals("INTERVAL", h.getAsJsonObject("schedule").get("phase").getAsString());
        assertEquals("Test", h.getAsJsonArray("history").get(0).getAsJsonObject().get("home").getAsString());
    }
    @Test void idleSnapshotsKeepLiveEquipmentAndUsableCount() {
        var idle = HighwayTelemetry.idle(null, new HighwayTelemetry.Elytra(true, 300, 10, 4));
        assertFalse(idle.active());
        var json = com.google.gson.JsonParser.parseString(DashboardJson.toJson(idle)).getAsJsonObject();
        var equipment = json.getAsJsonObject("elytra");
        assertEquals(4, equipment.get("usableCount").getAsInt());
        assertEquals(10, equipment.get("minDurability").getAsInt());
        assertEquals(300, equipment.get("remaining").getAsInt());
    }
}
