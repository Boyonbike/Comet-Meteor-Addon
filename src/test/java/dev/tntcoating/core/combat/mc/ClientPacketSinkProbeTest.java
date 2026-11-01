/*
 * Original Comet implementation by Boyonabike.
 * Independent: probe contract tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.mc.CombatPacketSink.SendResult;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.junit.jupiter.api.Test;

/**
 * Stage 6 probe contract: the sequence comes from the vanilla prediction handler, the packet is the abort
 * branch only, and no real sequence means no send (never a fabricated {@code -1} ack).
 */
final class ClientPacketSinkProbeTest {
    @Test
    void probeSendsOneAbortCarryingTheHandlerSequence() {
        BlockStatePredictionHandler handler = new BlockStatePredictionHandler();
        List<ServerboundPlayerActionPacket> sent = new ArrayList<>();

        SendResult result = ClientPacketSink.probe(new BlockPos(0, 66, 0), handler, sent::add);

        assertTrue(result.enqueued());
        assertTrue(result.sendMethodCompleted());
        assertTrue(result.ackSequence() >= 0, "a real prediction sequence was assigned");
        assertEquals(handler.currentSequence(), result.ackSequence());
        assertEquals(1, sent.size());
        ServerboundPlayerActionPacket packet = sent.get(0);
        assertEquals(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, packet.getAction());
        assertEquals(new BlockPos(0, 66, 0), packet.getPos());
        assertEquals(Direction.DOWN, packet.getDirection());
        assertEquals(result.ackSequence(), packet.getSequence());
    }

    @Test
    void probeRefusesWithoutAPositionHandlerOrSender() {
        BlockStatePredictionHandler handler = new BlockStatePredictionHandler();
        List<ServerboundPlayerActionPacket> sent = new ArrayList<>();

        assertEquals(SendResult.refused(), ClientPacketSink.probe(null, handler, sent::add));
        assertEquals(SendResult.refused(), ClientPacketSink.probe(new BlockPos(0, 66, 0), null, sent::add));
        assertEquals(SendResult.refused(), ClientPacketSink.probe(new BlockPos(0, 66, 0), handler, null));
        assertTrue(sent.isEmpty());
    }

    @Test
    void probeRefusesANegativeSequenceWithoutSending() {
        BlockStatePredictionHandler noSequence = new BlockStatePredictionHandler() {
            @Override
            public int currentSequence() {
                return -1;
            }
        };
        List<ServerboundPlayerActionPacket> sent = new ArrayList<>();

        SendResult result = ClientPacketSink.probe(new BlockPos(0, 66, 0), noSequence, sent::add);

        assertEquals(SendResult.refused(), result);
        assertTrue(sent.isEmpty(), "a -1 sequence is never presented as an ack");
    }

    @Test
    void probeRefusesWhenTheSendThrowsAndClosesThePrediction() {
        BlockStatePredictionHandler handler = new BlockStatePredictionHandler();
        SendResult failed = ClientPacketSink.probe(new BlockPos(0, 66, 0), handler,
            packet -> {
                throw new IllegalStateException("connection closed");
            });
        assertEquals(SendResult.refused(), failed);

        List<ServerboundPlayerActionPacket> retry = new ArrayList<>();
        SendResult next = ClientPacketSink.probe(new BlockPos(0, 66, 0), handler, retry::add);

        assertTrue(next.enqueued());
        assertEquals(handler.currentSequence(), next.ackSequence());
        assertEquals(1, retry.size());
    }
}
