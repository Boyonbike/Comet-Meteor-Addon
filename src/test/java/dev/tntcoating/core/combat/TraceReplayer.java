/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Phase 0 JSON Lines client trace into the observation stream the pure engine consumes.
 *
 * <p>Only applied receipts become observations: an unapplied packet (for example a removal that vanilla
 * cancelled) is a receipt, not world state. Labels stay available so a test can reconstruct the actor's
 * own actions (the harness labels the crystal it placed); placement hazards are derived from those labels
 * and the first matching end-crystal spawn.</p>
 */
public final class TraceReplayer {
    public static final String END_CRYSTAL = "minecraft:end_crystal";

    public record Label(long seq, long tick, String text) {}

    /** A hazard the actor should have reserved before its labelled placement. */
    public record Placement(ConnectionKey conn, Box source, Box swept, int sentTick, EntityKey intended) {}

    public record Replay(ConnectionKey primaryConnection, long lastRevision,
                  List<Observation> observations, List<Label> labels, List<Placement> placements) {}

    private static final Pattern PISTON_LABEL = Pattern.compile(
        "piston-crystal id=(?<id>\\d+) source=BlockPos\\{x=(?<x>-?\\d+), y=(?<y>-?\\d+), z=(?<z>-?\\d+)}");
    private static final Pattern PLACE_LABEL = Pattern.compile("place-crystal(?:\\s.*)?");
    private static final Pattern CRYSTAL_ID_LABEL = Pattern.compile("crystal-id=(?<id>\\d+)");

    private TraceReplayer() {}

    public static Replay load(Path clientTrace) throws IOException {
        List<Observation> observations = new ArrayList<>();
        List<CombatEventRecord> events = new ArrayList<>();
        List<Label> labels = new ArrayList<>();
        long lastRevision = CombatEventRecord.NO_REVISION;

        for (String line : Files.readAllLines(clientTrace)) {
            if (line.isBlank()) continue;
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            switch (CombatTraceCodec.decode(json)) {
                case CombatLabelRecord label -> labels.add(new Label(
                    label.receiptSeq(), label.clientTick(), label.label()));
                case CombatEventRecord event -> {
                    events.add(event);
                    if (!event.applied()) continue;
                    observations.add(toObservation(event));
                    lastRevision = Math.max(lastRevision, event.appliedRevision());
                }
            }
        }

        ConnectionKey primary = observations.isEmpty()
            ? new ConnectionKey(0)
            : observations.getFirst().conn();
        List<Placement> placements = derivePlacements(labels, events, observations);
        return new Replay(primary, lastRevision, List.copyOf(observations),
            List.copyOf(labels), List.copyOf(placements));
    }

    public static boolean isCrystalSpawn(Observation observation) {
        return observation.kind() == ObservationKind.SPAWN
            && observation.payload() instanceof ObservationPayload.Spawn spawn
            && END_CRYSTAL.equals(spawn.entityType());
    }

    private static Observation toObservation(CombatEventRecord event) {
        ConnectionKey conn = new ConnectionKey(event.connectionId());
        EntityKey entity = event.entityId() < 0 ? null
            : new EntityKey(conn, event.entityId(), parseUuid(event.uuid()));
        Vec position = event.x() == null || event.y() == null || event.z() == null
            ? null : new Vec(event.x(), event.y(), event.z());
        Cell cell = switch (event.kind()) {
            case BLOCK_UPDATE, EXPLOSION -> position == null ? null : cellOf(position);
            default -> null;
        };
        return new Observation(conn, event.receiptSeq(), event.appliedRevision(), event.receiptNanos(),
            (int) event.clientTick(), event.kind(), entity, cell, position, null, payload(event));
    }

    private static ObservationPayload payload(CombatEventRecord event) {
        JsonObject payload = event.payload();
        return switch (event.kind()) {
            case SPAWN -> new ObservationPayload.Spawn(string(payload, "entityType"));
            case MOVE -> new ObservationPayload.Move(
                bool(payload, "hasPosition", true), bool(payload, "hasRotation", false));
            case REMOVED -> new ObservationPayload.Removed(
                string(payload, "removalReason"), bool(payload, "tracking", false));
            case TRACKING_REMOVED -> new ObservationPayload.Removed(
                string(payload, "removalReason"), true);
            case BLOCK_UPDATE -> new ObservationPayload.BlockUpdate(
                string(payload, "oldBlock"), string(payload, "newBlock"), bool(payload, "movingPiston", false));
            case EXPLOSION -> new ObservationPayload.Explosion(number(payload, "radius", 6.0));
            case HEALTH -> new ObservationPayload.Health(
                number(payload, "appliedHealth", number(payload, "health", 0.0)),
                number(payload, "appliedAbsorption", 0.0),
                integer(payload, "appliedFood", integer(payload, "food", 0)),
                number(payload, "appliedSaturation", number(payload, "saturation", 0.0)));
            case ENTITY_DATA -> entityData(payload);
            case EFFECT -> new ObservationPayload.Effect(string(payload, "effect"),
                integer(payload, "amplifier", 0), integer(payload, "duration", 0),
                bool(payload, "removed", false));
            case EQUIPMENT -> new ObservationPayload.Equipment(equipment(payload));
            case DAMAGE_EVENT -> new ObservationPayload.DamageEvent(string(payload, "sourceType"),
                integer(payload, "sourceCauseId", -1), integer(payload, "sourceDirectId", -1));
            case ENTITY_STATUS -> new ObservationPayload.EntityStatus(integer(payload, "eventId", -1));
            case TOTEM_POP -> new ObservationPayload.TotemPop();
            case SETBACK -> new ObservationPayload.Setback();
            case DEATH -> new ObservationPayload.Death();
            case RESPAWN -> new ObservationPayload.Respawn(
                string(payload, "dimension"), bool(payload, "dimensionChanged", false));
            case CONNECTION_CHANGE -> new ObservationPayload.ConnectionChange(string(payload, "event"));
        };
    }

