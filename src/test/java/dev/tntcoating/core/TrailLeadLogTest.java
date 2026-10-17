package dev.tntcoating.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TrailLeadLogTest {
    @TempDir Path directory;

    @Test void appendedLeadsReloadWithTheirHeaderWrittenOnce() throws IOException {
        Path file = directory.resolve("leads/world.csv");
        TrailLeadLog.append(file, new TrailLeadLog.Entry(1_000, "trail-end", -12_345, 6_789, -90, 2_400));
        TrailLeadLog.append(file, new TrailLeadLog.Entry(2_000, "fork", 100, 200, 45, 300));
        var lines = Files.readAllLines(file);
        assertEquals(TrailLeadLog.HEADER, lines.getFirst());
        assertEquals(3, lines.size());
        var log = TrailLeadLog.load(file);
        assertEquals(2, log.entries().size());
        assertEquals(new TrailLeadLog.Entry(1_000, "trail-end", -12_345, 6_789, -90, 2_400), log.entries().getFirst());
    }

    @Test void aLeadNearAnEarlierOneOfTheSameKindIsTheSamePlace() {
        var log = new TrailLeadLog();
        assertTrue(log.add(new TrailLeadLog.Entry(1, "trail-end", 1_000, 1_000, 0, 500)));
        assertFalse(log.add(new TrailLeadLog.Entry(2, "trail-end", 1_150, 1_100, 0, 500)), "within 256 blocks");
        assertTrue(log.add(new TrailLeadLog.Entry(3, "fork", 1_000, 1_000, 0, 500)), "a different kind is separate");
        assertTrue(log.add(new TrailLeadLog.Entry(4, "trail-end", 1_300, 1_000, 0, 500)));
        assertEquals(2, log.count("trail-end"));
    }

    @Test void aMissingFileIsEmptyAndDamagedLinesAreSkipped() throws IOException {
        assertTrue(TrailLeadLog.load(directory.resolve("missing.csv")).entries().isEmpty());
        Path file = directory.resolve("edited.csv");
        Files.writeString(file, TrailLeadLog.HEADER + "\nnot,a,lead\n5,trail-end,1,2,3,4\n6,trail-end,x,2,3,4\n");
        var log = TrailLeadLog.load(file);
        assertEquals(1, log.entries().size());
        assertTrue(log.seen("trail-end", 10, 10));
    }

    @Test void kindsMustBePlainWords() {
        assertThrows(IllegalArgumentException.class, () -> new TrailLeadLog.Entry(0, "a,b", 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TrailLeadLog.Entry(0, " ", 0, 0, 0, 0));
    }
}
