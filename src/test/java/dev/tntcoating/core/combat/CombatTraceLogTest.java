/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CombatTraceLogTest {
    @TempDir
    Path temp;

    private static JsonObject line(int seq) {
        JsonObject json = new JsonObject();
        json.addProperty("v", 1);
        json.addProperty("type", "event");
        json.addProperty("seq", seq);
        return json;
    }

    private static void awaitFinished(CombatTraceLog log) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!log.isFinished() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(log.isFinished(), "writer must finish after close");
    }

    @Test
    void writesJsonLinesIntoTheCombatTracesDirectoryAndFinishesOnClose() throws Exception {
        CombatTraceLog log = CombatTraceLog.open(temp.resolve("comet").resolve("combat-traces"), 1_000_000);

        assertTrue(log.append(line(1)));
        assertTrue(log.append(line(2)));
        log.close();
        awaitFinished(log);

        assertEquals(temp.resolve("comet").resolve("combat-traces"), log.path().getParent());
        assertTrue(log.path().getFileName().toString().matches("\\d{8}-\\d{6}-\\d{3}(-\\d+)?\\.jsonl"),
            "file name must be the timestamp: " + log.path().getFileName());
        List<String> lines = Files.readAllLines(log.path());
        assertEquals(3, lines.size(), "two records plus the summary label");
        assertEquals(1, JsonParser.parseString(lines.get(0)).getAsJsonObject().get("seq").getAsLong());
        assertEquals(2, JsonParser.parseString(lines.get(1)).getAsJsonObject().get("seq").getAsLong());
        JsonObject summary = JsonParser.parseString(lines.get(2)).getAsJsonObject();
        assertEquals("label", summary.get("type").getAsString());
        assertTrue(summary.get("label").getAsString().contains("written=2"));
        assertNull(log.failure());
    }

    @Test
    void concurrentOpensNeverOverwriteAnExistingTrace() throws Exception {
        CombatTraceLog first = CombatTraceLog.open(temp, 100_000);
        CombatTraceLog second = CombatTraceLog.open(temp, 100_000);

        assertNotEquals(first.path(), second.path());
        first.close();
        second.close();
        awaitFinished(first);
        awaitFinished(second);
    }

    @Test
    void byteLimitStopsAcceptingAndTheSummaryReportsDrops() throws Exception {
        CombatTraceLog log = CombatTraceLog.open(temp, 420);

        int accepted = 0;
        for (int i = 0; i < 100 && log.isAccepting(); i++) {
            if (log.append(line(i))) accepted++;
        }
        assertFalse(log.isAccepting(), "the byte limit must stop accepting records");
        assertTrue(log.droppedRecords() > 0);
        log.close();
        awaitFinished(log);

        List<String> lines = Files.readAllLines(log.path());
        JsonObject summary = JsonParser.parseString(lines.getLast()).getAsJsonObject();
        assertTrue(summary.get("label").getAsString().contains("written=" + accepted));
        assertTrue(summary.get("label").getAsString().contains("dropped="));
    }
}
