package dev.tntcoating.modules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tntcoating.core.PacketCaptureLog;
import dev.tntcoating.core.PacketPosition;
import dev.tntcoating.mixin.MoveEntityPacketAccessor;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HomePacketLogTest {
    private static final UUID PLAYER = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    @TempDir Path directory;

    @Test void removalRetainsIdentityForALateDestinationPacketWithoutInventingADestination() throws Exception {
        var capture = capture("Alice", true);
        seed(capture, 41, "Alice", new PacketPosition(0, 64, 0));
        capture.receive(new ClientboundEntityPositionSyncPacket(41, values(12.5, 70, -8), false));
        capture.receive(new ClientboundRemoveEntitiesPacket(41));
        capture.receive(new ClientboundTeleportEntityPacket(41, values(2_000_000, 80, -3_000_000), Set.of(), true));

        var events = records(capture);
        assertEquals(List.of("entity_position_sync", "entity_removed", "entity_teleport", "session_end"), kinds(events));
        JsonObject removed = events.get(1);
        assertEquals("unknown", removed.get("destination").getAsString());
        assertEquals(12.5, removed.getAsJsonObject("last_received_position").get("x").getAsDouble());
        JsonObject late = events.get(2);
        assertEquals("Alice", late.get("player").getAsString());
        assertEquals(PLAYER.toString(), late.get("uuid").getAsString());
        assertTrue(late.get("after_removal").getAsBoolean());
        assertEquals(2_000_000, late.getAsJsonObject("absolute_position").get("x").getAsDouble());
        assertEquals(-3_000_000, late.getAsJsonObject("reconstructed_position").get("z").getAsDouble());
        assertEquals(1, events.getFirst().get("sequence").getAsLong());
        assertEquals(4, events.getLast().get("sequence").getAsLong());
    }

    @Test void teleportDoesNotReplaceTheRelativeMovementCodecBaseButAbsoluteSyncDoes() throws Exception {
        var capture = capture("", true);
        seed(capture, 41, "Alice", new PacketPosition(0, 64, 0));
        capture.receive(new ClientboundEntityPositionSyncPacket(41, values(5, 70, -8), false));
        capture.receive(new ClientboundTeleportEntityPacket(41, values(100, 80, 200), Set.of(), true));
        assertEquals(new PacketPosition(100, 80, 200), capture.players.get(41).position);
        assertEquals(new PacketPosition(5, 70, -8), capture.players.get(41).codecBase);

        capture.receive(new TestMovePacket(41, (short) 4096, (short) 0, (short) -4096));
        assertEquals(new PacketPosition(6, 70, -9), capture.players.get(41).position);
        assertEquals(capture.players.get(41).position, capture.players.get(41).codecBase);

        capture.receive(new ClientboundEntityPositionSyncPacket(41, values(-50.125, 90, 30), false));
        capture.receive(new TestMovePacket(41, (short) 4096, (short) 0, (short) 0));
        assertEquals(new PacketPosition(-49.125, 90, 30), capture.players.get(41).position);
        assertEquals(6, records(capture).size());
    }

    @Test void mixedTeleportFlagsPreserveRawValuesAndMarkTheReconstructionAsAnEstimate() throws Exception {
        var capture = capture("", true);
        seed(capture, 41, "Alice", new PacketPosition(10, 64, -20));
        capture.receive(new ClientboundTeleportEntityPacket(41, values(2.5, 100, -3.25),
            Set.of(Relative.X, Relative.Z, Relative.Y_ROT), false));

        var event = records(capture).getFirst();
        assertEquals(2.5, event.getAsJsonObject("position_change").get("x").getAsDouble());
        assertEquals(12.5, event.getAsJsonObject("reconstructed_position").get("x").getAsDouble());
        assertEquals(100, event.getAsJsonObject("reconstructed_position").get("y").getAsDouble());
        assertEquals(-23.25, event.getAsJsonObject("reconstructed_position").get("z").getAsDouble());
        assertEquals("packet_estimate", event.get("position_basis").getAsString());
        assertFalse(event.has("absolute_position"));
        assertEquals(Set.of("X", "Z", "Y_ROT"), event.getAsJsonArray("relative_flags").asList().stream()
            .map(element -> element.getAsString()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void sameDimensionRespawnPreservesOtherPlayersForLaterPositionPackets() throws Exception {
        var capture = capture("", true);
        seed(capture, 41, "Alice", new PacketPosition(10, 64, -20));
        capture.receive(respawn("minecraft:overworld"));
        capture.receive(new ClientboundTeleportEntityPacket(41, values(500, 100, 500), Set.of(), false));

        assertEquals(new PacketPosition(500, 100, 500), capture.players.get(41).position);
        assertEquals("Alice", capture.names.get(PLAYER));
        assertEquals("minecraft:overworld", capture.dimension);
        var events = records(capture);
        assertEquals(List.of("self_respawn", "entity_teleport", "session_end"), kinds(events));
        assertFalse(events.getFirst().get("entities_reset").getAsBoolean());
        assertEquals("minecraft:overworld", events.getFirst().get("next_dimension").getAsString());
    }

    @Test void changedDimensionRespawnClearsOldEntityIdsBeforeAnyLaterPositionPackets() throws Exception {
        var capture = capture("", true);
        seed(capture, 41, "Alice", new PacketPosition(10, 64, -20));
        capture.receive(respawn("minecraft:the_nether"));
        capture.receive(new ClientboundTeleportEntityPacket(41, values(500, 100, 500), Set.of(), false));

        assertTrue(capture.players.isEmpty());
        assertEquals("Alice", capture.names.get(PLAYER));
        assertEquals("minecraft:the_nether", capture.dimension);
        var events = records(capture);
        assertEquals(List.of("world_reset", "session_end"), kinds(events));
        assertTrue(events.getFirst().get("entities_reset").getAsBoolean());
        assertEquals("minecraft:overworld", events.getFirst().get("dimension").getAsString());
        assertEquals("minecraft:the_nether", events.getFirst().get("next_dimension").getAsString());
        assertEquals("minecraft:the_nether", events.getLast().get("dimension").getAsString());
    }

    @Test void suppressingMovementRecordsStillMaintainsCoordinatesForLaterPackets() throws Exception {
        var capture = capture("", false);
        seed(capture, 41, "Alice", new PacketPosition(1.0001, 64, -1.0001));
        capture.receive(new TestMovePacket(41, (short) 1, (short) 0, (short) -1));
        capture.receive(new ClientboundTeleportEntityPacket(41, values(2, 100, 3), Set.of(Relative.X), false));

        var events = records(capture);
        assertEquals(List.of("entity_teleport", "session_end"), kinds(events));
        assertEquals(1.000244140625, events.getFirst().getAsJsonObject("previous_received_position").get("x").getAsDouble());
        assertEquals(3.000244140625, events.getFirst().getAsJsonObject("reconstructed_position").get("x").getAsDouble());
    }

    @Test void selfTeleportAcknowledgementIdsNeverRouteToAnotherPlayerEntity() throws Exception {
        var capture = capture("", true);
        var original = new PacketPosition(10, 64, -20);
        seed(capture, 41, "Alice", original);
        capture.receive(new ClientboundPlayerPositionPacket(41, values(1000, 80, 2000), Set.of()));

        assertEquals(original, capture.players.get(41).position);
        var events = records(capture);
        assertEquals(List.of("self_position", "session_end"), kinds(events));
        assertEquals(41, events.getFirst().get("teleport_id").getAsInt());
        assertFalse(events.getFirst().has("entity_id"));
    }

    @Test void malformedCoordinatesStopOnlyTheRecorderAndLeaveADiagnostic() throws Exception {
        var capture = capture("", true);
        var original = new PacketPosition(10, 64, -20);
        seed(capture, 41, "Alice", original);

        assertDoesNotThrow(() -> capture.receive(new ClientboundEntityPositionSyncPacket(41,
            values(Double.NaN, 80, 2000), false)));
        assertInstanceOf(IllegalArgumentException.class, capture.decodeFailure);
        assertFalse(capture.log.isAccepting());
        assertEquals(original, capture.players.get(41).position);
        assertDoesNotThrow(() -> capture.receive(new ClientboundRemoveEntitiesPacket(41)));
        var events = records(capture);
        assertEquals(List.of("decode_error"), kinds(events));
        assertEquals("ClientboundEntityPositionSyncPacket", events.getFirst().get("packet").getAsString());
        assertTrue(events.getFirst().get("error").getAsString().contains("finite"));
    }

    @Test void malformedRotationStopsTheRecorderWithoutWritingInvalidJson() throws Exception {
        var capture = capture("", true);
        var malformed = new PositionMoveRotation(new Vec3(1, 64, 2), Vec3.ZERO, Float.NaN, 0);

        assertDoesNotThrow(() -> capture.receive(new ClientboundPlayerPositionPacket(7, malformed, Set.of())));
        assertInstanceOf(IllegalArgumentException.class, capture.decodeFailure);
        assertFalse(capture.log.isAccepting());
        var events = records(capture);
        assertEquals(List.of("decode_error"), kinds(events));
        assertEquals("ClientboundPlayerPositionPacket", events.getFirst().get("packet").getAsString());
    }

    @Test void nameAndUuidFiltersMatchTheTargetAndExcludeOtherPlayers() throws Exception {
        for (String filter : List.of("aLiCe", PLAYER.toString().toUpperCase(java.util.Locale.ROOT))) {
            var capture = capture(filter, true);
            seed(capture, 41, "Alice", new PacketPosition(10, 64, -20));
            UUID other = UUID.fromString("00000000-0000-0000-0000-000000000001");
            capture.names.put(other, "Bob");
            capture.players.put(42, new HomePacketLog.PlayerState(other, "Bob", new PacketPosition(0, 64, 0), "packet"));
            capture.receive(new ClientboundEntityPositionSyncPacket(42, values(123, 70, 456), false));
            capture.receive(new ClientboundEntityPositionSyncPacket(41, values(1000, 80, 2000), false));

            var events = records(capture);
            assertEquals(List.of("entity_position_sync", "session_end"), kinds(events));
            assertEquals(41, events.getFirst().get("entity_id").getAsInt());
            assertEquals("Alice", events.getFirst().get("player").getAsString());
        }
    }

    @Test void onlyOwnHomeCommandsAreRecordedAndUnrelatedCommandPayloadsAreOmitted() throws Exception {
        var capture = capture("", true);
        capture.sent(new ServerboundChatCommandPacket("msg Alice private-message"));
        capture.sent(new ServerboundChatCommandPacket("spawn"));
        capture.sent(new ServerboundChatCommandPacket("essentials:home main"));
        capture.sent(new ServerboundChatCommandPacket("homecancel"));

        var events = records(capture);
        assertEquals(List.of("self_home_command", "self_home_command", "session_end"), kinds(events));
        assertEquals("/essentials:home main", events.getFirst().get("command").getAsString());
        assertEquals("/homecancel", events.get(1).get("command").getAsString());
        assertFalse(events.toString().contains("private-message"));
    }

    private HomePacketLog.Capture capture(String filter, boolean relativeMoves) throws Exception {
        return new HomePacketLog.Capture(PacketCaptureLog.open(directory, 1_000_000), null,
            filter, relativeMoves, false, "minecraft:overworld");
    }

    private static void seed(HomePacketLog.Capture capture, int id, String name, PacketPosition position) {
        capture.names.put(PLAYER, name);
        capture.players.put(id, new HomePacketLog.PlayerState(PLAYER, name, position, "packet"));
    }

    private static PositionMoveRotation values(double x, double y, double z) {
        return new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, 0, 0);
    }

    private static ClientboundRespawnPacket respawn(String nextDimension) {
        ResourceKey<Level> dimension = ResourceKey.create(
            ResourceKey.createRegistryKey(Identifier.parse("minecraft:dimension")), Identifier.parse(nextDimension));
        // Dimension type and game modes are not used by the recorder; no registry bootstrap is needed.
        var spawnInfo = new CommonPlayerSpawnInfo(null, dimension, 0, null, null, false, false, Optional.empty(), 0, 63);
        return new ClientboundRespawnPacket(spawnInfo, (byte) 0);
    }

    private static List<JsonObject> records(HomePacketLog.Capture capture) throws Exception {
        capture.stop();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!capture.log.isFinished()) Thread.sleep(1);
        });
        assertNull(capture.log.failure());
        return Files.readAllLines(capture.log.path()).stream()
            .map(line -> JsonParser.parseString(line).getAsJsonObject())
            .filter(event -> !event.get("event").getAsString().equals("summary"))
            .toList();
    }

    private static List<String> kinds(List<JsonObject> events) {
        return events.stream().map(event -> event.get("event").getAsString()).toList();
    }

    /** Unit tests do not apply mixins, so supply the ID accessor on a genuine movement packet. */
    private static final class TestMovePacket extends ClientboundMoveEntityPacket.Pos implements MoveEntityPacketAccessor {
        TestMovePacket(int id, short dx, short dy, short dz) { super(id, dx, dy, dz, false); }
        @Override public int comet$getEntityId() { return entityId; }
    }
}
