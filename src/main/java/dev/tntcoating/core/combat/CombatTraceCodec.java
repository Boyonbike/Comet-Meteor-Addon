/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * JSON Lines encoding for combat traces. Pure: no Minecraft types, so Phase 0.4 replay tooling can use it
 * from a unit test. Field names are the fixture format; changing them breaks recorded traces.
 */
public final class CombatTraceCodec {
    public static final int VERSION = 1;

    private CombatTraceCodec() {}

    public static JsonObject encode(CombatTraceLine line) {
        return switch (line) {
            case CombatEventRecord event -> encodeEvent(event);
            case CombatLabelRecord label -> encodeLabel(label);
        };
    }

    public static CombatTraceLine decode(JsonObject json) {
        String type = requiredString(json, "type");
        return switch (type) {
            case "event" -> decodeEvent(json);
            case "label" -> decodeLabel(json);
            default -> throw new IllegalArgumentException("Unknown trace line type: " + type);
        };
    }

    public static JsonObject encodeEvent(CombatEventRecord record) {
        JsonObject json = base("event", record.connectionId(), record.receiptSeq(),
            record.receiptNanos(), record.clientTick());
        json.addProperty("bundle", record.bundleId());
        json.addProperty("bundlePosition", record.bundlePosition());
        json.addProperty("kind", record.kind().name());
        json.addProperty("applied", record.applied());
        json.addProperty("revision", record.appliedRevision());
        json.addProperty("entity", record.entityId());
        if (record.uuid() != null) json.addProperty("uuid", record.uuid());
        if (record.x() != null && record.y() != null && record.z() != null) {
            JsonArray position = new JsonArray();
            position.add(record.x());
            position.add(record.y());
            position.add(record.z());
            json.add("pos", position);
        }
        json.add("payload", record.payload());
        return json;
    }

    public static CombatEventRecord decodeEvent(JsonObject json) {
        ObservationKind kind;
        try {
            kind = ObservationKind.valueOf(requiredString(json, "kind"));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown observation kind: " + json.get("kind"), error);
        }

        Double x = null;
        Double y = null;
        Double z = null;
        JsonElement position = json.get("pos");
        if (position != null && position.isJsonArray()) {
            JsonArray array = position.getAsJsonArray();
            if (array.size() == 3) {
                x = array.get(0).getAsDouble();
                y = array.get(1).getAsDouble();
                z = array.get(2).getAsDouble();
            }
        }

        JsonObject payload = new JsonObject();
        JsonElement encodedPayload = json.get("payload");
        if (encodedPayload != null && encodedPayload.isJsonObject()) {
            payload = encodedPayload.getAsJsonObject().deepCopy();
        }

        return new CombatEventRecord(
            optionalLong(json, "conn", 0L),
            optionalLong(json, "seq", 0L),
            optionalLong(json, "bundle", CombatEventRecord.NO_BUNDLE),
            (int) optionalLong(json, "bundlePosition", CombatEventRecord.NO_BUNDLE_POSITION),
            optionalLong(json, "nanos", 0L),
            optionalLong(json, "tick", 0L),
            kind,
            optionalBoolean(json, "applied", false),
            optionalLong(json, "revision", CombatEventRecord.NO_REVISION),
            (int) optionalLong(json, "entity", CombatEventRecord.NO_ENTITY),
            optionalString(json, "uuid"),
            x, y, z,
            payload);
    }

    public static JsonObject encodeLabel(CombatLabelRecord record) {
        JsonObject json = base("label", record.connectionId(), record.receiptSeq(),
            record.receiptNanos(), record.clientTick());
        json.addProperty("label", record.label());
        return json;
    }

    public static CombatLabelRecord decodeLabel(JsonObject json) {
        return new CombatLabelRecord(
            optionalLong(json, "conn", 0L),
            optionalLong(json, "seq", 0L),
            optionalLong(json, "nanos", 0L),
            optionalLong(json, "tick", 0L),
            requiredString(json, "label"));
    }

    private static JsonObject base(String type, long connectionId, long receiptSeq, long nanos, long tick) {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("type", type);
        json.addProperty("conn", connectionId);
        json.addProperty("seq", receiptSeq);
        json.addProperty("nanos", nanos);
        json.addProperty("tick", tick);
        return json;
    }

    private static String requiredString(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing string field: " + name);
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static long optionalLong(JsonObject json, String name, long fallback) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsLong();
    }

    private static boolean optionalBoolean(JsonObject json, String name, boolean fallback) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }
}
