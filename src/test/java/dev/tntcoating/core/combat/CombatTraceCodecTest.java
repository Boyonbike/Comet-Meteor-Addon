/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every recorded line must survive the JSON Lines round trip unchanged; Phase 0.4 replays these files.
 */
class CombatTraceCodecTest {
    private static CombatEventRecord event(ObservationKind kind, boolean applied) {
        JsonObject payload = new JsonObject();
        payload.addProperty("packet", "ClientboundExplodePacket");
        payload.addProperty("count", 12);
        payload.addProperty("flag", true);
        JsonObject nested = new JsonObject();
        nested.addProperty("inner", "value");
        payload.add("nested", nested);
        return new CombatEventRecord(
            3L, 41L, 9L, 2, 1_234_567_890L, 120L, kind, applied,
            applied ? 88L : -1L,
            applied ? 17 : -1,
            applied ? "0f9c4e8a-6d3f-4a55-9f1b-2f5f0d52a9a1" : null,
            applied ? 1.25 : null, applied ? 64.0 : null, applied ? -3.5 : null,
            payload);
    }

    @Test
    void everyObservationKindRoundTripsThroughJson() {
        for (ObservationKind kind : ObservationKind.values()) {
            for (boolean applied : new boolean[] {false, true}) {
                CombatEventRecord original = event(kind, applied);
                CombatTraceLine decoded = CombatTraceCodec.decode(original.toJson());
                assertEquals(original, decoded, "kind " + kind + ", applied " + applied);
            }
        }
    }

    @Test
    void labelRoundTripsThroughJson() {
        CombatLabelRecord original = new CombatLabelRecord(5L, 77L, 987_654_321L, 44L, "placed crystal at the feet hole");
        CombatTraceLine decoded = CombatTraceCodec.decode(original.toJson());
        assertEquals(original, decoded);
    }

    @Test
    void unappliedRecordStaysUnappliedAfterRoundTrip() {
        CombatEventRecord original = event(ObservationKind.HEALTH, false);
        CombatEventRecord decoded = CombatTraceCodec.decodeEvent(original.toJson());
        assertFalse(decoded.applied());
        assertEquals(-1L, decoded.appliedRevision());
        assertEquals(-1, decoded.entityId());
        assertNull(decoded.uuid());
        assertNull(decoded.x());
    }

    @Test
    void payloadIsDefensivelyCopiedOnBothSides() {
        CombatEventRecord original = event(ObservationKind.EXPLOSION, true);
        JsonObject source = original.payload();
        source.addProperty("mutated", true);
        assertFalse(original.payload().has("mutated"), "constructor copy must not alias the caller's object");

        JsonObject json = original.toJson();
        CombatEventRecord decoded = CombatTraceCodec.decodeEvent(json);
        json.getAsJsonObject("payload").addProperty("mutated", true);
        assertFalse(decoded.payload().has("mutated"), "decoded payload must not alias the JSON source");
    }

    @Test
    void unknownLineTypeIsRejected() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "mystery");
        assertThrows(IllegalArgumentException.class, () -> CombatTraceCodec.decode(json));
    }

    @Test
    void unknownObservationKindIsRejected() {
        JsonObject json = event(ObservationKind.SPAWN, true).toJson();
        json.addProperty("kind", "NOT_A_KIND");
        assertThrows(IllegalArgumentException.class, () -> CombatTraceCodec.decodeEvent(json));
    }
}
