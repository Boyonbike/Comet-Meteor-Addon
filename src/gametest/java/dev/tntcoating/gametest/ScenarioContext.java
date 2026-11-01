/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * client and server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import dev.tntcoating.core.combat.mc.CombatRecorderHost;
import dev.tntcoating.gametest.server.FakePlayers;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.Properties;

/** Per-run context shared by the scenario scripts. */
public final class ScenarioContext {
    public final ClientGameTestContext client;
    public final TestDedicatedServerContext server;
    private TestDedicatedServerConnection connection;
    public final Path runDirectory;
    public final int delayMs;
    public final int jitterMs;
    public final int tps;
    public final int repeat;
    public final long seed;
    public final CombatRecorderHost trace;

    private ServerPlayer target;

    public ScenarioContext(ClientGameTestContext client, TestDedicatedServerContext server,
                           TestDedicatedServerConnection connection,
                           Path runDirectory, int delayMs, int jitterMs, int tps, int repeat,
                           long seed, CombatRecorderHost trace) {
        this.client = client;
        this.server = server;
        this.connection = connection;
        this.runDirectory = runDirectory;
        this.delayMs = delayMs;
        this.jitterMs = jitterMs;
        this.tps = tps;
        this.repeat = repeat;
        this.seed = seed;
        this.trace = trace;
    }

    /** Closes and recreates the client's server connection while the server keeps running. */
    public void reconnect() {
        try {
            connection.close();
        } catch (RuntimeException ignored) {
        }
        connection = server.connect();
        LatencyHandler.install(client, delayMs, jitterMs, seed ^ 0x5EEDL);
        client.waitTicks(20);
    }

    public void command(String command) {
        server.runCommand(command);
    }

    public void waitTicks(int ticks) {
        client.waitTicks(ticks);
    }

    public void label(String text) {
        trace.label(text);
    }

    public ServerPlayer spawnTarget(String name, double x, double y, double z) {
        target = server.computeOnServer(minecraft -> {
            ServerLevel level = minecraft.getAllLevels().iterator().next();
            return FakePlayers.spawn(minecraft, level, name, x, y, z);
        });
        return target;
    }

    public ServerPlayer target() {
        return target;
    }

    public void removeTarget() {
        if (target == null) return;
        ServerPlayer player = target;
        server.runOnServer(minecraft -> FakePlayers.remove(player));
        target = null;
    }

    public ServerPlayer spawnPlayer(String name, double x, double y, double z) {
        return server.computeOnServer(minecraft -> {
            ServerLevel level = minecraft.getAllLevels().iterator().next();
            return FakePlayers.spawn(minecraft, level, name, x, y, z);
        });
    }

    public void removePlayer(ServerPlayer player) {
        if (player == null) return;
        server.runOnServer(minecraft -> FakePlayers.remove(player));
    }

    public int spawnCrystal(double x, double y, double z) {
        return server.computeOnServer(minecraft -> {
            ServerLevel level = minecraft.getAllLevels().iterator().next();
            EndCrystal crystal = new EndCrystal(level, x, y, z);
            level.addFreshEntity(crystal);
            return crystal.getId();
        });
    }

    public void detonateCrystal(int entityId) {
        server.runOnServer(minecraft -> {
            ServerLevel level = minecraft.getAllLevels().iterator().next();
            Entity entity = level.getEntity(entityId);
            if (entity instanceof EndCrystal crystal) {
                crystal.hurtServer(level, level.damageSources().magic(), 1.0f);
            }
        });
    }

    public void giveTotem(ServerPlayer player) {
        server.runOnServer(minecraft ->
            player.getInventory().setItem(Inventory.SLOT_OFFHAND,
                new ItemStack(Items.TOTEM_OF_UNDYING)));
    }

