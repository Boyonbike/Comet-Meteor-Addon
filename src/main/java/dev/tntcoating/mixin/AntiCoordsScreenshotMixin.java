package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.tntcoating.modules.AntiCoords;
import java.io.File;
import java.util.function.Consumer;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(KeyboardHandler.class)
public abstract class AntiCoordsScreenshotMixin {
    @WrapOperation(method = "keyPress", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Screenshot;grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V"))
    private void comet$deferScreenshot(File workDir, RenderTarget target, Consumer<Component> callback, Operation<Void> original) {
        if (!AntiCoords.deferScreenshot(callback)) original.call(workDir, target, callback);
    }
}
