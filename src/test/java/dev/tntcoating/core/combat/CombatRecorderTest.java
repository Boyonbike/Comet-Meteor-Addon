/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CombatRecorderTest {
    @TempDir
    Path temp;

    /** A fake runtime that fails the test if the recorder ever reaches a send path. */
    private static final class FakeRuntime implements CombatRecorderRuntime {
        private long connectionId = 7L;
        private long tick = 42L;
        private int sends;

        @Override
        public long connectionId() {
            return connectionId;
        }

        @Override
        public long clientTick() {
            return tick;
        }

        void sendPlaceCrystal() {
            send("place crystal");
        }

        void sendAttack() {
            send("attack crystal");
        }

        void sendPacket() {
            send("packet");
        }

        int sends() {
            return sends;
        }

        private void send(String what) {
            sends++;
            throw new AssertionError("recorder attempted to send: " + what);
        }
    }

    private static JsonObject health(float health) {
        JsonObject payload = new JsonObject();
        payload.addProperty("packet", "ClientboundSetHealthPacket");
        payload.addProperty("health", health);
        return payload;
    }

    private static List<JsonObject> readLines(CombatTraceLog log) throws Exception {
        List<JsonObject> lines = new ArrayList<>();
        for (String line : Files.readAllLines(log.path())) {
            lines.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return lines;
    }

    private static void awaitFinished(CombatTraceLog log) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!log.isFinished() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(log.isFinished());
    }

    @Test
    void writesAppliedReceiptsInBundleOrderAndReleasesStaleReceiptsUnapplied() throws Exception {
        CombatTraceLog log = CombatTraceLog.open(temp, 1_000_000);
        CombatRecorder recorder = new CombatRecorder(new FakeRuntime(), log, 16);
        Object first = new Object();
        Object second = new Object();
        recorder.registerBundle(new Object(), List.of(first, second));

        recorder.receive(new CombatRecorder.Receipt(ObservationKind.HEALTH, -1, null, null, null, null,
            health(20.0f)), first);
        recorder.receive(new CombatRecorder.Receipt(ObservationKind.EXPLOSION, -1, null, null, null, null,
            new JsonObject()), second);

        assertTrue(recorder.markApplied(second, -1, null, null, null, null, null));
        recorder.tick();
        assertTrue(recorder.markApplied(first, -1, null, null, null, null, null));
        recorder.close();
        awaitFinished(log);

        List<JsonObject> lines = readLines(log);
        assertEquals(3, lines.size(), "two events plus the summary label");
        assertEquals("HEALTH", lines.get(0).get("kind").getAsString());
        assertEquals("EXPLOSION", lines.get(1).get("kind").getAsString());
        assertEquals(0, lines.get(0).get("bundlePosition").getAsInt());
        assertEquals(1, lines.get(1).get("bundlePosition").getAsInt());
        assertTrue(lines.get(0).get("applied").getAsBoolean());
        assertTrue(lines.get(1).get("applied").getAsBoolean());
        assertEquals("label", lines.get(2).get("type").getAsString());
    }

    @Test
    void anUnappliedReceiptIsStillWrittenAfterThePendingWindow() throws Exception {
        CombatTraceLog log = CombatTraceLog.open(temp, 1_000_000);
        FakeRuntime runtime = new FakeRuntime();
        CombatRecorder recorder = new CombatRecorder(runtime, log, 16);
        recorder.receive(new CombatRecorder.Receipt(ObservationKind.HEALTH, -1, null, null, null, null,
            health(20.0f)), new Object());

        runtime.tick = 42L + CombatRecorder.MAX_PENDING_TICKS;
        recorder.tick();
        recorder.close();
        awaitFinished(log);

        JsonObject record = readLines(log).getFirst();
        assertFalse(record.get("applied").getAsBoolean());
        assertEquals("no-application", record.getAsJsonObject("payload").get("unappliedReason").getAsString());
    }

    @Test
    void labelWritesAFreeTextMarkerLine() throws Exception {
        CombatTraceLog log = CombatTraceLog.open(temp, 1_000_000);
        CombatRecorder recorder = new CombatRecorder(new FakeRuntime(), log, 16);

        recorder.label("placed crystal at the feet hole");
        recorder.close();
        awaitFinished(log);

        JsonObject label = readLines(log).getFirst();
        assertEquals("label", label.get("type").getAsString());
        assertEquals("placed crystal at the feet hole", label.get("label").getAsString());
        assertEquals(7L, label.get("conn").getAsLong());
    }

    @Test
    void connectionChangesAreRecordedAsAppliedObservations() throws Exception {
        CombatTraceLog log = CombatTraceLog.open(temp, 1_000_000);
        CombatRecorder recorder = new CombatRecorder(new FakeRuntime(), log, 16);

        recorder.session("join");
        recorder.session("leave");
        recorder.close();
        awaitFinished(log);

        List<JsonObject> lines = readLines(log);
        assertEquals(3, lines.size(), "two connection changes plus the summary label");
        assertEquals("CONNECTION_CHANGE", lines.get(0).get("kind").getAsString());
        assertEquals("join", lines.get(0).getAsJsonObject("payload").get("event").getAsString());
        assertEquals("leave", lines.get(1).getAsJsonObject("payload").get("event").getAsString());
        assertTrue(lines.get(0).get("applied").getAsBoolean());
        assertTrue(lines.get(0).get("revision").getAsLong() < lines.get(1).get("revision").getAsLong());
    }

    @Test
    void recorderNeverCallsAnySendPathOnTheFakeRuntime() throws Exception {
        CombatTraceLog log = CombatTraceLog.open(temp, 1_000_000);
        FakeRuntime runtime = new FakeRuntime();
        CombatRecorder recorder = new CombatRecorder(runtime, log, 16);
        Object packet = new Object();

        recorder.session("join");
        recorder.registerBundle(new Object(), List.of(packet));
        recorder.receive(new CombatRecorder.Receipt(ObservationKind.HEALTH, -1, null, null, null, null,
            health(19.0f)), packet);
        recorder.markApplied(packet, -1, null, null, null, null, null);
        recorder.label("attacked");
        runtime.tick++;
        recorder.tick();
        recorder.close();
        awaitFinished(log);

        assertEquals(0, runtime.sends(), "the recorder must never send");
        assertNull(log.failure());
    }
}
