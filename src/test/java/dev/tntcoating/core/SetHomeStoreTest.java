package dev.tntcoating.core;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SetHomeStoreTest {
    @TempDir Path directory;
    @Test void survivesRestartAndSeparatesAccountsAndServers() throws Exception {
        Path file = directory.resolve("state.json");
        var store = new SetHomeStore(file);
        var s = new SetHomeScheduler(() -> 1_000_000L, SetHomeScheduler.State.empty());
        s.sent("Test", new SetHomeScheduler.Location(1, 2, 3, "minecraft:the_nether"), false, 10_000);
        s.message("Successfully moved Home: Test", 60_000);
        store.save("alice", "Example.ORG:25565", s.state());
        var restored = new SetHomeStore(file);
        assertEquals("example.org", restored.latestServer("alice"));
        assertNull(restored.latestServer("unknown"));
        assertEquals(s.state(), restored.load("alice", "example.org"));
        assertEquals(SetHomeScheduler.State.empty(), restored.load("bob", "example.org"));
        assertEquals(SetHomeScheduler.State.empty(), restored.load("alice", "other.org"));
        restored.save("bob", "example.org", SetHomeScheduler.State.empty());
        assertEquals(s.state(), new SetHomeStore(file).load("alice", "example.org"));
    }
    @Test void malformedOrOversizedDataCannotCrashOrInventHistory() throws Exception {
        Path file = directory.resolve("state.json");
        Files.writeString(file, "{bad json");
        assertEquals(SetHomeScheduler.State.empty(), new SetHomeStore(file).load("a", "server"));
        Files.writeString(file, "{\"version\":99,\"records\":{}}");
        assertEquals(SetHomeScheduler.State.empty(), new SetHomeStore(file).load("a", "server"));
        Files.writeString(file, "x".repeat(4_194_305));
        assertEquals(SetHomeScheduler.State.empty(), new SetHomeStore(file).load("a", "server"));
    }
    @Test void upgradesLegacyEstimatesWithoutLosingHistoryOrReportedWaits() throws Exception {
        Path file = directory.resolve("legacy.json");
        var scheduler = new SetHomeScheduler(() -> 1_000_000L, SetHomeScheduler.State.empty());
        scheduler.sent("Test", new SetHomeScheduler.Location(1, 2, 3, "minecraft:the_nether"), false, 10_000);
        scheduler.message("Successfully moved Home: Test", 60_000);
        var history = scheduler.state().history();
        var estimate = new SetHomeScheduler.State(1_000_000, 1_000_000, 1_000_000,
            43_200_000, 44_200_000, 0, history);
        var reported = new SetHomeScheduler.State(1_060_000, 1_000_000, 1_000_000,
            43_200_000, 1_354_000, 0, history);
        var document = java.util.Map.of("version", 1, "records", java.util.Map.of(
            "alice|server", estimate, "bob|server", reported));
        Files.writeString(file, new com.google.gson.Gson().toJson(document));
        var store = new SetHomeStore(file);
        assertEquals(0, store.load("alice", "server").learnedMillis());
        assertEquals(0, store.load("alice", "server").cooldownEnd());
        assertEquals(history, store.load("alice", "server").history());
        assertEquals(1_354_000, store.load("bob", "server").cooldownEnd());
        assertEquals(0, store.load("bob", "server").learnedMillis());
    }

}
