/*
 * Comet disconnect notification bridge.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.tntcoating.mixin;

import dev.tntcoating.modules.HighwayFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs before Meteor's priority-1000 world-leave hook clears the trip/player context. */
@Mixin(value = Minecraft.class, priority = 1500)
public abstract class HighwayFlyDisconnectMixin {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void comet$notifyActualDisconnect(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo ci) {
        Modules modules = Modules.get();
        HighwayFly highway = modules == null ? null : modules.get(HighwayFly.class);
        if (highway != null) highway.onClientDisconnect(keepResourcePacks, screen instanceof DisconnectedScreen);
    }
}
