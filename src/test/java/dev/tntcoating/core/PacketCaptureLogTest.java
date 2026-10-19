package dev.tntcoating.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PacketCaptureLogTest {
    @TempDir Path directory;

    @Test void closeDrainsAcceptedRecordsInOrderAndAddsOneSummary() throws Exception {
        PacketCaptureLog log = PacketCaptureLog.open(directory, 1_000_000);
        JsonObject mutable = event("original\nvalue");
        assertTrue(log.append(mutable));
        mutable.addProperty("value", "changed");
        for (int i = 0; i < 100; i++) {
            JsonObject event = new JsonObject();
            event.addProperty("sequence", i);
            assertTrue(log.append(event));
        }
        log.close();
        log.close();
        awaitFinished(log);

        var lines = Files.readAllLines(log.path(), StandardCharsets.UTF_8);
        assertEquals(102, lines.size());
        assertEquals("original\nvalue", JsonParser.parseString(lines.getFirst()).getAsJsonObject().get("value").getAsString());
        for (int i = 0; i < 100; i++) {
            assertEquals(i, JsonParser.parseString(lines.get(i + 1)).getAsJsonObject().get("sequence").getAsInt());
        }
        JsonObject summary = JsonParser.parseString(lines.getLast()).getAsJsonObject();
        assertEquals("summary", summary.get("event").getAsString());
        assertEquals("closed", summary.get("reason").getAsString());
        assertEquals(101, summary.get("written_records").getAsLong());
        assertEquals(0, summary.get("dropped_records").getAsLong());
        assertEquals(101, log.writtenRecords());
        assertNull(log.failure());
        assertFalse(log.isAccepting());
    }

    @Test void capturesCreatedInTheSameDirectoryHaveUniquePaths() throws Exception {
        PacketCaptureLog first = PacketCaptureLog.open(directory.resolve("captures"), 512);
        PacketCaptureLog second = PacketCaptureLog.open(directory.resolve("captures"), 512);
        first.close();
        second.close();
        awaitFinished(first);
        awaitFinished(second);
        assertNotEquals(first.path(), second.path());
        assertTrue(Files.exists(first.path()));
        assertTrue(Files.exists(second.path()));
        assertTrue(first.path().getFileName().toString().matches("packet-.*\\.jsonl"));
    }

    @Test void byteLimitIncludesUtf8RecordsAndReservesSpaceForTheSummary() throws Exception {
        JsonObject unicode = event("雪🌍");
        int recordBytes = (unicode + "\n").getBytes(StandardCharsets.UTF_8).length;
        long limit = 256L + recordBytes * 3;
        PacketCaptureLog log = PacketCaptureLog.open(directory, limit);
        assertTrue(log.append(unicode));
        assertTrue(log.append(unicode));
        assertTrue(log.append(unicode));
        assertFalse(log.append(unicode));
        assertFalse(log.isAccepting());
        awaitFinished(log);

        var lines = Files.readAllLines(log.path(), StandardCharsets.UTF_8);
        assertEquals(4, lines.size());
        assertEquals("雪🌍", JsonParser.parseString(lines.getFirst()).getAsJsonObject().get("value").getAsString());
        JsonObject summary = JsonParser.parseString(lines.getLast()).getAsJsonObject();
        assertEquals("max_bytes", summary.get("reason").getAsString());
        assertEquals(3, summary.get("written_records").getAsLong());
        assertEquals(1, summary.get("dropped_records").getAsLong());
        assertEquals(recordBytes * 3L, summary.get("written_bytes").getAsLong());
        assertTrue(Files.size(log.path()) <= limit);
        assertNull(log.failure());
    }

    @Test void evenTheMinimumLimitFitsAValidSummary() throws Exception {
        PacketCaptureLog log = PacketCaptureLog.open(directory, 256);
        assertFalse(log.append(event("too large")));
        awaitFinished(log);
        var lines = Files.readAllLines(log.path());
        assertEquals(1, lines.size());
        assertEquals("summary", JsonParser.parseString(lines.getFirst()).getAsJsonObject().get("event").getAsString());
        assertTrue(Files.size(log.path()) <= 256);
    }

    @Test void closeReturnsWhileTheWriterIsBusyAndPreventsFurtherAppends() throws Exception {
        CountDownLatch enteredWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        ByteArrayOutputStream recorded = new ByteArrayOutputStream();
        OutputStream slow = new OutputStream() {
            @Override public void write(int value) throws IOException { recorded.write(value); }
            @Override public void write(byte[] bytes) throws IOException {
                enteredWrite.countDown();
                try {
                    if (!releaseWrite.await(5, TimeUnit.SECONDS)) throw new IOException("writer was not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                recorded.write(bytes);
            }
        };
        PacketCaptureLog log = new PacketCaptureLog(directory.resolve("slow.jsonl"), 10_000, slow);
        try {
            assertTrue(log.append(event("first")));
            assertTrue(enteredWrite.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), log::close);
            assertFalse(log.isFinished());
            assertFalse(log.append(event("after close")));
            assertEquals(1, log.droppedRecords());
        } finally {
            log.close();
            releaseWrite.countDown();
        }
        awaitFinished(log);
        assertEquals(1, log.writtenRecords());
        assertEquals(2, recorded.toString(StandardCharsets.UTF_8).lines().count());
        assertNull(log.failure());
    }

    @Test void anIoFailureIsVisibleAndTerminatesTheWriter() throws Exception {
        CountDownLatch enteredWrite = new CountDownLatch(1);
        CountDownLatch failWrite = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        IOException diskError = new IOException("simulated disk full");
        OutputStream failing = new OutputStream() {
            @Override public void write(int value) throws IOException {
                enteredWrite.countDown();
                try {
                    if (!failWrite.await(5, TimeUnit.SECONDS)) throw new IOException("failure was not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                throw diskError;
            }
            @Override public void close() { closed.countDown(); }
        };
        PacketCaptureLog log = new PacketCaptureLog(directory.resolve("failure.jsonl"), 10_000, failing);
        try {
            assertTrue(log.append(event("in flight")));
            assertTrue(enteredWrite.await(5, TimeUnit.SECONDS));
            assertTrue(log.append(event("queued")));
        } finally {
            failWrite.countDown();
        }
        awaitFinished(log);
        assertSame(diskError, log.failure());
        assertEquals(0, log.writtenRecords());
        assertEquals(2, log.droppedRecords());
        assertFalse(log.isAccepting());
        assertFalse(log.append(event("after failure")));
        assertEquals(0, closed.getCount());
    }

    @Test void aFullQueueDropsEventsWithoutBlockingOrEndingTheCapture() throws Exception {
        CountDownLatch enteredWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        ByteArrayOutputStream recorded = new ByteArrayOutputStream();
        OutputStream slow = new OutputStream() {
            @Override public void write(int value) { recorded.write(value); }
            @Override public void write(byte[] bytes) throws IOException {
                enteredWrite.countDown();
                try {
                    if (!releaseWrite.await(5, TimeUnit.SECONDS)) throw new IOException("writer was not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                recorded.write(bytes);
            }
        };
        PacketCaptureLog log = new PacketCaptureLog(directory.resolve("full.jsonl"), 1_000_000, slow);
        try {
            assertTrue(log.append(event("in flight")));
            assertTrue(enteredWrite.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 8_192; i++) assertTrue(log.append(event("queued")));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertFalse(log.append(event("overflow"))));
            assertTrue(log.isAccepting());
            assertEquals(1, log.droppedRecords());
        } finally {
            log.close();
            releaseWrite.countDown();
        }
        awaitFinished(log);
        assertEquals(8_193, log.writtenRecords());
        assertEquals(8_194, recorded.toString(StandardCharsets.UTF_8).lines().count());
        assertNull(log.failure());
    }

    @Test void anIdleCaptureFlushesWithoutWaitingForClose() throws Exception {
        PacketCaptureLog log = PacketCaptureLog.open(directory, 10_000);
        try {
            assertTrue(log.append(event("flush me")));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Files.size(log.path()) == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.size(log.path()) > 0, "an idle writer should flush within a second");
            assertTrue(log.isAccepting());
        } finally {
            log.close();
        }
        awaitFinished(log);
        assertNull(log.failure());
    }

    @Test void rejectedLimitsAndBadDirectoriesDoNotStartACapture() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> PacketCaptureLog.open(directory, 255));
        Path regularFile = directory.resolve("regular-file");
        Files.writeString(regularFile, "existing");
        assertThrows(IOException.class, () -> PacketCaptureLog.open(regularFile, 1_000));
        assertEquals("existing", Files.readString(regularFile));
    }

    private static JsonObject event(String value) {
        JsonObject event = new JsonObject();
        event.addProperty("value", value);
        return event;
    }

    private static void awaitFinished(PacketCaptureLog log) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!log.isFinished() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(log.isFinished(), "capture writer did not terminate");
    }

    @Test void finishedCallbacksRunAfterTheDrainWithFinalCounts() throws Exception {
        PacketCaptureLog log = PacketCaptureLog.open(directory, 1_000_000);
        for (int i = 0; i < 50; i++) assertTrue(log.append(event("record " + i)));
        var counted = new java.util.concurrent.atomic.AtomicLong(-1);
        var done = new CountDownLatch(1);
        log.onFinished(() -> { counted.set(log.writtenRecords()); done.countDown(); });
        log.close();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(50, counted.get());
        assertTrue(log.isFinished());
        var late = new CountDownLatch(1);
        log.onFinished(late::countDown);
        assertEquals(0, late.getCount(), "a callback registered after finishing runs immediately");
    }
}
