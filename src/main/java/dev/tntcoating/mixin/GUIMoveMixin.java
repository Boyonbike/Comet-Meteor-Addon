package dev.tntcoating.mixin;

import dev.tntcoating.gui.ChatToolsScreen;
import meteordevelopment.meteorclient.systems.modules.movement.GUIMove;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GUIMove.class, remap = false)
public abstract class GUIMoveMixin {
    // Chat Tools is typed into like chat, which GUI Move already leaves alone. Every GUI Move check asks skip(),
    // so this also releases held movement keys when the window opens.
    @Inject(method = "skip", at = @At("HEAD"), cancellable = true)
    private void comet$skipChatTools(CallbackInfoReturnable<Boolean> cir) {
        if (Minecraft.getInstance().screen instanceof ChatToolsScreen) cir.setReturnValue(true);
    }
}
