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
}
