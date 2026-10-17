package dev.tntcoating.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.PacketCaptureLog;
import dev.tntcoating.core.PacketPosition;
import dev.tntcoating.mixin.MoveEntityPacketAccessor;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Records decoded packets as received, before world handling or interpolation can hide a position jump. */
public final class HomePacketLog extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<String> player = general.add(new StringSetting.Builder().name("player")
        .description("Exact player name or UUID to record. Blank records all visible players. Takes effect next time you enable the module.")
        .defaultValue("").build());
    private final Setting<Boolean> relativeMoves = general.add(new BoolSetting.Builder().name("relative-moves")
        .description("Include ordinary player movement and rotation. Positions are still tracked when this is off. Takes effect next capture.")
        .defaultValue(true).build());
    private final Setting<Boolean> packetTypes = general.add(new BoolSetting.Builder().name("other-packet-types")
        .description("Also record the type of every incoming packet. Other payloads are omitted. Takes effect next capture.")
        .defaultValue(false).build());
    private final Setting<Integer> maxSize = general.add(new IntSetting.Builder().name("max-file-mib")
        .description("Stop this capture at this file size. Each activation creates a new file. Takes effect next capture.")
        .defaultValue(64).range(1, 1024).sliderRange(1, 128).build());

    private volatile Capture capture;

    public HomePacketLog() {
        super(TntCoatingAddon.CATEGORY, "home-packet-log", "Records player position and disappearance packets to a local file for teleport testing.");
    }

    @Override public void onActivate() {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            error("Join a world before starting a capture.");
            toggle();
            return;
        }
        PacketCaptureLog log = null;
        try {
            log = PacketCaptureLog.open(MeteorClient.FOLDER.toPath().resolve("comet/packet-logs"), maxSize.get() * 1024L * 1024L);
            Capture next = new Capture(log, mc.getConnection().getConnection(), player.get().trim(), relativeMoves.get(), packetTypes.get(),
                mc.level.dimension().identifier().toString());
            JsonObject header = next.event("session_start", "local", null);
            header.addProperty("format_version", 1);
            header.addProperty("minecraft", "26.2");
            header.addProperty("player_filter", next.filter);
            header.addProperty("relative_moves", next.relativeMoves);
            header.addProperty("other_packet_types", next.packetTypes);
            header.addProperty("note", "Decoded client-visible packets, not raw wire bytes. Removal does not establish a teleport or reveal its destination.");
            log.append(header);
            // Only activation reads the world. Receive handlers use their own packet-derived state.
            for (var info : mc.getConnection().getOnlinePlayers()) next.names.put(info.getProfile().id(), info.getProfile().name());
            for (var visible : mc.level.players()) {
                var state = new PlayerState(visible.getUUID(), visible.getGameProfile().name(), position(visible.position()), "client_snapshot");
                state.codecBase = position(visible.getPositionCodec().getBase());
                next.players.put(visible.getId(), state);
                JsonObject snapshot = next.playerEvent("player_snapshot", null, visible.getId(), state);
                if (snapshot != null) {
                    snapshot.addProperty("direction", "local");
                    snapshot.add("position", json(state.position));
                    snapshot.add("relative_move_base", json(state.codecBase));
                    snapshot.addProperty("position_basis", state.basis);
                    log.append(snapshot);
                }
            }
            capture = next;
            info("Recording to %s", log.path().toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            if (log != null) log.close();
            error("Could not start packet capture: %s", e.getMessage());
            toggle();
        }
    }

    @Override public void onDeactivate() {
        Capture previous = capture;
        capture = null;
        if (previous != null) {
            previous.stop();
            info("Packet log: %s", previous.log.path().toAbsolutePath());
            if (previous.log.droppedRecords() > 0) warning("%d log records were dropped; check the summary before interpreting this capture.", previous.log.droppedRecords());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST) private void onReceive(PacketEvent.Receive event) {
        Capture active = capture;
        if (active != null && event.connection == active.connection) active.receive(event.packet);
    }

    @EventHandler private void onSent(PacketEvent.Sent event) {
        Capture active = capture;
        if (active != null && event.connection == active.connection) active.sent(event.packet);
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        Capture active = capture;
        if (active == null || active.log.isAccepting()) return;
        IOException failure = active.log.failure();
        if (active.decodeFailure != null) error("Packet capture stopped while decoding a packet: %s", active.decodeFailure.getMessage());
        else if (failure != null) error("Packet capture stopped: %s", failure.getMessage());
        else info("Packet capture reached its file limit.");
        toggle();
    }

    @EventHandler(priority = EventPriority.HIGHEST) private void onGameLeft(GameLeftEvent event) {
        // Run before Meteor unsubscribes normal module handlers; the old connection must never enter a new capture.
        if (isActive()) toggle();
    }

    @Override public String getInfoString() {
        Capture active = capture;
        return active == null ? null : active.log.writtenRecords() + " records";
    }

    static final class PlayerState {
        final UUID uuid;
        String name;
        PacketPosition position;
        PacketPosition codecBase;
        String basis;
        String codecBasis;
        boolean removed;

        PlayerState(UUID uuid, String name, PacketPosition position, String basis) {
            this.uuid = uuid;
            this.name = name;
            this.position = position;
            this.codecBase = position;
            this.basis = basis;
            this.codecBasis = basis;
        }
    }

    /** Lock packet order against sent events and stop; never touch the live client world in these methods. */
    static final class Capture {
        final PacketCaptureLog log;
        final Connection connection;
        final String filter;
        final boolean relativeMoves, packetTypes;
        final long started = System.nanoTime();
        final Map<UUID, String> names = boundedMap(8192);
        // Retain removed players so a late final position packet still has its name and UUID. New spawns replace reused IDs.
        final Map<Integer, PlayerState> players = boundedMap(4096);
        String dimension;
        long sequence;
        boolean stopped;
        volatile RuntimeException decodeFailure;

        Capture(PacketCaptureLog log, Connection connection, String filter, boolean relativeMoves, boolean packetTypes, String dimension) {
            this.log = log;
            this.connection = connection;
            this.filter = filter;
            this.relativeMoves = relativeMoves;
            this.packetTypes = packetTypes;
            this.dimension = dimension;
        }

        JsonObject event(String kind, String direction, Packet<?> packet) {
            JsonObject event = new JsonObject();
            event.addProperty("sequence", ++sequence);
            event.addProperty("time_ms", System.currentTimeMillis());
            event.addProperty("elapsed_ns", System.nanoTime() - started);
            event.addProperty("event", kind);
            event.addProperty("direction", direction);
            event.addProperty("dimension", dimension);
            if (packet != null) {
                event.addProperty("packet", packet.getClass().getSimpleName());
                event.addProperty("packet_type", packet.type().id().toString());
            }
            return event;
        }

        JsonObject playerEvent(String kind, Packet<?> packet, int id, PlayerState state) {
            String currentName = names.get(state.uuid);
            if (currentName != null) state.name = currentName;
            if (!filter.isEmpty() && !filter.equalsIgnoreCase(state.name) && !filter.equalsIgnoreCase(state.uuid.toString())) return null;
            JsonObject event = event(kind, "in", packet);
            event.addProperty("entity_id", id);
            event.addProperty("uuid", state.uuid.toString());
            if (state.name != null) event.addProperty("player", state.name);
            event.addProperty("after_removal", state.removed);
            return event;
        }

        synchronized void receive(Packet<?> packet) {
            if (stopped || !log.isAccepting()) return;
            try {
                receivePacket(packet);
            } catch (RuntimeException error) {
                decodeFailed(packet, error);
            }
        }

        private void receivePacket(Packet<?> packet) {
            // Meteor already unwraps bundles and calls Receive for each child in order.
            if (packetTypes) log.append(event("packet_type", "in", packet));
            if (packet instanceof ClientboundPlayerInfoUpdatePacket info) {
                for (var entry : info.newEntries()) {
                    if (entry.profile() != null) names.put(entry.profileId(), entry.profile().name());
                }
            } else if (packet instanceof ClientboundAddEntityPacket add) {
                players.remove(add.getId());
                if (add.getType() != EntityTypes.PLAYER) return;
                PlayerState state = new PlayerState(add.getUUID(), names.get(add.getUUID()), new PacketPosition(add.getX(), add.getY(), add.getZ()), "packet");
                players.put(add.getId(), state);
                JsonObject event = playerEvent("player_spawn", packet, add.getId(), state);
                if (event != null) {
                    event.add("position", json(state.position));
                    event.addProperty("yaw", add.getYRot());
                    event.addProperty("pitch", add.getXRot());
                    event.add("velocity", json(position(add.getMovement())));
                    log.append(event);
                }
            } else if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
                update(packet, sync.id(), sync.values(), Set.of(), sync.onGround(), "entity_position_sync", true);
            } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
                update(packet, teleport.id(), teleport.change(), teleport.relatives(), teleport.onGround(), "entity_teleport", false);
            } else if (packet instanceof ClientboundMoveEntityPacket move) {
                int id = ((MoveEntityPacketAccessor) move).comet$getEntityId();
                PlayerState state = players.get(id);
                if (state == null) return;
                if (move.hasPosition()) {
                    state.codecBase = state.codecBase.relativeMove(move.getXa(), move.getYa(), move.getZa());
                    state.position = state.codecBase;
                    state.basis = state.codecBasis;
                }
                if (!relativeMoves) return;
                JsonObject event = playerEvent("entity_relative_move", packet, id, state);
                if (event != null) {
                    event.addProperty("has_position", move.hasPosition());
                    event.addProperty("has_rotation", move.hasRotation());
                    event.addProperty("dx_4096", move.getXa());
                    event.addProperty("dy_4096", move.getYa());
                    event.addProperty("dz_4096", move.getZa());
                    event.addProperty("on_ground", move.isOnGround());
                    if (move.hasRotation()) {
                        event.addProperty("yaw", move.getYRot());
                        event.addProperty("pitch", move.getXRot());
                    }
                    event.add("reconstructed_position", json(state.position));
                    event.addProperty("position_basis", state.basis);
                    log.append(event);
                }
            } else if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
                for (int id : remove.getEntityIds()) {
                    PlayerState state = players.get(id);
                    if (state == null) continue;
                    JsonObject event = playerEvent("entity_removed", packet, id, state);
                    state.removed = true;
                    if (event != null) {
                        event.add("last_received_position", json(state.position));
                        event.addProperty("position_basis", state.basis);
                        event.addProperty("destination", "unknown");
                        log.append(event);
                    }
                }
            } else if (packet instanceof ClientboundPlayerInfoRemovePacket remove) {
                for (UUID uuid : remove.profileIds()) {
                    String name = names.remove(uuid);
                    if (!filter.isEmpty() && !filter.equalsIgnoreCase(name) && !filter.equalsIgnoreCase(uuid.toString())) continue;
                    JsonObject event = event("player_tab_removed", "in", packet);
                    event.addProperty("uuid", uuid.toString());
                    if (name != null) event.addProperty("player", name);
                    log.append(event);
                }
            } else if (packet instanceof ClientboundPlayerPositionPacket own) {
                JsonObject event = event("self_position", "in", packet);
                event.addProperty("teleport_id", own.id());
                change(event, own.change(), own.relatives());
                event.addProperty("note", "This packet moves the recording account. teleport_id is an acknowledgement ID, not an entity ID.");
                log.append(event);
            } else if (packet instanceof ClientboundRespawnPacket respawn) {
                resetDimension(packet, respawn.commonPlayerSpawnInfo().dimension().identifier().toString());
            } else if (packet instanceof ClientboundLoginPacket login) {
                names.clear();
                resetDimension(packet, login.commonPlayerSpawnInfo().dimension().identifier().toString());
            }
        }

        private void update(Packet<?> packet, int id, PositionMoveRotation change, Set<Relative> relatives, boolean onGround, String kind, boolean syncCodec) {
            PlayerState state = players.get(id);
            if (state == null) return;
            PacketPosition before = state.position;
            state.position = before.teleport(position(change.position()), relatives.contains(Relative.X), relatives.contains(Relative.Y), relatives.contains(Relative.Z));
            // Vanilla resolves relative teleports against the current interpolated client position.
            // A packet-only reconstruction is an estimate; always retain the original fields/flags.
            state.basis = relativePosition(relatives) ? "packet_estimate" : "packet";
            // TeleportEntity does not reset VecDeltaCodec in 26.2. PositionSync does.
            if (syncCodec) {
                state.codecBase = state.position;
                state.codecBasis = "packet";
            }
            JsonObject event = playerEvent(kind, packet, id, state);
            if (event == null) return;
            change(event, change, relatives);
            event.add("previous_received_position", json(before));
            event.add("reconstructed_position", json(state.position));
            event.addProperty("position_basis", state.basis);
            event.addProperty("on_ground", onGround);
            log.append(event);
        }

        private void resetDimension(Packet<?> packet, String next) {
            // A same-dimension respawn preserves ClientLevel and its other players in vanilla.
            boolean reset = packet instanceof ClientboundLoginPacket || !dimension.equals(next);
            JsonObject event = event(reset ? "world_reset" : "self_respawn", "in", packet);
            event.addProperty("next_dimension", next);
            event.addProperty("entities_reset", reset);
            log.append(event);
            if (reset) players.clear();
            dimension = next;
        }

        synchronized void sent(Packet<?> packet) {
            if (stopped || !log.isAccepting()) return;
            try {
                sentPacket(packet);
            } catch (RuntimeException error) {
                decodeFailed(packet, error);
            }
        }

        private void sentPacket(Packet<?> packet) {
            if (packet instanceof ServerboundAcceptTeleportationPacket accept) {
                JsonObject event = event("self_teleport_ack", "out", packet);
                event.addProperty("teleport_id", accept.getId());
                log.append(event);
                return;
            }
            String command;
            if (packet instanceof ServerboundChatCommandPacket plain) command = plain.command();
            else if (packet instanceof ServerboundChatCommandSignedPacket signed) command = signed.command();
            else return;
            String verb = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            verb = verb.substring(verb.lastIndexOf(':') + 1);
            if (!verb.equals("home") && !verb.equals("homecancel")) return;
            JsonObject event = event("self_home_command", "out", packet);
            event.addProperty("command", "/" + command);
            log.append(event);
        }

        synchronized void stop() {
            if (stopped) return;
            stopped = true;
            if (log.isAccepting()) log.append(event("session_end", "local", null));
            log.close();
        }

        private void decodeFailed(Packet<?> packet, RuntimeException error) {
            decodeFailure = error;
            stopped = true;
            JsonObject event = event("decode_error", "local", null);
            event.addProperty("packet", packet.getClass().getSimpleName());
            event.addProperty("error", error.toString());
            log.append(event);
            log.close();
        }
    }

    private static void change(JsonObject event, PositionMoveRotation change, Set<Relative> relatives) {
        if (!Float.isFinite(change.yRot()) || !Float.isFinite(change.xRot())) throw new IllegalArgumentException("Packet rotation must be finite");
        event.add("position_change", json(position(change.position())));
        event.add("velocity_change", json(position(change.deltaMovement())));
        event.addProperty("yaw_change", change.yRot());
        event.addProperty("pitch_change", change.xRot());
        JsonArray flags = new JsonArray();
        relatives.stream().sorted().forEach(flag -> flags.add(flag.name()));
        event.add("relative_flags", flags);
        if (!relativePosition(relatives)) event.add("absolute_position", json(position(change.position())));
    }

    private static boolean relativePosition(Set<Relative> flags) {
        return flags.contains(Relative.X) || flags.contains(Relative.Y) || flags.contains(Relative.Z);
    }

    private static PacketPosition position(Vec3 position) { return new PacketPosition(position.x, position.y, position.z); }

    private static JsonObject json(PacketPosition position) {
        JsonObject object = new JsonObject();
        object.addProperty("x", position.x());
        object.addProperty("y", position.y());
        object.addProperty("z", position.z());
        return object;
    }

    private static <K, V> Map<K, V> boundedMap(int limit) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > limit; }
        };
    }
}
