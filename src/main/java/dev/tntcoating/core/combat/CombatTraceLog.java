/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A bounded JSON Lines writer for combat traces, drained off the game thread.
 *
 * <p>Each instance writes {@code <timestamp>.jsonl} inside the directory it is given. Accepted lines are
 * copied before {@link #append} returns. The final line is always a label summary, so a trace file stays
 * uniformly decodable by {@link CombatTraceCodec}.</p>
 */
public final class CombatTraceLog implements CombatTraceSink {
    private static final int QUEUE_CAPACITY = 8_192;
    private static final int SUMMARY_RESERVE = 256;
    private static final long FLUSH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
        .withZone(ZoneOffset.UTC);

    private final Object lock = new Object();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private final Path path;
    private final long dataLimit;
    private final OutputStream output;
    private long reservedBytes;
    private long writtenBytes;
    private String reason = "closed";
    private volatile boolean accepting = true;
    private volatile boolean finished;
    private volatile long writtenRecords;
    private volatile long droppedRecords;
    private volatile IOException failure;
    private Runnable finishedCallback;

    /** Creates a fresh trace file. The limit must leave {@value #SUMMARY_RESERVE} bytes for the summary. */
    public static CombatTraceLog open(Path directory, long maxBytes) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (maxBytes < SUMMARY_RESERVE) throw new IllegalArgumentException("maxBytes must be at least 256");
        Files.createDirectories(directory);
        String timestamp = FILE_TIME.format(Instant.now());
        while (true) {
            Path path = directory.resolve(timestamp + ".jsonl");
            for (int suffix = 2; Files.exists(path); suffix++) {
                path = directory.resolve(timestamp + "-" + suffix + ".jsonl");
            }
            OutputStream stream;
            try {
                stream = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ignored) {
                continue;
            }
            try {
                return new CombatTraceLog(path, maxBytes, new BufferedOutputStream(stream));
            } catch (RuntimeException | Error error) {
                try {
                    stream.close();
                } catch (IOException closeError) {
                    error.addSuppressed(closeError);
                }
                throw error;
            }
        }
    }

    // Also permits deterministic I/O-failure tests without depending on the host filesystem.
    CombatTraceLog(Path path, long maxBytes, OutputStream output) {
        this.path = path;
        this.dataLimit = maxBytes - SUMMARY_RESERVE;
        this.output = output;
        try {
            Thread worker = new Thread(this::writeLoop, "combat-trace-writer");
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException | Error error) {
            try {
                output.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    /**
     * Serializes a line immediately, returning false if closed, full, or over the byte limit.
     * Reaching the byte limit ends the trace; a temporarily full queue only drops that line.
     */
    public boolean append(JsonObject line) {
        Objects.requireNonNull(line, "line");
        synchronized (lock) {
            if (!accepting) {
                droppedRecords++;
                return false;
            }
        }
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            if (!accepting) {
                droppedRecords++;
                return false;
            }
            if (bytes.length > dataLimit - reservedBytes) {
                droppedRecords++;
                accepting = false;
                reason = "max_bytes";
                lock.notifyAll();
                return false;
            }
            if (queue.size() >= QUEUE_CAPACITY) {
                droppedRecords++;
                return false;
            }
            reservedBytes += bytes.length;
            queue.addLast(bytes);
            lock.notifyAll();
            return true;
        }
    }

    /** Requests shutdown and returns immediately. Already accepted lines are drained by the writer. */
    @Override
    public void close() {
        synchronized (lock) {
            accepting = false;
            lock.notifyAll();
        }
    }

    /**
     * Runs once the writer has drained and closed the file, on the writer thread, or immediately if it already
     * has. Final record counts are only complete at that point.
     */
    public void onFinished(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        synchronized (lock) {
            if (!finished) {
                Runnable previous = finishedCallback;
                finishedCallback = previous == null ? callback : () -> {
                    previous.run();
                    callback.run();
                };
                return;
            }
        }
        callback.run();
    }

    public boolean isAccepting() {
        return accepting;
    }

    public boolean isFinished() {
        return finished;
    }

    public long writtenRecords() {
        return writtenRecords;
    }

    public long droppedRecords() {
        return droppedRecords;
    }

    public IOException failure() {
        return failure;
    }

    public Path path() {
        return path;
    }

    private void writeLoop() {
        byte[] inFlight = null;
        long nextFlush = System.nanoTime() + FLUSH_INTERVAL_NANOS;
        try {
            while (true) {
                synchronized (lock) {
                    while (queue.isEmpty() && accepting) {
                        long remaining = nextFlush - System.nanoTime();
                        if (remaining <= 0) break;
                        try {
                            TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                        } catch (InterruptedException ignored) {
                            accepting = false;
                            reason = "interrupted";
                            Thread.currentThread().interrupt();
                        }
                    }
                    inFlight = queue.pollFirst();
                    if (inFlight == null && !accepting) break;
                }
                if (inFlight != null) {
                    output.write(inFlight);
                    synchronized (lock) {
                        writtenRecords++;
                        writtenBytes += inFlight.length;
                    }
                    inFlight = null;
                }
                if (System.nanoTime() >= nextFlush) {
                    output.flush();
                    nextFlush = System.nanoTime() + FLUSH_INTERVAL_NANOS;
                }
            }
            output.write(summary());
            output.flush();
        } catch (IOException error) {
            synchronized (lock) {
                failure = error;
                accepting = false;
                reason = "io_error";
                if (inFlight != null) droppedRecords++;
                droppedRecords += queue.size();
                queue.clear();
            }
        } finally {
            try {
                output.close();
            } catch (IOException error) {
                synchronized (lock) {
                    if (failure == null) failure = error;
                    else if (failure != error) failure.addSuppressed(error);
                }
            }
            Runnable callback;
            synchronized (lock) {
                accepting = false;
                finished = true;
                callback = finishedCallback;
                finishedCallback = null;
                lock.notifyAll();
            }
            if (callback != null) callback.run();
        }
    }

    private byte[] summary() {
        JsonObject summary = new JsonObject();
        synchronized (lock) {
            summary.addProperty("v", CombatTraceCodec.VERSION);
            summary.addProperty("type", "label");
            summary.addProperty("conn", -1L);
            summary.addProperty("seq", -1L);
            summary.addProperty("nanos", System.nanoTime());
            summary.addProperty("tick", -1L);
            summary.addProperty("label", "recorder-summary reason=" + reason
                + " written=" + writtenRecords
                + " dropped=" + droppedRecords
                + " bytes=" + writtenBytes);
        }
        return (summary + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
