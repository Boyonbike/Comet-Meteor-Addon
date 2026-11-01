/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Test-only server-side ground truth. The writer is opened on the first server tick
 * and is driven only by the harness mixins in this source set. It never changes world
 * state and never sends packets.
 */
public final class ServerGroundTruth {
    private static final Gson GSON = new Gson();
    private static BufferedWriter writer;
    private static Path path;
    private static MinecraftServer server;
    private static boolean failed;

    private ServerGroundTruth() {
    }

    public static synchronized Path path() {
        return path;
    }

    public static synchronized void event(String type, Consumer<JsonObject> fields) {
        if (!ensureOpen()) return;
        JsonObject line = base(type);
        if (fields != null) fields.accept(line);
        write(line);
    }

    public static synchronized void tickStart(MinecraftServer minecraftServer) {
        server = minecraftServer;
        if (!ensureOpen()) return;
        JsonObject line = base("server_tick_start");
        line.addProperty("tickRate", minecraftServer.tickRateManager().tickrate());
        write(line);
    }

    public static synchronized void tickEnd(MinecraftServer minecraftServer) {
        server = minecraftServer;
        if (!ensureOpen()) return;
        JsonObject line = base("server_tick_end");
        line.addProperty("tickRate", minecraftServer.tickRateManager().tickrate());

        JsonArray players = new JsonArray();
        List<ServerPlayer> allPlayers = new ArrayList<>();
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            allPlayers.addAll(level.players());
        }
        allPlayers.sort(Comparator.comparingInt(Entity::getId));
        for (ServerPlayer player : allPlayers) {
            JsonObject p = new JsonObject();
            p.addProperty("id", player.getId());
            p.addProperty("uuid", player.getUUID().toString());
            p.addProperty("name", player.getGameProfile().name());
            p.addProperty("dimension", player.level().dimension().identifier().toString());
            p.addProperty("health", player.getHealth());
            p.addProperty("absorption", player.getAbsorptionAmount());
            p.addProperty("onGround", player.onGround());
            p.addProperty("x", player.getX());
            p.addProperty("y", player.getY());
            p.addProperty("z", player.getZ());
            Vec3 movement = player.getDeltaMovement();
            p.addProperty("dx", movement.x);
            p.addProperty("dy", movement.y);
            p.addProperty("dz", movement.z);
            players.add(p);
        }
        line.add("players", players);

        JsonArray crystals = new JsonArray();
        List<EndCrystal> allCrystals = new ArrayList<>();
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EndCrystal crystal) allCrystals.add(crystal);
            }
        }
        allCrystals.sort(Comparator.comparingInt(Entity::getId));
        for (EndCrystal crystal : allCrystals) {
            JsonObject c = new JsonObject();
            c.addProperty("id", crystal.getId());
            c.addProperty("uuid", crystal.getUUID().toString());
            c.addProperty("dimension", crystal.level().dimension().identifier().toString());
            c.addProperty("x", crystal.getX());
            c.addProperty("y", crystal.getY());
            c.addProperty("z", crystal.getZ());
            c.addProperty("removed", crystal.isRemoved());
            crystals.add(c);
        }
        line.add("crystals", crystals);
        write(line);
    }

    public static synchronized void close() {
        if (writer == null) return;
        try {
            write(base("server_stop"));
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
            // The harness must not fail a scenario because diagnostic output failed.
        } finally {
            writer = null;
        }
    }

    private static JsonObject base(String type) {
        JsonObject line = new JsonObject();
        line.addProperty("type", type);
        line.addProperty("t", System.nanoTime());
        line.addProperty("tick", server == null ? -1 : server.getTickCount());
        return line;
    }

    private static boolean ensureOpen() {
        if (writer != null) return true;
        if (failed) return false;
        String output = System.getProperty("comet.harness.output");
        if (output == null || output.isBlank()) return false;
        try {
            Path directory = Path.of(output);
            Files.createDirectories(directory);
            path = directory.resolve("server.jsonl");
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            JsonObject meta = base("server_meta");
            meta.addProperty("scenario", System.getProperty("comet.harness.scenario", "unknown"));
            meta.addProperty("run", System.getProperty("comet.harness.run", "unknown"));
            meta.addProperty("delayMs", System.getProperty("comet.harness.delay", "0"));
            meta.addProperty("jitterMs", System.getProperty("comet.harness.jitter", "0"));
            meta.addProperty("tps", System.getProperty("comet.harness.tps", "20"));
            meta.addProperty("seed", System.getProperty("comet.harness.seed", "0"));
            write(meta);
            return true;
        } catch (IOException | RuntimeException error) {
            failed = true;
            writer = null;
            return false;
        }
    }

    private static void write(JsonObject line) {
        if (writer == null) return;
        try {
            writer.write(GSON.toJson(line));
            writer.newLine();
            writer.flush();
        } catch (IOException error) {
            failed = true;
            try {
                writer.close();
            } catch (IOException ignored) {
            } finally {
                writer = null;
            }
        }
    }
}
