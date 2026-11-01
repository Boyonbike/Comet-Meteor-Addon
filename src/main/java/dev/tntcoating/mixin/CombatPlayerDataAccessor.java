/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to the synched absorption data id (audit C19: 26.2 sends absorption through entity
 * metadata, not through {@code ClientboundSetHealthPacket}). Nothing here mutates entity state.
 */
@Mixin(Player.class)
public interface CombatPlayerDataAccessor {
    @Accessor("DATA_PLAYER_ABSORPTION_ID")
    static EntityDataAccessor<Float> comet$absorptionData() {
        throw new AssertionError();
    }
}
