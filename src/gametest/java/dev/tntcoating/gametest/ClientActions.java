/*
 * Original Comet test harness code by Boyonabike.
 * Independent: written for this repository against the pinned Minecraft 26.2
 * client classes; no third-party client code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side action helpers. These use the same client packet paths as a real hotbar
 * press, block use or attack; they do not construct product combat packets.
 */
public final class ClientActions {
    private ClientActions() {
    }

    public static void lookAt(ClientGameTestContext context, BlockPos pos) {
        context.getInput().lookAt(pos);
        context.waitTicks(1);
    }

    public static void selectHotbar(ClientGameTestContext context, int slot) {
        context.runOnClient(client -> {
            client.player.getInventory().setSelectedSlot(slot);
            client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        });
        context.waitTicks(1);
    }

    public static void placeOn(ClientGameTestContext context, BlockPos base, Direction face) {
        context.runOnClient(client -> {
            LocalPlayer player = client.player;
            Vec3 hit = Vec3.atCenterOf(base).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            BlockHitResult result = new BlockHitResult(hit, face, base, false);
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, result);
        });
    }

    public static void interactBlock(ClientGameTestContext context, BlockPos base, Direction face) {
        context.runOnClient(client -> {
            Vec3 hit = Vec3.atCenterOf(base).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, base, false));
        });
    }

    public static void useItem(ClientGameTestContext context) {
        context.runOnClient(client ->
            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND));
        context.waitTicks(1);
    }

    public static void attack(ClientGameTestContext context, int entityId) {
        context.runOnClient(client -> {
            Entity target = client.level == null ? null : client.level.getEntity(entityId);
            if (target != null) {
                client.gameMode.attack(client.player, target);
            }
        });
    }

    public static void attackEntity(ClientGameTestContext context, Entity target) {
        context.runOnClient(client -> client.gameMode.attack(client.player, target));
    }

    public static void swing(ClientGameTestContext context) {
        context.runOnClient(client -> client.player.swing(InteractionHand.MAIN_HAND));
    }

    public static Vec3 playerPosition(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player == null ? null : client.player.position());
    }

    public static int selectedSlot(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player == null
            ? -1 : client.player.getInventory().getSelectedSlot());
    }

    public static void waitForClientEntity(ClientGameTestContext context, int entityId) {
        context.waitFor(client -> client.level != null && client.level.getEntity(entityId) != null);
    }

    public static void waitForClientRemoval(ClientGameTestContext context, int entityId) {
        context.waitFor(client -> client.level != null && client.level.getEntity(entityId) == null);
    }

    public static void sendChatCommand(ClientGameTestContext context, String command) {
        context.runOnClient(client -> {
            if (client.player != null) {
                client.player.connection.sendCommand(command.startsWith("/") ? command.substring(1) : command);
            }
        });
        context.waitTicks(1);
    }
}
