/*
 * Adapted from Quiettee Utils BoatPhase by Quiettee and contributors.
 * Copyright (c) Quiettee Utils contributors; Comet adaptations (c) 2026 Comet contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.tntcoating.events;

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;

public abstract class BoatPlayerCorrectionEvent {
    public final ClientboundPlayerPositionPacket packet;

    private BoatPlayerCorrectionEvent(ClientboundPlayerPositionPacket packet) {
        this.packet = packet;
    }

    public static final class Before extends BoatPlayerCorrectionEvent {
        public Before(ClientboundPlayerPositionPacket packet) {
            super(packet);
        }
    }

    public static final class After extends BoatPlayerCorrectionEvent {
        public After(ClientboundPlayerPositionPacket packet) {
            super(packet);
        }
    }
}
