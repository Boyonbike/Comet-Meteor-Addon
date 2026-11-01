/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * server classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.world.level.GameType;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Test-only factory for the scripted server-side target and friend. */
public final class FakePlayers {
    private FakePlayers() {
    }

    public static ServerPlayer spawn(MinecraftServer server, ServerLevel level, String name,
                                     double x, double y, double z) {
        UUID uuid = UUID.nameUUIDFromBytes(("comet-harness:" + name).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, name);
        ClientInformation information = ClientInformation.createDefault();
        FakeServerPlayer player = new FakeServerPlayer(server, level, profile, information);
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(x, y, z);
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0.0f);
        server.getPlayerList().placeNewPlayer(new NullConnection(), player,
            CommonListenerCookie.createInitial(profile, false));
        // A network client sends this after its chunks load; the dummy has no network client.
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0.0f);
        ServerGroundTruth.event("fake_player_spawn", line -> {
            line.addProperty("entity", player.getId());
            line.addProperty("uuid", player.getUUID().toString());
            line.addProperty("name", name);
            line.addProperty("x", x);
            line.addProperty("y", y);
            line.addProperty("z", z);
            line.addProperty("inLevelPlayers", level.players().size());
            line.addProperty("inPlayerList", server.getPlayerList().getPlayerCount());
        });
        return player;
    }

    public static void remove(ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        ServerGroundTruth.event("fake_player_remove", line -> {
            line.addProperty("entity", player.getId());
            line.addProperty("uuid", player.getUUID().toString());
        });
        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        server.getPlayerList().remove(player);
    }
}
