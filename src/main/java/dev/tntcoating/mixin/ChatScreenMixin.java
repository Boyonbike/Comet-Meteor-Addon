package dev.tntcoating.mixin;

import dev.tntcoating.modules.ChatTools;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(net.minecraft.client.gui.screens.ChatScreen.class)
public abstract class ChatScreenMixin {
    @Shadow private ChatComponent.DisplayMode displayMode;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void comet$chatTools(MouseButtonEvent event, boolean doubled,
                                 CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != 0 || Modules.get() == null) return;
        ChatTools tools = Modules.get().get(ChatTools.class);
        if (tools == null || !tools.isActive()) return;

        var mc = net.minecraft.client.Minecraft.getInstance();
        var finder = new ActiveTextCollector.ClickableStyleFinder(mc.font, (int) event.x(), (int) event.y())
            .includeInsertions(true);
        mc.gui.hud.getChat().captureClickableText(finder, mc.getWindow().getGuiScaledHeight(),
            mc.gui.hud.getGuiTicks(), displayMode);
        Style style = finder.result();
        if (style != null && tools.openFor(style)) cir.setReturnValue(true);
    }
}
