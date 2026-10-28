/*
 * Adapted from Quiettee Utils BoatPhase by Quiettee and contributors.
 * Copyright (c) Quiettee Utils contributors; Comet adaptations (c) 2026 Comet contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.tntcoating.modules.boatfly.BoatFlyEngine;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractBoat.class)
public abstract class BoatFlyMovementMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
    private void comet$boatMove(AbstractBoat boat, MoverType type, Vec3 movement, Operation<Void> original) {
        BoatFlyEngine module = BoatFlyEngine.active();
        if (module == null) {
            original.call(boat, type, movement);
            return;
        }
        Vec3 step = module.movement(boat, movement);
        boolean oldNoClip = boat.noPhysics;
        try {
            if (module.phases(boat)) boat.noPhysics = true;
            original.call(boat, type, step);
        } finally {
            boat.noPhysics = oldNoClip;
        }
        module.afterMove(boat);
    }
}
