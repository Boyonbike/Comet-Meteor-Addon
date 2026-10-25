package dev.tntcoating.core;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyRecoveryStoreTest {
    @TempDir Path tempDir;

    private Path marker() {
        return tempDir.resolve("auto-lobby-recovery.json");
    }

    private LobbyRecoveryStore store() {
        return new LobbyRecoveryStore(marker());
    }

    @Test void savesAndLoadsServerTrip() throws IOException {
        store().save("minecraft:the_nether", "6b6t.org");

        LobbyRecoveryStore.Recovery recovery = store().load().orElseThrow();
        assertEquals("minecraft:the_nether", recovery.dimension());
        assertEquals("6b6t.org", recovery.server());
        assertTrue(recovery.isForServer("6b6t.org"));
        assertFalse(recovery.isForServer("other.org"));
    }

    @Test void singleplayerTripHasNoServer() throws IOException {
        store().save("minecraft:the_nether", null);

        LobbyRecoveryStore.Recovery recovery = store().load().orElseThrow();
        assertNull(recovery.server());
        assertTrue(recovery.isForServer(null));
        assertFalse(recovery.isForServer("6b6t.org"));
    }

    @Test void serverMatchIsCaseInsensitive() {
        LobbyRecoveryStore.Recovery recovery =
            new LobbyRecoveryStore.Recovery("minecraft:the_nether", "6B6T.ORG");
        assertTrue(recovery.isForServer("6b6t.org"));
    }

    @Test void clearRemovesMarker() throws IOException {
        store().save("minecraft:the_nether", "6b6t.org");
        store().clear();

        assertFalse(Files.exists(marker()));
        assertTrue(store().load().isEmpty());
        assertDoesNotThrow(() -> store().clear());
    }

    @Test void missingMarkerLoadsEmpty() {
        assertTrue(store().load().isEmpty());
    }

    @Test void corruptMarkerIsDiscarded() throws IOException {
        Files.writeString(marker(), "{not json");

        assertTrue(store().load().isEmpty());
        assertFalse(Files.exists(marker()));
    }

    @Test void nonStringValuesAreDiscarded() throws IOException {
        Files.writeString(marker(), "{\"dimension\":42}");
        assertTrue(store().load().isEmpty());

        Files.writeString(marker(), "{\"dimension\":\"minecraft:the_nether\",\"server\":42}");
        assertTrue(store().load().isEmpty());
    }

    @Test void saveCreatesParentDirectories() throws IOException {
        Path nested = tempDir.resolve("nested/deep/recovery.json");
        LobbyRecoveryStore nestedStore = new LobbyRecoveryStore(nested);

        nestedStore.save("minecraft:the_nether", "6b6t.org");

        assertTrue(Files.isRegularFile(nested));
        assertEquals("6b6t.org", nestedStore.load().orElseThrow().server());
    }


    @Test void saveReplacesThePreviousTrip() throws IOException {
        store().save("minecraft:the_nether", "6b6t.org");
        store().save("minecraft:the_nether", "other.org");

        assertEquals("other.org", store().load().orElseThrow().server());
        assertFalse(Files.exists(marker().resolveSibling("auto-lobby-recovery.json.tmp")));
    }

    @Test void saveRejectsBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> store().save(" ", null));
        assertThrows(IllegalArgumentException.class,
            () -> store().save("minecraft:the_nether", " "));
    }
    @Test void partialRouteMarkerIsDiscarded() throws IOException {
        Files.writeString(marker(), """
            {"dimension":"minecraft:the_nether","destination":{"x":100,"y":120,"z":200}}
            """);

        assertTrue(store().load().isEmpty());
        assertFalse(Files.exists(marker()));
    }

    @Test void oversizedMarkerIsDiscarded() throws IOException {
        Files.writeString(marker(), "{\"dimension\":\"minecraft:the_nether\",\"padding\":\""
            + "a".repeat(20_000) + "\"}");

        assertTrue(store().load().isEmpty());
        assertFalse(Files.exists(marker()));
    }

    @Test void completeRouteSurvivesNewStoreInstance() throws IOException {
        store().save("minecraft:the_nether", "6b6t.org", new BlockPos(-12_345, 120, 67_890), 124);

        LobbyRecoveryStore.Recovery recovery = store().load().orElseThrow();
        assertEquals(new BlockPos(-12_345, 120, 67_890), recovery.destination());
        assertEquals(124, recovery.targetY());
    }

    @Test void legacyMarkerHasNoRoute() throws IOException {
        Files.writeString(marker(), "{\"dimension\":\"minecraft:the_nether\",\"server\":\"6b6t.org\"}");

        LobbyRecoveryStore.Recovery recovery = store().load().orElseThrow();
        assertNull(recovery.destination());
        assertNull(recovery.targetY());
    }

    @Test void invalidSavesPreservePreviousTrip() throws IOException {
        store().save("minecraft:the_nether", "6b6t.org", new BlockPos(10, 120, 20), 120);
        assertThrows(IllegalArgumentException.class,
            () -> store().save("minecraft:the_nether", "other.org", new BlockPos(1, 120, 2), null));
        assertThrows(IllegalArgumentException.class,
            () -> store().save("minecraft:the_nether", "other.org", null, 120));
        assertThrows(IllegalArgumentException.class,
            () -> store().save("minecraft:the_nether", "other.org", new BlockPos(30_000_001, 120, 2), 120));
        assertThrows(IllegalArgumentException.class,
            () -> store().save("minecraft:the_nether", "other.org", new BlockPos(1, 120, -30_000_001), 120));
        assertThrows(IllegalArgumentException.class,
            () -> store().save("minecraft:the_nether", "other.org", new BlockPos(1, 5000, 2), 120));
        assertThrows(IllegalArgumentException.class,
            () -> store().save("minecraft:the_nether", "other.org", new BlockPos(1, 120, 2), 5000));
        assertEquals(new BlockPos(10, 120, 20), store().load().orElseThrow().destination());
    }

    @Test void malformedRouteValuesAreDiscarded() throws IOException {
        String[] routes = {
            "\"targetY\":120",
            "\"destination\":null,\"targetY\":120",
            "\"destination\":{\"x\":1,\"y\":120},\"targetY\":120",
            "\"destination\":{\"x\":1.5,\"y\":120,\"z\":2},\"targetY\":120",
            "\"destination\":{\"x\":30000001,\"y\":120,\"z\":2},\"targetY\":120",
            "\"destination\":{\"x\":1,\"y\":120,\"z\":2},\"targetY\":\"120\"",
            "\"destination\":{\"x\":1,\"y\":120,\"z\":2},\"targetY\":4294967416",
            "\"destination\":{\"x\":1,\"y\":120,\"z\":2},\"targetY\":5000"
        };
        for (String route : routes) {
            Files.writeString(marker(), "{\"dimension\":\"minecraft:the_nether\"," + route + "}");
            assertTrue(store().load().isEmpty(), route);
            assertFalse(Files.exists(marker()), route);
        }
    }

    @Test void routeReplacementLeavesCompleteTripAndNoTemporaryMarker() throws IOException {
        store().save("minecraft:the_nether", "old.org", new BlockPos(1, 120, 2), 120);
        store().save("minecraft:overworld", "new.org", new BlockPos(30_000_000, 320, -30_000_000), 320);

        LobbyRecoveryStore.Recovery recovery = store().load().orElseThrow();
        assertEquals("minecraft:overworld", recovery.dimension());
        assertEquals("new.org", recovery.server());
        assertEquals(new BlockPos(30_000_000, 320, -30_000_000), recovery.destination());
        assertEquals(320, recovery.targetY());
        assertFalse(Files.exists(marker().resolveSibling("auto-lobby-recovery.json.tmp")));
    }

    @Test void readFailurePreservesMarkerForRetry() throws IOException {
        store().save("minecraft:the_nether", "6b6t.org");
        var permissions = Files.getPosixFilePermissions(marker());
        try {
            Files.setPosixFilePermissions(marker(), java.util.Set.of());
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(marker()));
            assertTrue(store().load().isEmpty());
            assertTrue(Files.exists(marker()));
        } finally {
            Files.setPosixFilePermissions(marker(), permissions);
        }
        assertEquals("6b6t.org", store().load().orElseThrow().server());
    }

}
