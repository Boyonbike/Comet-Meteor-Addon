/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the private health metadata id without hard-coding the numeric value anywhere. */
@Mixin(LivingEntity.class)
public interface CombatLivingEntityDataAccessor {
    @Accessor("DATA_HEALTH_ID")
    static EntityDataAccessor<Float> comet$healthData() {
        throw new AssertionError("mixin accessor not applied");
    }
}
