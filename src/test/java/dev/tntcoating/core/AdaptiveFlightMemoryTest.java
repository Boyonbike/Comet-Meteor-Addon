package dev.tntcoating.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static dev.tntcoating.core.AdaptiveFlightMemory.*;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFlightMemoryTest {
    @TempDir Path directory;

    @Test void unknownServersDoNotInheritAnotherServersLimitsOrConstrainUnlearnedAxes() {
        var memory = new AdaptiveFlightMemory();
        var onlyAscentLearned = new Limits(64, .72, 64, 2);
        memory.put("example.org:25565/overworld", onlyAscentLearned);

        assertEquals(onlyAscentLearned, memory.get("example.org:25565/overworld"));
        assertEquals(Limits.UNKNOWN, memory.get("other.example:25565/overworld"));
        assertEquals(Limits.UNKNOWN, memory.get("example.org:25566/overworld"));
        assertEquals(Limits.UNKNOWN, memory.get("example.org:25565/nether"));
        assertEquals(1, memory.size(), "a lookup does not create a learned profile");
    }

    @Test void roundTripRestoresIndependentHorizontalUpDownAndAccelerationCeilings() throws IOException {
        var memory = new AdaptiveFlightMemory();
        var first = new Limits(6.4, .8, 1.3, .035);
        var second = new Limits(2, 1.6, .65, .08);
        memory.put("server-a:25565", first);
        memory.put("server-b:25565", second);
        Path path = directory.resolve("nested/flight.memory");

        memory.save(path);
        var restored = AdaptiveFlightMemory.load(path);
        assertEquals(2, restored.size());
        assertEquals(first, restored.get("server-a:25565"));
        assertEquals(second, restored.get("server-b:25565"));
        assertEquals(Limits.UNKNOWN, restored.get("new-server:25565"));
    }

    @Test void cleanWarmStartRetainsUnknownRejectionCeilingsAcrossReload() throws IOException {
        var memory = new AdaptiveFlightMemory();
        var warmStart = new Limits(64, .75, .9, .05);
        var profile = new ProfileLimits(warmStart, Limits.UNKNOWN);
        memory.putProfile("tested-server", profile);
        Path file = directory.resolve("clean-hints.memory");
        memory.save(file);

        var restored = AdaptiveFlightMemory.load(file);
        assertEquals(profile, restored.getProfile("tested-server"));
        assertEquals(warmStart, restored.get("tested-server"));
        assertEquals(64, restored.getProfile("tested-server").ceilings().ascent(),
            "a .75 clean reconnect hint must still allow subsequent upward exploration");
        assertEquals(ProfileLimits.UNKNOWN, restored.getProfile("new-server"));
        assertEquals(2, java.nio.ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4));
    }

    @Test void knownRejectionCeilingsRemainDistinctFromLowerProvenHints() throws IOException {
        var memory = new AdaptiveFlightMemory();
        var proven = new Limits(4.5, .28, .7, .025);
        var rejected = new Limits(5, .3, .8, .03);
        var profile = new ProfileLimits(proven, rejected);
        memory.putProfile("limited-server", profile);
        Path file = directory.resolve("rejected-ceilings.memory");
        memory.save(file);

        assertEquals(profile, AdaptiveFlightMemory.load(file).getProfile("limited-server"));
        memory.put("legacy-call", rejected);
        assertEquals(new ProfileLimits(rejected, rejected), memory.getProfile("legacy-call"));
    }

    @Test void versionOneLimitsMigrateConservativelyAndResaveAsVersionTwo() throws IOException {
        Path file = directory.resolve("legacy.memory");
        Files.write(file, encoded(1, 1, "legacy-server", 5, .04));
        var migrated = AdaptiveFlightMemory.load(file);
        var old = new Limits(5, .8, 1.4, .04);
        assertEquals(new ProfileLimits(old, old), migrated.getProfile("legacy-server"));
        migrated.save(file);

        assertEquals(2, java.nio.ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4));
        assertEquals(new ProfileLimits(old, old), AdaptiveFlightMemory.load(file).getProfile("legacy-server"));
    }

    @Test void warmStartMayExceedIndependentCorrectionGuidance() {
        var guidance = new Limits(4, .3, .8, .03);
        for (Limits demonstrated : new Limits[] {
            new Limits(4.1, .28, .7, .025), new Limits(4, .31, .7, .025),
            new Limits(4, .28, .81, .025), new Limits(4, .28, .7, .031)
        }) assertDoesNotThrow(() -> new ProfileLimits(demonstrated, guidance));
        assertThrows(NullPointerException.class, () -> new ProfileLimits(null, Limits.UNKNOWN));
        assertThrows(NullPointerException.class, () -> new ProfileLimits(Limits.UNKNOWN, null));
    }

    @Test void updatingOrClearingOneServerLeavesOtherServerLimitsAlone() {
        var memory = new AdaptiveFlightMemory();
        var first = new Limits(7, .7, 1, .05);
        var second = new Limits(5, .5, 1.5, .03);
        memory.put("a", first);
        memory.put("b", second);
        memory.put("a", new Limits(6, .6, 1.2, .04));
        assertEquals(second, memory.get("b"));
        memory.clear("a");
        assertEquals(Limits.UNKNOWN, memory.get("a"));
        assertEquals(second, memory.get("b"));
        assertEquals(1, memory.size());
    }

    @Test void invalidLimitsCannotEnterLiveMemory() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, 0, -.1, 64.01}) {
            assertThrows(IllegalArgumentException.class, () -> new Limits(invalid, 1, 1, .1));
            assertThrows(IllegalArgumentException.class, () -> new Limits(1, invalid, 1, .1));
            assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, invalid, .1));
        }
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, -.1, 2.01})
            assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, invalid));
        assertDoesNotThrow(() -> new Limits(64, 64, 64, 2));
    }

    @Test void unusableIdentitiesCannotEnterLiveMemory() {
        var memory = new AdaptiveFlightMemory();
        for (String invalid : new String[] {"", "  ", "server\nother", "x".repeat(1_025)}) {
            assertThrows(IllegalArgumentException.class, () -> memory.put(invalid, Limits.UNKNOWN));
            assertThrows(IllegalArgumentException.class, () -> memory.get(invalid));
        }
        assertThrows(NullPointerException.class, () -> memory.put("server", null));
        assertEquals(0, memory.size());
    }

    @Test void leastRecentlyUsedServerIsEvictedAtTheBoundAndOrderSurvivesReload() throws IOException {
        var memory = new AdaptiveFlightMemory();
        var limits = new Limits(5, .75, 1.25, .06);
        for (int i = 0; i < MAX_SERVER_PROFILES; i++) memory.put("server-" + i, limits);
        assertEquals(limits, memory.get("server-0"));
        Path path = directory.resolve("bounded.memory");
        memory.save(path);
        var restored = AdaptiveFlightMemory.load(path);
        restored.put("new-server", limits);

        assertEquals(MAX_SERVER_PROFILES, restored.size());
        assertEquals(Limits.UNKNOWN, restored.get("server-1"));
        assertEquals(limits, restored.get("server-0"));
        assertEquals(limits, restored.get("new-server"));
    }

    @Test void staleProfilesExpireWithoutBeingKeptAliveByLookupsOrReload() throws IOException {
        var clock = new MutableClock();
        var memory = new AdaptiveFlightMemory(clock);
        var limits = new Limits(4, .6, 1.2, .04);
        memory.put("old-server", limits);
        clock.advance(MAX_AGE_MILLIS - 1);
        assertEquals(limits, memory.get("old-server"));
        memory.put("recent-server", limits);
        Path path = directory.resolve("expiry.memory");
        memory.save(path);
        clock.advance(1);

        assertEquals(Limits.UNKNOWN, memory.get("old-server"));
        var restored = AdaptiveFlightMemory.load(path, clock);
        assertEquals(1, restored.size());
        assertEquals(Limits.UNKNOWN, restored.get("old-server"));
        assertEquals(limits, restored.get("recent-server"));
    }

    @Test void relearningRefreshesOnlyThatServersExpiry() {
        var clock = new MutableClock();
        var memory = new AdaptiveFlightMemory(clock);
        var limits = new Limits(4, .6, 1.2, .04);
        memory.put("updated", limits);
        memory.put("untouched", limits);
        clock.advance(MAX_AGE_MILLIS - 1);
        memory.put("updated", new Limits(3.5, .6, 1.2, .04));
        clock.advance(1);

        assertEquals(Limits.UNKNOWN, memory.get("untouched"));
        assertEquals(3.5, memory.get("updated").horizontal());
    }

    @Test void missingUnreadableTruncatedAndDamagedFilesStartFreshWithoutChangingTheFile() throws IOException {
        assertEquals(0, AdaptiveFlightMemory.load(directory.resolve("missing.memory")).size());
        assertEquals(0, AdaptiveFlightMemory.load(directory).size());
        var memory = new AdaptiveFlightMemory();
        memory.put("server", new Limits(5, .8, 1.4, .04));
        Path file = directory.resolve("damaged.memory");
        memory.save(file);
        byte[] original = Files.readAllBytes(file);
        for (int length : new int[] {0, 3, 19, original.length - 1}) {
            byte[] truncated = Arrays.copyOf(original, length);
            Files.write(file, truncated);
            assertEquals(0, AdaptiveFlightMemory.load(file).size());
            assertArrayEquals(truncated, Files.readAllBytes(file));
        }
        byte[] damaged = original.clone();
        damaged[damaged.length - 10] ^= 0x40;
        Files.write(file, damaged);
        assertEquals(0, AdaptiveFlightMemory.load(file).size());
        assertArrayEquals(damaged, Files.readAllBytes(file));
    }

    @Test void validChecksumsCannotAdmitUnknownSchemasInvalidCountsOrInvalidLimits() throws IOException {
        Path file = directory.resolve("invalid.memory");
        Files.write(file, encoded(3, 0, null, 1, 1));
        assertEquals(0, AdaptiveFlightMemory.load(file).size());
        Files.write(file, encoded(2, MAX_SERVER_PROFILES + 1, null, 1, 1));
        assertEquals(0, AdaptiveFlightMemory.load(file).size());
        Files.write(file, encoded(2, -1, null, 1, 1));
        assertEquals(0, AdaptiveFlightMemory.load(file).size());
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -.3, 0, 65}) {
            Files.write(file, encoded(2, 1, "server", invalid, 1));
            assertEquals(0, AdaptiveFlightMemory.load(file).size());
        }
        Files.write(file, encoded(2, 1, "server", 1, 2.1));
        assertEquals(0, AdaptiveFlightMemory.load(file).size());
    }

    @Test void validChecksumsCannotAdmitInvalidRejectionCeilings() throws IOException {
        Path file = directory.resolve("invalid-ceiling.memory");
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -.3, 0, 65}) {
            Files.write(file, encoded(2, 1, "server", 1, .05, invalid, .05));
            assertEquals(0, AdaptiveFlightMemory.load(file).size());
        }
        Files.write(file, encoded(2, 1, "server", 1, .05, 1, 2.1));
        assertEquals(0, AdaptiveFlightMemory.load(file).size());
        Files.write(file, encoded(2, 1, "server", 1, .05, .5, .05));
        assertEquals(1, AdaptiveFlightMemory.load(file).size(),
            "demonstrated speeds may exceed remembered correction guidance");
    }

    @Test void duplicateBlankAndUnnormalizedStoredIdentitiesAreRejected() throws IOException {
        Path file = directory.resolve("invalid-identity.memory");
        for (String invalid : new String[] {"", " server ", "server\nother", "x".repeat(1_025)}) {
            Files.write(file, encoded(2, 1, invalid, 1, .1));
            assertEquals(0, AdaptiveFlightMemory.load(file).size());
        }
        Files.write(file, encoded(2, 2, "duplicate-server", 1, .1));
        assertEquals(0, AdaptiveFlightMemory.load(file).size());
    }

    @Test void trailingBytesAndOversizedFilesAreRejected() throws IOException {
        Path file = directory.resolve("excess.memory");
        byte[] valid = encoded(2, 1, "server", 1, .1);
        Files.write(file, Arrays.copyOf(valid, valid.length + 1));
        assertEquals(0, AdaptiveFlightMemory.load(file).size());
        Files.write(file, new byte[500_000]);
        assertEquals(0, AdaptiveFlightMemory.load(file).size());
    }

    @Test void atomicReplacementLeavesOnlyCurrentMemoryAndRetainsTheLastSavedSnapshot() throws IOException {
        Path file = directory.resolve("replace.memory");
        var memory = new AdaptiveFlightMemory();
        memory.put("server", new Limits(5, .6, 1, .04));
        memory.save(file);
        memory.put("server", new Limits(4, .5, .9, .03));
        assertEquals(5, AdaptiveFlightMemory.load(file).get("server").horizontal());
        memory.save(file);

        assertEquals(memory.get("server"), AdaptiveFlightMemory.load(file).get("server"));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void failedReplacementCleansItsTemporaryFileWithoutDeletingAnExistingPath() throws IOException {
        Path target = Files.createDirectory(directory.resolve("existing.memory"));
        Files.writeString(target.resolve("keep"), "previous data");
        var memory = new AdaptiveFlightMemory();
        memory.put("server", new Limits(5, .6, 1, .04));

        assertThrows(IOException.class, () -> memory.save(target));
        assertEquals("previous data", Files.readString(target.resolve("keep")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    private static byte[] encoded(int version, int count, String server, double horizontal,
                                  double acceleration) throws IOException {
        return encoded(version, count, server, horizontal, acceleration, horizontal, acceleration);
    }

    private static byte[] encoded(int version, int count, String server, double horizontal,
                                  double acceleration, double ceilingHorizontal,
                                  double ceilingAcceleration) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var crc = new CRC32();
        try (var out = new DataOutputStream(new CheckedOutputStream(bytes, crc))) {
            out.writeInt(0x4341464d);
            out.writeInt(version);
            out.writeInt(count);
            if (server != null) {
                for (int i = 0; i < count; i++) {
                    out.writeUTF(server);
                    out.writeLong(Instant.parse("2026-10-18T00:00:00Z").toEpochMilli());
                    out.writeDouble(horizontal);
                    out.writeDouble(.8);
                    out.writeDouble(1.4);
                    out.writeDouble(acceleration);
                    if (version >= 2) {
                        out.writeDouble(ceilingHorizontal);
                        out.writeDouble(.8);
                        out.writeDouble(1.4);
                        out.writeDouble(ceilingAcceleration);
                    }
                }
            }
            out.writeLong(crc.getValue());
        }
        return bytes.toByteArray();
    }

    private static final class MutableClock extends Clock {
        private long millis = Instant.parse("2026-10-18T00:00:00Z").toEpochMilli();
        void advance(long amount) { millis += amount; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
