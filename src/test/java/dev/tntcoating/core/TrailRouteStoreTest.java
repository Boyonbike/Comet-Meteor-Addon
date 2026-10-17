package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TrailRouteStoreTest {
    @TempDir Path directory;

    @Test void missingFileStartsAnEmptyRoute() throws IOException {
        assertEquals(0, TrailRouteStore.load(directory.resolve("missing.route")).size());
    }

    @Test void roundTripRetainsNegativeCoordinatesAndDirectionIndependentIntersections() throws IOException {
        var memory = new TrailRouteMemory();
        memory.record(-248, -8);
        memory.record(248, -8);
        memory.rememberTrailChunk(Integer.MIN_VALUE, Integer.MAX_VALUE);
        Path file = directory.resolve("server-a/overworld.route");
        TrailRouteStore.save(file, memory);
        var restored = TrailRouteStore.load(file);
        assertEquals(memory.size(), restored.size());
        assertTrue(restored.isVisitedChunk(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertTrue(restored.wouldRetrace(-376, -8, 376, -8));
        assertTrue(restored.wouldRetrace(376, -8, -376, -8));
        assertFalse(restored.wouldRetrace(0, -264, 0, 248), "flight directions survive a restart");
        assertEquals(20 + 12L * memory.size(), Files.size(file));
    }

    @Test void versionOneFilesLoadWithoutDirectionsAndKeepBlockingAlongTheRoute() throws IOException {
        Path file = directory.resolve("legacy.route");
        var bytes = new java.io.ByteArrayOutputStream();
        var crc = new java.util.zip.CRC32();
        try (var out = new java.io.DataOutputStream(new java.util.zip.CheckedOutputStream(bytes, crc))) {
            out.writeInt(0x4354524d);
            out.writeInt(1);
            out.writeInt(2);
            out.writeLong(0L);
            out.writeLong(1L << 32);
            out.writeLong(crc.getValue());
        }
        Files.write(file, bytes.toByteArray());
        var restored = TrailRouteStore.load(file);
        assertEquals(2, restored.size());
        assertTrue(restored.wouldRetrace(-200, 8, 200, 8, 2, 0), "version 1 cells match every direction");
        assertTrue(restored.wouldRetrace(-200, 40, 200, -24, 2, 0));
        TrailRouteStore.save(file, restored);
        var resaved = TrailRouteStore.load(file);
        assertTrue(resaved.wouldRetrace(-200, 40, 200, -24, 2, 0), "and still do after resaving as version 2");
    }

    @Test void restoredFlightStartsWithoutAnInterpolatedReconnectSegment() throws IOException {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        Path file = directory.resolve("overworld.route");
        TrailRouteStore.save(file, memory);
        var restored = TrailRouteStore.load(file);
        restored.record(328, 8);
        assertEquals(2, restored.size());
        assertFalse(restored.isVisitedChunk(10, 0));
    }

    @Test void worldAndDimensionPathsKeepHistoriesSeparate() throws IOException {
        var overworld = new TrailRouteMemory();
        overworld.record(8, 8);
        var nether = new TrailRouteMemory();
        nether.record(104, 104);
        Path overworldFile = directory.resolve("server-a/overworld.route");
        Path netherFile = directory.resolve("server-a/nether.route");
        Path otherServer = directory.resolve("server-b/overworld.route");
        TrailRouteStore.save(overworldFile, overworld);
        TrailRouteStore.save(netherFile, nether);
        assertTrue(TrailRouteStore.load(overworldFile).isVisitedChunk(0, 0));
        assertFalse(TrailRouteStore.load(overworldFile).isVisitedChunk(6, 6));
        assertTrue(TrailRouteStore.load(netherFile).isVisitedChunk(6, 6));
        assertEquals(0, TrailRouteStore.load(otherServer).size());
    }

    @Test void immutableSnapshotCanBeSavedAfterLiveMemoryChanges() throws IOException {
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        var snapshot = memory.snapshot();
        memory.record(328, 8);
        Path file = directory.resolve("snapshot.route");
        TrailRouteStore.save(file, snapshot);
        assertEquals(1, TrailRouteStore.load(file).size());
    }

    @Test void damagedHistoryThrowsWithoutChangingTheExistingFile() throws IOException {
        var memory = new TrailRouteMemory();
        memory.record(-8, 8);
        Path file = directory.resolve("damaged.route");
        TrailRouteStore.save(file, memory);
        byte[] bytes = Files.readAllBytes(file);
        bytes[12] ^= 0x40;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> TrailRouteStore.load(file));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    @Test void truncatedHeaderAndPayloadAreRejected() throws IOException {
        Path file = directory.resolve("truncated.route");
        Files.write(file, new byte[] {0, 1, 2});
        assertThrows(IOException.class, () -> TrailRouteStore.load(file));
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        TrailRouteStore.save(file, memory);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(IOException.class, () -> TrailRouteStore.load(file));
    }

    @Test void unknownVersionAndImplausibleCountsAreRejected() throws IOException {
        Path file = directory.resolve("invalid.route");
        TrailRouteStore.save(file, new TrailRouteMemory());
        byte[] bytes = Files.readAllBytes(file);
        bytes[7] = 3;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> TrailRouteStore.load(file));
        TrailRouteStore.save(file, new TrailRouteMemory());
        bytes = Files.readAllBytes(file);
        bytes[8] = 0x7f;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> TrailRouteStore.load(file));
    }

    @Test void atomicReplacementLeavesOnlyTheCurrentRouteFile() throws IOException {
        Path file = directory.resolve("replace.route");
        var memory = new TrailRouteMemory();
        memory.record(8, 8);
        TrailRouteStore.save(file, memory);
        memory.record(104, 8);
        TrailRouteStore.save(file, memory);
        assertEquals(memory.size(), TrailRouteStore.load(file).size());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void anUnreadableExistingPathIsNotTreatedAsEmptyHistory() throws IOException {
        Path file = Files.createDirectory(directory.resolve("directory.route"));
        assertThrows(IOException.class, () -> TrailRouteStore.load(file));
    }
}
