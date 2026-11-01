/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to the synched health data id. The id is private in 26.2 and the combat observation
 * intake needs it to tell which fields a {@code ClientboundSetEntityDataPacket} actually carried. Nothing
 * here mutates entity state.
 */
@Mixin(LivingEntity.class)
public interface CombatLivingEntityDataAccessor {
    @Accessor("DATA_HEALTH_ID")
    static EntityDataAccessor<Float> comet$healthData() {
        throw new AssertionError();
    }
}
