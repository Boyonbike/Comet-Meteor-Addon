/*
 * This file is part of THM Addons — https://github.com/Leonn170709/THM-Addons
 * Copyright (c) THM Addons contributors. Credit the devs, keep the link.
 * By using this code you agree to the license terms and to keep your repo public.
 */

package dev.tntcoating.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.tntcoating.modules.Speedmine;

@Mixin(ClientLevel.class)
public class SpeedmineBlockUpdateMixin {
    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void comet$beforeServerBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        Speedmine speedmine = Speedmine.INSTANCE;
        if (speedmine != null) speedmine.onServerBlockUpdate((ClientLevel) (Object) this, pos, state);
    }
}
