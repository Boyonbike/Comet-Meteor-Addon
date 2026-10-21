package dev.tntcoating.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.tntcoating.modules.ChatTools;
import java.util.List;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Friend and clan colours in the tab list: on the name and, with its own colour, as a highlight behind the row. */
@Mixin(PlayerTabOverlay.class)
public abstract class TabListHighlightMixin {
    /** Wraps the call rather than the method, so it also colours the name Meteor's Better Tab returns from its HEAD hook. */
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;"))
    private Component comet$colorName(PlayerTabOverlay overlay, PlayerInfo info, Operation<Component> original) {
        Component name = original.call(overlay, info);
        ChatTools tools = comet$tools();
        return tools != null ? tools.highlightTabName(info, name) : name;
    }

    /** The third fill is each slot's own background; the head, name and ping are drawn over it afterwards. */
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V", ordinal = 2))
    private void comet$highlightRow(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color, Operation<Void> original,
                                    @Local(name = "i") int index, @Local(name = "playerInfos") List<PlayerInfo> infos) {
        original.call(graphics, x0, y0, x1, y1, color);
        ChatTools tools = comet$tools();
        if (tools == null || index >= infos.size()) return;
        int highlight = tools.tabHighlight(infos.get(index));
        if (highlight != 0) graphics.fill(x0, y0, x1, y1, highlight);
    }

    private static ChatTools comet$tools() {
        if (Modules.get() == null) return null;
        ChatTools tools = Modules.get().get(ChatTools.class);
        return tools != null && tools.isActive() ? tools : null;
    }
}
