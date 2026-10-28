package dev.tntcoating.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/** Durable trip identity, written when flight starts and retained until the trip finishes. */
public final class LobbyRecoveryStore {
    private static final int MAX_VALUE_LENGTH = 256;
    private static final int MAX_FILE_BYTES = 4096;
    private static final int WORLD_BOUND = 30_000_000;
    // BlockPos stores Y in 12 signed bits; allow custom dimensions as well as vanilla heights.
    private static final int MIN_Y = -2048;
    private static final int MAX_Y = 2047;

    /** Saved owner of a native fixed-heading boat trip (the Auto Boat Fly module name). */
    public static final String NATIVE_BOAT = "auto-boat-fly";
    /** Owner name written before Boat Fly was split into manual and automatic modules. */
    static final String LEGACY_NATIVE_BOAT = "boat-fly";

    private final Path file;

    public LobbyRecoveryStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** A pending trip and the server it belongs to (null means singleplayer). */
    public record Recovery(String dimension, String server, BlockPos destination, Integer targetY, Float boatYaw, String boatModule) {
        public Recovery(String dimension, String server) {
            this(dimension, server, null, null, null, null);
        }

        public Recovery(String dimension, String server, BlockPos destination, Integer targetY) {
            this(dimension, server, destination, targetY, null, null);
        }

        public Recovery(String dimension, String server, BlockPos destination, Integer targetY, Float boatYaw) {
            this(dimension, server, destination, targetY, boatYaw, boatYaw == null ? null : "boat-phase");
        }

        public Recovery {
            if (LEGACY_NATIVE_BOAT.equals(boatModule)) boatModule = NATIVE_BOAT;
            if (boatYaw == null && boatModule != null) throw new IllegalArgumentException("boat owner without heading");
            if (boatYaw != null && !NATIVE_BOAT.equals(boatModule) && !"boat-phase".equals(boatModule))
                throw new IllegalArgumentException("unknown boat controller");
            if (boatYaw != null && (!Float.isFinite(boatYaw) || destination != null))
                throw new IllegalArgumentException("invalid boat trip");
            validateRoute(destination, targetY);
            if (destination != null) destination = destination.immutable();
        }

        public boolean isForServer(String currentServer) {
            if (server == null) return currentServer == null;
            return currentServer != null && server.equalsIgnoreCase(currentServer);
        }
    }

    /** Returns the pending trip, or empty if there is none for this marker file. */
    public Optional<Recovery> load() {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("oversized marker");
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                .getAsJsonObject();
            String dimension = stringValue(root, "dimension");
            if (dimension == null || !valid(dimension)) {
                clearQuietly();
                return Optional.empty();
            }

            String server = null;
            if (root.has("server")) {
                server = stringValue(root, "server");
                if (server == null || !valid(server)) {
                    clearQuietly();
                    return Optional.empty();
                }
            }
            BlockPos destination = null;
            Integer targetY = null;
            if (root.has("destination") || root.has("targetY")) {
                JsonObject position = root.getAsJsonObject("destination");
                destination = new BlockPos(integerValue(position, "x"), integerValue(position, "y"),
                    integerValue(position, "z"));
                targetY = integerValue(root, "targetY");
            }
            Float boatYaw = root.has("boatYaw") ? root.get("boatYaw").getAsFloat() : null;
            String boatModule = boatYaw == null ? null : root.has("boatModule") ? stringValue(root, "boatModule") : "boat-phase";
            return Optional.of(new Recovery(dimension, server, destination, targetY, boatYaw, boatModule));
        } catch (IOException e) {
            // A temporary read failure must not erase the only durable copy of a trip.
            return Optional.empty();
        } catch (RuntimeException e) {
            clearQuietly();
            return Optional.empty();
        }
    }

    public void save(String dimension, String server) throws IOException {
        save(dimension, server, null, null);
    }

    public void save(String dimension, String server, BlockPos destination, Integer targetY) throws IOException {
        save(dimension, server, destination, targetY, null, null);
    }

    public void saveBoat(String dimension, String server, float yaw) throws IOException {
        saveBoat(dimension, server, yaw, "boat-phase");
    }

    public void saveBoat(String dimension, String server, float yaw, String module) throws IOException {
        new Recovery(dimension, server, null, null, yaw, module);
        save(dimension, server, null, null, yaw, module);
    }

    private void save(String dimension, String server, BlockPos destination, Integer targetY, Float boatYaw, String boatModule) throws IOException {
        validateRoute(destination, targetY);
        Objects.requireNonNull(dimension, "dimension");
        String trimmedDimension = dimension.trim();
        if (!valid(trimmedDimension)) throw new IllegalArgumentException("invalid dimension");

        String trimmedServer = server == null ? null : server.trim();
        if (trimmedServer != null && !valid(trimmedServer)) throw new IllegalArgumentException("invalid server");

        JsonObject root = new JsonObject();
        root.addProperty("dimension", trimmedDimension);
        if (trimmedServer != null) root.addProperty("server", trimmedServer);
        if (destination != null) {
            JsonObject position = new JsonObject();
            position.addProperty("x", destination.getX());
            position.addProperty("y", destination.getY());
            position.addProperty("z", destination.getZ());
            root.add("destination", position);
            root.addProperty("targetY", targetY);
        }
        if (boatYaw != null) {
            root.addProperty("boatYaw", boatYaw);
            root.addProperty("boatModule", boatModule);
        }
        root.addProperty("savedAt", System.currentTimeMillis());
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) output.write(data);
                output.force(true);
            }
            Files.move(temporary, file,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                Files.deleteIfExists(temporary);
                throw fallbackFailure;
            }
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    private static void validateRoute(BlockPos destination, Integer targetY) {
        if ((destination == null) != (targetY == null)) {
            throw new IllegalArgumentException("destination and targetY must both be present");
        }
        if (destination == null) return;
        if (destination.getX() < -WORLD_BOUND || destination.getX() > WORLD_BOUND
            || destination.getZ() < -WORLD_BOUND || destination.getZ() > WORLD_BOUND
            || destination.getY() < MIN_Y || destination.getY() > MAX_Y
            || targetY < MIN_Y || targetY > MAX_Y) {
            throw new IllegalArgumentException("route coordinates out of range");
        }
    }

    private static int integerValue(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("invalid route coordinate: " + key);
        }
        return element.getAsBigDecimal().intValueExact();
    }

    private static String stringValue(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString().trim();
    }

    private static boolean valid(String value) {
        return !value.isEmpty() && value.length() <= MAX_VALUE_LENGTH
            && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }

    private void clearQuietly() {
        try {
            clear();
        } catch (IOException ignored) {
        }
    }
}
