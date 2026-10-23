/*
 * This file is part of THM Addons — https://github.com/Leonn170709/THM-Addons
 * Copyright (c) THM Addons contributors. Credit the devs, keep the link.
 * By using this code you agree to the license terms and to keep your repo public.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import dev.tntcoating.modules.Speedmine;

/** Airborne mining adaptation from Quiettee Utils (GPL-3.0). */
@Mixin(Player.class)
public abstract class SpeedmineAirMixin {
    @ModifyReturnValue(method = "getDestroySpeed", at = @At("RETURN"))
    private float comet$airMiningSpeed(float speed, BlockState state) {
        if (MeteorClient.mc == null || (Object) this != MeteorClient.mc.player || Modules.get() == null) return speed;
        Speedmine speedmine = Modules.get().get(Speedmine.class);
        return speedmine != null && speedmine.liftsAirMiningPenalty() ? speed * 5 : speed;
    }
}
