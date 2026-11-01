/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The only place combat packets leave the client. {@link CombatRuntime#dispatch} calls it on the client
 * thread and records each send at packet level; planners never see this interface. Implementations must be
 * fail-observable: a refused send reports {@code enqueued == false} instead of throwing.
 *
 * <p>Local enqueue acceptance, send-method completion and later server acknowledgement are separate facts.
 * Vanilla's prediction sequence is private in 26.2 (audit C8), so the production ack sequence is unknown
 * ({@code -1}); no production path may treat {@code -1} as an acknowledgement.
 */
public interface CombatPacketSink {
    /** Whether a usable connection exists right now. */
    boolean open();

    /** One packet attempt. {@code ackSequence} is the prediction sequence, or {@code -1} when unknown. */
    record SendResult(boolean enqueued, boolean sendMethodCompleted, int ackSequence) {
        public static SendResult refused() {
            return new SendResult(false, false, -1);
        }

        public static SendResult accepted(boolean completed, int ackSequence) {
            return new SendResult(true, completed, ackSequence);
        }
    }

    /** Sends the placement interaction. This is the causative packet for a place action. */
    SendResult placeCrystal(BlockHitResult hit);

    /** Places one support (obsidian base) block; the hit is that block's target face. */
    SendResult placeSupport(BlockHitResult hit);

    /** Places a piston for the Phase 5 trap. Default keeps old sinks source-compatible. */
    default SendResult placePiston(BlockHitResult hit) {
        return placeSupport(hit);
    }

    /** Places the trap's redstone power source. Default keeps old sinks source-compatible. */
    default SendResult placePower(BlockHitResult hit) {
        return placeSupport(hit);
    }

    /** Sends the attack interaction. This is the causative packet for an attack action. */
    SendResult attack(int entityId);

    /**
     * Vanilla block breaking through the client interaction manager. This is the causative call for a
     * cleanup action; it never routes through Speedmine or any other module.
     */
    SendResult breakBlock(BlockHitResult hit);

    /**
     * Sends one side-effect-free sequenced interaction for the BARRIER probe: an
     * {@code ABORT_DESTROY_BLOCK} action at an inert position (air above the player's head), carrying a real
     * vanilla prediction sequence. The server's abort branch only clears its own destroy state and sends a
     * {@code -1} destroy-progress overlay; it never changes a block, and the server always acknowledges the
     * sequence on its next tick. Implementations must report {@code -1} when they cannot obtain a real
     * sequence; no caller may treat {@code -1} as an acknowledgement.
     */
    SendResult probeBlockChange(BlockPos pos);

    /**
     * Cancels the vanilla destruction this sink started. This is the one cancellation exception to
     * {@code dispatch}: it is not a combat send, never retires risk, and never touches the packet budget.
     * It is only called for a block this module started breaking, from the client thread, while the
     * session is still open.
     */
    void stopBreak();

    /** Sends a swing. Supporting only: a required swing failure must not roll back an attack. */
    SendResult swing();

    /** Sends a hotbar selection change. Supporting only. */
    SendResult selectSlot(int slot);
}