    private static ObservationPayload entityData(JsonObject payload) {
        boolean healthKnown = false;
        boolean absorptionKnown = false;
        JsonElement values = payload.get("values");
        if (values != null && values.isJsonArray()) {
            for (JsonElement value : values.getAsJsonArray()) {
                if (!value.isJsonObject()) continue;
                int id = integer(value.getAsJsonObject(), "id", -1);
                if (id == 9) healthKnown = true;
                if (id == 17) absorptionKnown = true;
            }
        }
        return new ObservationPayload.EntityData(healthKnown, number(payload, "appliedHealth", 0.0),
            absorptionKnown, number(payload, "appliedAbsorption", 0.0), string(payload, "entityType"));
    }

    private static Map<String, String> equipment(JsonObject payload) {
        Map<String, String> slots = new LinkedHashMap<>();
        JsonElement value = payload.get("slots");
        if (value != null && value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                slots.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return slots;
    }

    private static List<Placement> derivePlacements(List<Label> labels, List<CombatEventRecord> events,
                                                    List<Observation> observations) {
        List<Placement> placements = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            Label label = labels.get(i);
            if (PLACE_LABEL.matcher(label.text()).matches()) {
                Integer intendedId = nextCrystalId(labels, i);
                if (intendedId == null) continue;
                Optional<Observation> spawn = firstCrystalSpawn(observations, intendedId);
                Optional<Cell> intent = placementIntentCell(events, label.seq());
                if (spawn.isEmpty() || intent.isEmpty()) continue;
                Box box = Box.fromCell(intent.get());
                int sentTick = Math.min((int) label.tick(), spawn.get().clientTick());
                placements.add(new Placement(spawn.get().conn(), box, box.expand(2.0), sentTick,
                    spawn.get().entity()));
                continue;
            }
            Matcher piston = PISTON_LABEL.matcher(label.text());
            if (piston.find()) {
                int id = Integer.parseInt(piston.group("id"));
                Cell source = new Cell(Integer.parseInt(piston.group("x")),
                    Integer.parseInt(piston.group("y")), Integer.parseInt(piston.group("z")));
                Optional<Observation> spawn = firstCrystalSpawn(observations, id);
                if (spawn.isPresent()) {
                    int sentTick = Math.min((int) label.tick(), spawn.get().clientTick());
                    Box box = Box.fromCell(source);
                    placements.add(new Placement(spawn.get().conn(), box, box.expand(2.0), sentTick,
                        spawn.get().entity()));
                }
            }
        }
        return placements;
    }

    /**
     * The cell the actor targeted when it placed the crystal. The harness emits a generic
     * {@code place-crystal} label, but the recorder still captured the placement's block update: the cell
     * the crystal occupies is set to air. Deriving the box from that intent, rather than from the spawn
     * the hazard is expected to bind, keeps the binding assertion from being self-fulfilling.
     */
    private static Optional<Cell> placementIntentCell(List<CombatEventRecord> events, long labelSeq) {
        return events.stream()
            .filter(event -> event.receiptSeq() > labelSeq)
            .filter(event -> event.kind() == ObservationKind.BLOCK_UPDATE)
            .filter(event -> "minecraft:air".equals(string(event.payload(), "block")))
            .filter(event -> event.x() != null && event.y() != null && event.z() != null)
            .map(event -> cellOf(new Vec(event.x(), event.y(), event.z())))
            .findFirst();
    }

    private static Integer nextCrystalId(List<Label> labels, int from) {
        for (int j = from + 1; j < labels.size(); j++) {
            Matcher matcher = CRYSTAL_ID_LABEL.matcher(labels.get(j).text());
            if (matcher.matches()) return Integer.parseInt(matcher.group("id"));
        }
        return null;
    }

    private static Optional<Observation> firstCrystalSpawn(List<Observation> observations, int entityId) {
        return observations.stream()
            .filter(TraceReplayer::isCrystalSpawn)
            .filter(observation -> observation.entity() != null && observation.entity().id() == entityId)
            .findFirst();
    }

    private static UUID parseUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Cell cellOf(Vec position) {
        return new Cell((int) Math.floor(position.x()), (int) Math.floor(position.y()),
            (int) Math.floor(position.z()));
    }

    private static String string(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static double number(JsonObject json, String name, double fallback) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsDouble();
    }

    private static int integer(JsonObject json, String name, int fallback) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static boolean bool(JsonObject json, String name, boolean fallback) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }
}
