/*
 * Original Comet implementation by Boyonabike.
 * Independent: written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.junit.jupiter.api.Test;

final class ClientPacketSinkProbeTest {
    @Test
    void probeSendsOneAbortCarryingTheHandlerSequence() {
        BlockStatePredictionHandler handler = new BlockStatePredictionHandler();
        List<ServerboundPlayerActionPacket> sent = new ArrayList<>();
        CombatPacketSink.SendResult result =
            ClientPacketSink.probe(new BlockPos(1, 64, 2), handler, sent::add);
        assertTrue(result.enqueued());
        assertEquals(1, sent.size());
        ServerboundPlayerActionPacket packet = sent.get(0);
        assertEquals(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, packet.getAction());
        assertEquals(Direction.DOWN, packet.getDirection());
        assertEquals(new BlockPos(1, 64, 2), packet.getPos());
        assertEquals(result.ackSequence(), packet.getSequence());
        assertTrue(result.ackSequence() >= 0);
        assertFalse(handler.isPredicting());
    }

    @Test
    void probeRefusesWithoutAPositionHandlerOrSender() {
        assertFalse(ClientPacketSink.probe(null, new BlockStatePredictionHandler(), packet -> {
        }).enqueued());
        assertFalse(ClientPacketSink.probe(new BlockPos(0, 64, 0), null, packet -> {
        }).enqueued());
        assertFalse(ClientPacketSink.probe(new BlockPos(0, 64, 0),
            new BlockStatePredictionHandler(), null).enqueued());
    }

    @Test
    void probeRefusesANegativeSequenceWithoutSending() {
        BlockStatePredictionHandler handler = new BlockStatePredictionHandler() {
            @Override
            public int currentSequence() {
                return -1;
            }
        };
        List<ServerboundPlayerActionPacket> sent = new ArrayList<>();
        CombatPacketSink.SendResult result =
            ClientPacketSink.probe(new BlockPos(0, 64, 0), handler, sent::add);
        assertFalse(result.enqueued());
        assertTrue(sent.isEmpty());
    }

    @Test
    void probeRefusesWhenTheSendThrowsAndClosesThePrediction() {
        BlockStatePredictionHandler handler = new BlockStatePredictionHandler();
        CombatPacketSink.SendResult result = ClientPacketSink.probe(new BlockPos(0, 64, 0), handler,
            packet -> {
                throw new IllegalStateException("connection closed");
            });
        assertFalse(result.enqueued());
        assertFalse(handler.isPredicting());
        assertSame(CombatPacketSink.SendResult.class, result.getClass());
    }
}
