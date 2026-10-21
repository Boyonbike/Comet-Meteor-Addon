package dev.tntcoating.mixin;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.combat.CrystalAura;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CrystalAura.class, remap = false)
public abstract class CrystalAuraMixin {
    // Tick events can outlive the world, including when another listener disconnects during dispatch.
    // Guard the late rotation callback too: it can still run after the main callback was skipped.
    @Inject(method = {"onPreTick", "onPreTickLast"}, at = @At("HEAD"), cancellable = true)
    private void comet$skipTickWithoutWorld(TickEvent.Pre event, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) ci.cancel();
    }
}
