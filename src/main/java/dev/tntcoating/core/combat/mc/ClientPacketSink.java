/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.mixin.SpeedminePredictionAccessor;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Production packet sink. Placement goes through {@code MultiPlayerGameMode.useItemOn} so vanilla keeps
 * ownership of prediction bookkeeping; attack, swing and hotbar selection are plain packets on the same
 * connection. No method touches the offhand, switches modules or mutates Meteor settings.
 */
public final class ClientPacketSink implements CombatPacketSink {
    private final Minecraft mc;

    public ClientPacketSink(Minecraft mc) {
        this.mc = Objects.requireNonNull(mc, "mc");
    }

    @Override
    public boolean open() {
        return mc.getConnection() != null && mc.getConnection().getConnection().isConnected();
    }

    @Override
    public SendResult placeSupport(BlockHitResult hit) {
        return placeCrystal(hit);
    }

    @Override
    public SendResult placePiston(BlockHitResult hit) {
        return placeCrystal(hit);
    }

    @Override
    public SendResult placePower(BlockHitResult hit) {
        return placeCrystal(hit);
    }

    @Override
    public SendResult placeCrystal(BlockHitResult hit) {
        if (!open() || mc.player == null || mc.gameMode == null || hit == null) {
            return SendResult.refused();
        }
        try {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            return SendResult.accepted(true, -1);
        } catch (RuntimeException refused) {
            return SendResult.refused();
        }
    }

    @Override
    public SendResult attack(int entityId) {
        if (!open() || entityId < 0) return SendResult.refused();
        try {
            mc.getConnection().getConnection().send(new ServerboundAttackPacket(entityId));
            return SendResult.accepted(true, -1);
        } catch (RuntimeException refused) {
            return SendResult.refused();
        }
    }

    /**
     * Vanilla block breaking, exactly as holding the attack key would drive it: the interaction manager
     * owns progress, prediction and the start/continue packets. Called only from {@code dispatch} on the
     * client thread; it never touches Speedmine or any other module.
     */
    @Override
    public SendResult breakBlock(BlockHitResult hit) {
        if (!open() || mc.player == null || mc.gameMode == null || hit == null) {
            return SendResult.refused();
        }
        try {
            mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection());
            return SendResult.accepted(true, -1);
        } catch (RuntimeException refused) {
            return SendResult.refused();
        }
    }

    /**
     * Inert barrier probe. Vanilla's prediction handler assigns a real sequence to the abort packet, so
     * the server acks it like any other block action; the abort branch only clears destroy state and
     * sends a -1 progress overlay, so no block, inventory slot or offhand is touched. A missing handler,
     * missing connection or negative sequence is reported as {@link SendResult#refused()}.
     */
    @Override
    public SendResult probeBlockChange(BlockPos pos) {
        if (!open() || mc.player == null || mc.level == null || pos == null) {
            return SendResult.refused();
        }
        try {
            BlockStatePredictionHandler prediction = ((SpeedminePredictionAccessor) (Object) mc.level)
                .comet$getBlockStatePredictionHandler();
            return probe(pos, prediction, packet -> mc.getConnection().getConnection().send(packet));
        } catch (RuntimeException refused) {
            return SendResult.refused();
        }
    }

    /**
     * Package-private seam for the probe contract: the handler supplies the sequence and the consumer
     * carries the packet, so tests can pin the sequence, action and refusal rules without a live client.
     */
    static SendResult probe(BlockPos pos, BlockStatePredictionHandler handler,
                            Consumer<ServerboundPlayerActionPacket> sender) {
        if (pos == null || handler == null || sender == null) {
            return SendResult.refused();
        }
        try (BlockStatePredictionHandler prediction = handler.startPredicting()) {
            int sequence = prediction.currentSequence();
            if (sequence < 0) {
                return SendResult.refused();
            }
            sender.accept(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, Direction.DOWN, sequence));
            return SendResult.accepted(true, sequence);
        } catch (RuntimeException refused) {
            return SendResult.refused();
        }
    }

    @Override
    public void stopBreak() {
        if (!open() || mc.player == null || mc.gameMode == null) return;
        try {
            mc.gameMode.stopDestroyBlock();
        } catch (RuntimeException ignored) {
            // A closing connection can tear the game mode down mid-call; the local state is already clear.
        }
    }

    @Override
    public SendResult swing() {
        if (!open()) return SendResult.refused();
        try {
            mc.getConnection().getConnection()
                .send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            return SendResult.accepted(true, -1);
        } catch (RuntimeException refused) {
            return SendResult.refused();
        }
    }

    @Override
    public SendResult selectSlot(int slot) {
        if (!open() || slot < 0 || slot > 8) return SendResult.refused();
        try {
            mc.getConnection().getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            return SendResult.accepted(true, -1);
        } catch (RuntimeException refused) {
            return SendResult.refused();
        }
    }
}
