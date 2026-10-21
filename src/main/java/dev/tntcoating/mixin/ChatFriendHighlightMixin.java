package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.tntcoating.modules.ChatTools;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiMessage.class)
public abstract class ChatFriendHighlightMixin {
    @ModifyExpressionValue(method = "splitLines", at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/multiplayer/chat/GuiMessage;content:Lnet/minecraft/network/chat/Component;"))
    private Component comet$highlightNames(Component message) {
        if (Modules.get() == null) return message;
        ChatTools tools = Modules.get().get(ChatTools.class);
        return tools != null && tools.isActive() ? tools.highlightNames(message) : message;
    }
}
