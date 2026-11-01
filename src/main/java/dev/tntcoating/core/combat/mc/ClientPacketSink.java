/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
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
