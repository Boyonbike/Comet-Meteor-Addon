package dev.tntcoating.core.scaffold;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.tntcoating.core.PacketCaptureLog;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Bounded scaffold session diagnostics. File opening and writing both happen off the game thread. */
public final class ScaffoldDiagnostics {
    public enum Mode { Off, Summary, Trace }

    private record Sample(long time, String category, JsonObject data) {}
    private static final long HISTORY = 2_000_000_000L;
    private static final long INTERVAL = 5_000_000_000L;
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private final Path directory;
    private final LongSupplier clock;
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private final ArrayDeque<JsonObject> openingQueue = new ArrayDeque<>();
    private Mode mode = Mode.Off;
    private PacketCaptureLog sink;
    private CompletableFuture<Void> opener;
    private long started, lastSummary, dropped;
    private String failure;
    private boolean closed;

    public ScaffoldDiagnostics(Path directory, LongSupplier clock) {
        this.directory = directory;
        this.clock = clock;
    }

    public synchronized void start(Mode mode, JsonObject metadata) {
        if (opener != null || closed) throw new IllegalStateException("one diagnostics object per session");
        this.mode = mode;
        started = lastSummary = clock.getAsLong();
        if (mode == Mode.Off) return;
        JsonObject first = record("session_start", metadata);
        first.addProperty("schema", 1);
        first.addProperty("utc", Instant.now().toString());
        openingQueue.add(first);
        opener = CompletableFuture.runAsync(() -> {
            try {
                PacketCaptureLog opened = PacketCaptureLog.open(directory, MAX_BYTES);
                synchronized (this) {
                    sink = opened;
                    while (!openingQueue.isEmpty()) sink.append(openingQueue.removeFirst());
                    if (closed) sink.close();
                }
            } catch (Exception error) {
                synchronized (this) {
                    failure = error.getClass().getSimpleName() + ": " + error.getMessage();
                    dropped += openingQueue.size();
                    openingQueue.clear();
                }
            }
        });
    }

    public synchronized void sample(String category, JsonObject data) {
        if (mode == Mode.Off || closed) return;
        long now = clock.getAsLong();
        prune(now);
        int limit = category.equals("packet") ? 1024 : 256;
        int count = 0;
        for (Sample sample : history) if (sample.category.equals(category)) count++;
        if (count >= limit) {
            var iterator = history.iterator();
            while (iterator.hasNext()) if (iterator.next().category.equals(category)) { iterator.remove(); break; }
        }
        while (history.size() >= 1536) history.removeFirst();
        history.addLast(new Sample(now, category, data.deepCopy()));
        if (mode == Mode.Trace) write(record(category, data));
    }

    public synchronized void event(String type, JsonObject fields) {
        if (mode == Mode.Off || closed) return;
        JsonObject event = record(type, fields);
        if (type.equals("correction") || type.equals("timeout") || type.equals("rejection") || type.equals("rollback")) {
            prune(clock.getAsLong());
            JsonArray preceding = new JsonArray();
            for (Sample sample : history) {
                JsonObject entry = sample.data.deepCopy();
                entry.addProperty("kind", sample.category);
                entry.addProperty("offsetNanos", sample.time - started);
                preceding.add(entry);
            }
            event.add("history", preceding);
        }
        write(event);
    }

    public synchronized void summary(JsonObject stats) {
        if (mode == Mode.Off || closed || clock.getAsLong() - lastSummary < INTERVAL) return;
        lastSummary = clock.getAsLong();
        JsonObject summary = record("session_summary", stats);
        summary.addProperty("loggerDropped", droppedRecords());
        String problem = problem();
        if (problem != null) summary.addProperty("loggerProblem", problem);
        write(summary);
    }

    public synchronized void close(JsonObject stats) {
        if (closed) return;
        if (mode != Mode.Off) write(record("session_end", stats));
        closed = true;
        if (sink != null) sink.close();
        history.clear();
    }

    public synchronized Path path() { return sink == null ? null : sink.path(); }
    public synchronized int bufferedSamples() { prune(clock.getAsLong()); return history.size(); }
    public synchronized long droppedRecords() { return dropped + (sink == null ? 0 : sink.droppedRecords()); }
    public synchronized boolean finished() {
        return mode == Mode.Off || (opener != null && opener.isDone() && (sink == null || sink.isFinished()));
    }
    public synchronized String problem() {
        if (failure != null) return failure;
        if (sink != null && sink.failure() != null) return sink.failure().toString();
        if (!closed && sink != null && !sink.isAccepting()) return "log capacity reached";
        return null;
    }

    private JsonObject record(String type, JsonObject fields) {
        JsonObject result = fields.deepCopy();
        result.addProperty("event", type);
        result.addProperty("offsetNanos", clock.getAsLong() - started);
        return result;
    }

    private void prune(long now) {
        while (!history.isEmpty() && now - history.peekFirst().time >= HISTORY) history.removeFirst();
    }

    private void write(JsonObject event) {
        if (failure != null) { dropped++; return; }
        if (sink != null) sink.append(event);
        else if (openingQueue.size() < 256) openingQueue.addLast(event);
        else dropped++;
    }
}
