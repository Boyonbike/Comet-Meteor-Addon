package dev.tntcoating.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScaffoldSetbackLogTest {
    private static ScaffoldSetbackLog.Tick tick(long tick, double y, float yaw, boolean ground, boolean tower, int placed) {
        return new ScaffoldSetbackLog.Tick(tick, 0, y, 0, yaw, 80, ground, tower, placed, 0, placed);
    }

    @Test void findsTheMoveAfterTheSetbackTargetAndItsRotationSnap() {
        var log = new ScaffoldSetbackLog();
        log.tick(tick(1, 64, 170, true, false, 0));
        log.tick(tick(2, 64, 170, true, false, 0));
        log.tick(tick(3, 64.5, -170, false, true, 1));
        log.tick(tick(4, 65, -170, false, true, 1));
        var record = new JsonObject();
        log.describe(record, 4, 0, 64, 0, 0);
        assertTrue(record.get("rejectedMoveFound").getAsBoolean());
        assertEquals(2, record.get("targetTicksAgo").getAsLong());
        assertEquals(1, record.get("rejectedTicksAgo").getAsLong());
        assertEquals(10, record.get("rejectedVerticalSpeed").getAsDouble(), 1e-9);
        assertEquals(20, record.get("rejectedYawStep").getAsDouble(), 1e-9);
        assertTrue(record.get("rejectedTowering").getAsBoolean());
        assertEquals(2, record.get("placedLastSecond").getAsInt());
        assertEquals(0, record.get("extraRotationPacketsLastSecond").getAsInt());
        assertEquals(4, record.getAsJsonArray("timeline").size());
        assertEquals(1, record.getAsJsonArray("timeline").get(3).getAsJsonObject().get("dy").getAsDouble(), 1e-9);
    }

    @Test void reportsAnUnknownTargetAndAGhostSupport() {
        var log = new ScaffoldSetbackLog();
        log.tick(tick(1, 64, 0, true, false, 1));
        log.placed(1, 42);
        var record = new JsonObject();
        log.describe(record, 6, 0, 70, 0, 42);
        assertFalse(record.get("rejectedMoveFound").getAsBoolean());
        assertEquals(-1, record.get("targetTicksAgo").getAsLong());
        assertEquals(5, record.get("supportPlacedTicksAgo").getAsLong());
        assertEquals(-1, record.get("ticksSinceLastSetback").getAsLong());
        var next = new JsonObject();
        log.describe(next, 9, 0, 70, 0, 7);
        assertEquals(3, next.get("ticksSinceLastSetback").getAsLong());
        assertEquals(-1, next.get("supportPlacedTicksAgo").getAsLong());
        assertEquals(2, next.get("setback").getAsInt());
    }

    @Test void countsRotationPacketsBeyondTheFirstRotatedPlacementOfEachTick() {
        var log = new ScaffoldSetbackLog();
        log.tick(new ScaffoldSetbackLog.Tick(1, 0, 64, 0, 0, 80, true, false, 8, 0, 8));
        log.tick(new ScaffoldSetbackLog.Tick(2, 0, 64, 0, 0, 80, true, false, 1, 0, 1));
        log.tick(new ScaffoldSetbackLog.Tick(3, 0, 64, 0, 0, 80, true, false, 3, 0, 0));
        var record = new JsonObject();
        log.describe(record, 3, 0, 64, 0, 0);
        assertEquals(7, record.get("extraRotationPacketsLastSecond").getAsInt());
        assertEquals(8, record.getAsJsonArray("timeline").get(0).getAsJsonObject().get("rotated").getAsInt());
    }

    @Test void historyIsBoundedAndCloseStartsOver() {
        var log = new ScaffoldSetbackLog();
        for (int i = 1; i <= 100; i++) log.tick(tick(i, 64, 0, true, false, 1));
        var record = new JsonObject();
        log.describe(record, 100, 0, 64, 0, 0);
        assertEquals(20, record.getAsJsonArray("timeline").size());
        assertEquals(20, record.get("placedLastSecond").getAsInt());
        log.close();
        var fresh = new JsonObject();
        log.describe(fresh, 1, 0, 64, 0, 0);
        assertEquals(1, fresh.get("setback").getAsInt());
        assertEquals(0, fresh.getAsJsonArray("timeline").size());
    }

    @Test void writesOneLinePerSetback(@TempDir Path directory) throws Exception {
        var log = new ScaffoldSetbackLog();
        assertNull(log.path());
        var record = new JsonObject();
        record.addProperty("x", 1);
        log.write(directory, record);
        Path file = log.path();
        assertNotNull(file);
        log.write(directory, record);
        log.close();
        long deadline = System.nanoTime() + 5_000_000_000L;
        List<String> lines = List.of();
        while (System.nanoTime() < deadline) {
            lines = Files.readAllLines(file).stream().filter(line -> line.contains("\"x\":1")).toList();
            if (lines.size() == 2) break;
            Thread.sleep(10);
        }
        assertEquals(2, lines.size());
    }
}
