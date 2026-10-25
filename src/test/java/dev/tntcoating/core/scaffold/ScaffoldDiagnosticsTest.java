package dev.tntcoating.core.scaffold;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ScaffoldDiagnosticsTest {
    @TempDir Path directory;

    @Test void offDoesNotCreateFiles() throws Exception {
        var diagnostics = new ScaffoldDiagnostics(directory, () -> 0L);
        diagnostics.start(ScaffoldDiagnostics.Mode.Off, new JsonObject());
        diagnostics.event("correction", new JsonObject());
        diagnostics.close(new JsonObject());
        assertEquals(0, Files.list(directory).count());
    }

    @Test void summaryRecordsSuccessfulSessionsAndPreCorrectionHistory() throws Exception {
        var clock = new AtomicLong();
        var diagnostics = new ScaffoldDiagnostics(directory, clock::get);
        var metadata = new JsonObject(); metadata.addProperty("preset", "Wide");
        diagnostics.start(ScaffoldDiagnostics.Mode.Summary, metadata);
        diagnostics.sample("movement", new JsonObject());
        clock.set(5_000_000_000L);
        var stats = new JsonObject(); stats.addProperty("confirmed", 10);
        diagnostics.summary(stats);
        diagnostics.sample("movement", new JsonObject());
        diagnostics.event("correction", new JsonObject());
        diagnostics.close(stats);
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!diagnostics.finished() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(diagnostics.finished(), "writer drains asynchronously");
        var events = Files.readAllLines(diagnostics.path()).stream()
            .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        assertEquals("session_start", events.getFirst().get("event").getAsString());
        assertTrue(events.stream().anyMatch(e -> e.get("event").getAsString().equals("session_summary") && e.get("confirmed").getAsInt() == 10));
        var correction = events.stream().filter(e -> e.get("event").getAsString().equals("correction")).findFirst().orElseThrow();
        assertEquals(1, correction.getAsJsonArray("history").size(), "expired history is excluded");
        assertTrue(events.stream().anyMatch(e -> e.get("event").getAsString().equals("session_end")));
    }

    @Test void ringHasHardBoundsAndCopiesCallerData() throws Exception {
        var diagnostics = new ScaffoldDiagnostics(directory, () -> 0L);
        diagnostics.start(ScaffoldDiagnostics.Mode.Summary, new JsonObject());
        var sample = new JsonObject(); sample.addProperty("x", 1);
        for (int i = 0; i < 2_000; i++) diagnostics.sample("packet", sample);
        sample.addProperty("x", 99);
        assertTrue(diagnostics.bufferedSamples() <= 1_536);
        diagnostics.event("correction", new JsonObject());
        diagnostics.close(new JsonObject());
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!diagnostics.finished() && System.nanoTime() < deadline) Thread.onSpinWait();
        var correction = Files.readAllLines(diagnostics.path()).stream().map(JsonParser::parseString)
            .map(e -> e.getAsJsonObject()).filter(e -> e.get("event").getAsString().equals("correction")).findFirst().orElseThrow();
        assertEquals(1, correction.getAsJsonArray("history").get(0).getAsJsonObject().get("x").getAsInt());
    }
}
