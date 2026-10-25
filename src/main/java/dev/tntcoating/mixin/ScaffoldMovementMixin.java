package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.tntcoating.core.scaffold.ScaffoldRotationBridge;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class ScaffoldMovementMixin {
    @Inject(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F", ordinal = 0))
    private void comet$ordinaryRotation(CallbackInfo ci) {
        ScaffoldRotationBridge.INSTANCE.begin((LocalPlayer) (Object) this);
    }
    @WrapMethod(method = "sendPosition")
    private void comet$restoreOrdinaryRotation(Operation<Void> original) {
        boolean successful = false;
        try { original.call(); successful = true; }
        finally { ScaffoldRotationBridge.INSTANCE.finish(successful); }
    }
}
