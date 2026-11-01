/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the private absorption metadata id without hard-coding the numeric value anywhere. */
@Mixin(Player.class)
public interface CombatPlayerDataAccessor {
    @Accessor("DATA_PLAYER_ABSORPTION_ID")
    static EntityDataAccessor<Float> comet$absorptionData() {
        throw new AssertionError("mixin accessor not applied");
    }
}
