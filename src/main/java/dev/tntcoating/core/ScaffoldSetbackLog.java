package dev.tntcoating.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * Scaffold's rubber-band log: one JSON line per server setback, with what the client reported and placed in the two
 * seconds before it, so each setback can be matched to the move, rotation or placement the server rejected.
 */
public final class ScaffoldSetbackLog {
    /** One client tick: Scaffold's placements, then the position, rotation and ground flag the client reported. */
    public record Tick(long tick, double x, double y, double z, float yaw, float pitch, boolean onGround,
                       boolean towering, int placed, int paced) {}

    private record Placement(long tick, long position) {}

    private static final int HISTORY_TICKS = 40;
    private static final int TIMELINE_TICKS = 20;
    private static final int MAX_PLACEMENTS = 256;
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private final ArrayDeque<Tick> ticks = new ArrayDeque<>();
    private final ArrayDeque<Placement> placements = new ArrayDeque<>();
    private PacketCaptureLog log;
    private boolean failed;
    private int setbacks;
    private long lastSetbackTick = -1;

    public void tick(Tick tick) {
        ticks.addLast(tick);
        while (ticks.size() > HISTORY_TICKS) ticks.removeFirst();
        while (!placements.isEmpty() && tick.tick() - placements.peekFirst().tick() >= HISTORY_TICKS) placements.removeFirst();
    }

    public void placed(long tick, long position) {
        placements.addLast(new Placement(tick, position));
        while (placements.size() > MAX_PLACEMENTS) placements.removeFirst();
    }

    /**
     * Adds the history before a setback to (x, y, z) at {@code tick}: the move the server rejected, the placements
     * around it and a per-tick timeline relative to the target, newest last. {@code support} is the block under it.
     */
    public void describe(JsonObject record, long tick, double x, double y, double z, long support) {
        setbacks++;
        record.addProperty("setback", setbacks);
        record.addProperty("ticksSinceLastSetback", lastSetbackTick < 0 ? -1 : tick - lastSetbackTick);
        lastSetbackTick = tick;

        int placed = 0, paced = 0;
        for (Tick t : ticks) {
            if (tick - t.tick() >= 20) continue;
            placed += t.placed();
            paced += t.paced();
        }
        record.addProperty("placedLastSecond", placed);
        record.addProperty("pacedLastSecond", paced);
        long supportPlaced = -1;
        for (Placement placement : placements) if (placement.position() == support) supportPlaced = tick - placement.tick();
        // Standing on a block the server never accepted is one way to be set back.
        record.addProperty("supportPlacedTicksAgo", supportPlaced);

        Tick[] history = ticks.toArray(new Tick[0]);
        int accepted = -1;
        for (int i = history.length - 1; i >= 0; i--) {
            if (distanceSqr(history[i], x, y, z) < 1e-4) {
                accepted = i;
                break;
            }
        }
        // How far back the server put you: 0 is where you last were, larger is an older position, -1 is older than 2 s.
        record.addProperty("targetTicksAgo", accepted < 0 ? -1 : tick - history[accepted].tick());
        boolean found = accepted >= 0 && accepted + 1 < history.length;
        record.addProperty("rejectedMoveFound", found);
        if (found) describeRejected(record, tick, history[accepted], history[accepted + 1]);

        JsonArray timeline = new JsonArray();
        for (int i = Math.max(0, history.length - TIMELINE_TICKS); i < history.length; i++) {
            Tick t = history[i];
            JsonObject entry = new JsonObject();
            entry.addProperty("ago", tick - t.tick());
            entry.addProperty("dx", round(t.x() - x, 1000));
            entry.addProperty("dy", round(t.y() - y, 1000));
            entry.addProperty("dz", round(t.z() - z, 1000));
            entry.addProperty("ground", t.onGround());
            entry.addProperty("yaw", round(t.yaw(), 10));
            entry.addProperty("pitch", round(t.pitch(), 10));
            entry.addProperty("placed", t.placed());
            entry.addProperty("paced", t.paced());
            entry.addProperty("tower", t.towering());
            timeline.add(entry);
        }
        record.add("timeline", timeline);
    }

    /** The move reported right after the position the server set you back to: its speed, rotation snap and placements. */
    private static void describeRejected(JsonObject record, long tick, Tick from, Tick rejected) {
        double ticks = Math.max(1, rejected.tick() - from.tick());
        double dx = (rejected.x() - from.x()) / ticks, dy = (rejected.y() - from.y()) / ticks, dz = (rejected.z() - from.z()) / ticks;
        record.addProperty("rejectedTicksAgo", tick - rejected.tick());
        record.addProperty("rejectedSpeed", Math.sqrt(dx * dx + dy * dy + dz * dz) * 20);
        record.addProperty("rejectedHorizontalSpeed", Math.sqrt(dx * dx + dz * dz) * 20);
        record.addProperty("rejectedVerticalSpeed", dy * 20);
        record.addProperty("rejectedOnGround", rejected.onGround());
        record.addProperty("rejectedYawStep", wrapDegrees(rejected.yaw() - from.yaw()));
        record.addProperty("rejectedPitchStep", rejected.pitch() - from.pitch());
        record.addProperty("rejectedPlaced", rejected.placed());
        record.addProperty("rejectedTowering", rejected.towering());
    }

    /** Appends a described record, opening the file with the first setback. An open failure is thrown only once. */
    public void write(Path directory, JsonObject record) throws IOException {
        if (log == null) {
            if (failed) return;
            try {
                log = PacketCaptureLog.open(directory, MAX_BYTES);
            } catch (IOException | RuntimeException e) {
                failed = true;
                throw e;
            }
        }
        log.append(record);
    }

    /** The open file, or null before the first setback. */
    public Path path() { return log == null ? null : log.path(); }

    /** Ends the file and forgets the history; the next setback starts a new file. */
    public void close() {
        if (log != null) log.close();
        log = null;
        failed = false;
        ticks.clear();
        placements.clear();
        setbacks = 0;
        lastSetbackTick = -1;
    }

    private static double distanceSqr(Tick t, double x, double y, double z) {
        double dx = t.x() - x, dy = t.y() - y, dz = t.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    private static double round(double value, double scale) { return Math.round(value * scale) / scale; }
}