    public int findCrystalNear(double x, double y, double z, double radius) {
        return server.computeOnServer(minecraft -> {
            ServerLevel level = minecraft.getAllLevels().iterator().next();
            double best = radius * radius;
            int id = -1;
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof EndCrystal)) continue;
                double distance = entity.position().distanceToSqr(x, y, z);
                if (distance <= best) {
                    best = distance;
                    id = entity.getId();
                }
            }
            return id;
        });
    }

    public int waitForCrystal(BlockPos base, double radius, int timeoutTicks) {
        long deadline = System.nanoTime() + timeoutTicks * 50_000_000L;
        while (System.nanoTime() < deadline) {
            int id = findCrystalNear(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5, radius);
            if (id != -1) {
                ClientActions.waitForClientEntity(client, id);
                return id;
            }
            client.waitTicks(1);
        }
        return -1;
    }

    public void setTargetHealth(float health) {
        ServerPlayer player = target;
        server.runOnServer(minecraft -> {
            ServerLevel level = (ServerLevel) player.level();
            player.setHealth(health);
            player.setAbsorptionAmount(0.0f);
        });
    }

    public void setClientAbsorption(float amount) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayerByName("HarnessClient");
            if (player == null) return;
            player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION)
                .setBaseValue(Math.max(16.0, amount));
            player.setAbsorptionAmount(amount);
        });
    }

    public void damageClient(float amount) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayerByName("HarnessClient");
            if (player == null) return;
            ServerLevel level = (ServerLevel) player.level();
            player.hurtServer(level, level.damageSources().magic(), amount);
        });
    }

    public void setClientMaxHealth(float maxHealth, float health) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayerByName("HarnessClient");
            if (player == null) return;
            player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(maxHealth);
            player.setHealth(health);
        });
    }

    public void setTargetMaxHealth(float maxHealth, float health) {
        ServerPlayer player = target;
        server.runOnServer(minecraft -> {
            player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(maxHealth);
            player.setHealth(health);
        });
    }

    public void setPlayerMaxHealth(ServerPlayer player, float maxHealth, float health) {
        server.runOnServer(minecraft -> {
            player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(maxHealth);
            player.setHealth(health);
        });
    }

    public void killAndRespawnClient() {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayerByName("HarnessClient");
            if (player == null) return;
            ServerLevel level = (ServerLevel) player.level();
            player.hurtServer(level, level.damageSources().magic(), 1000.0f);
        });
        client.waitTicks(3);
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayerByName("HarnessClient");
            if (player != null) {
                minecraft.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            }
        });
    }

    public void setTargetKnockbackResistance(double value) {
        ServerPlayer player = target;
        server.runOnServer(minecraft ->
            player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE)
                .setBaseValue(value));
    }

    public void setTargetExplosionKnockbackResistance(double value) {
        ServerPlayer player = target;
        server.runOnServer(minecraft ->
            player.getAttribute(
                    net.minecraft.world.entity.ai.attributes.Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)
                .setBaseValue(value));
    }

    public void setClientExplosionKnockbackResistance(double value) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayerByName("HarnessClient");
            if (player == null) return;
            player.getAttribute(
                    net.minecraft.world.entity.ai.attributes.Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)
                .setBaseValue(value);
        });
    }

    public void setTargetAbsorption(float amount) {
        ServerPlayer player = target;
        server.runOnServer(minecraft -> {
            player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION)
                .setBaseValue(Math.max(16.0, amount));
            player.setAbsorptionAmount(amount);
        });
    }

    public void damageTarget(float amount) {
        ServerPlayer player = target;
        server.runOnServer(minecraft -> {
            ServerLevel level = (ServerLevel) player.level();
            player.hurtServer(level, level.damageSources().magic(), amount);
        });
    }

    public void damageTargetFromPlayer(float amount) {
        ServerPlayer player = target;
        server.runOnServer(minecraft -> {
            ServerPlayer attacker = minecraft.getPlayerList().getPlayerByName("HarnessClient");
            ServerLevel level = (ServerLevel) player.level();
            player.hurtServer(level, level.damageSources().playerAttack(attacker), amount);
        });
    }

    public void resetTargetMotion() {
        ServerPlayer player = target;
        server.runOnServer(minecraft -> {
            player.teleportTo(4.5, -60.0, 0.5);
            player.setDeltaMovement(Vec3.ZERO);
        });
    }

    public void teleportClient(double x, double y, double z) {
        command("tp HarnessClient " + x + " " + y + " " + z);
        client.waitTicks(5);
    }

    public void setClientHealth(float health) {
        server.runOnServer(minecraft -> {
            for (ServerPlayer player : minecraft.getPlayerList().getPlayers()) {
                if (player.getName().getString().equals("HarnessClient")) {
                    player.setHealth(health);
                    return;
                }
            }
        });
    }

    public Vec3 targetPosition() {
        return server.computeOnServer(minecraft -> target == null ? Vec3.ZERO : target.position());
    }

    public static Properties serverProperties(long seed, int viewDistance) {
        Properties properties = new Properties();
        properties.setProperty("max-players", "4");
        properties.setProperty("online-mode", "false");
        properties.setProperty("spawn-protection", "0");
        properties.setProperty("sync-chunk-writes", "false");
        properties.setProperty("view-distance", Integer.toString(viewDistance));
        properties.setProperty("simulation-distance", Integer.toString(viewDistance));
        properties.setProperty("level-seed", Long.toString(seed));
        properties.setProperty("gamemode", "survival");
        properties.setProperty("difficulty", "normal");
        properties.setProperty("allow-nether", "true");
        properties.setProperty("enable-command-block", "false");
        return properties;
    }
}
