package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.tntcoating.modules.AntiCoords;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public abstract class AntiCoordsScreenshotMixin {
    // 26.2 takes the screenshot key in Minecraft.handleGlobalKeyPress rather than KeyboardHandler.
    @WrapOperation(method = "handleGlobalKeyPress", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Screenshot;grab(Lnet/minecraft/client/Minecraft;Z)V"))
    private void comet$deferScreenshot(Minecraft minecraft, boolean controlDown, Operation<Void> original) {
        Consumer<Component> callback = message -> minecraft.execute(() -> minecraft.showDebugChat(message));
        if (!AntiCoords.deferScreenshot(callback)) original.call(minecraft, controlDown);
    }
}
