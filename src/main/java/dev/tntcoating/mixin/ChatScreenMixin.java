package dev.tntcoating.mixin;

import dev.tntcoating.modules.ChatTools;
import dev.tntcoating.gui.ChatToolsHud;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.gui.screens.ChatScreen.class)
public abstract class ChatScreenMixin {
    @Shadow private ChatComponent.DisplayMode displayMode;
    @Shadow protected EditBox input;
    @Shadow private CommandSuggestions commandSuggestions;
    @Unique private final ChatToolsHud comet$hud = new ChatToolsHud();

    @Unique private ChatTools comet$tools() {
        if (Modules.get() == null) return null;
        ChatTools tools = Modules.get().get(ChatTools.class);
        return tools != null && tools.isActive() ? tools : null;
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void comet$renderHud(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ChatTools tools = comet$tools();
        if (tools != null) comet$hud.render(graphics, mouseX, mouseY, tools, input::setValue);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void comet$scrollHud(double x, double y, double horizontal, double vertical, CallbackInfoReturnable<Boolean> cir) {
        if (comet$tools() != null && comet$hud.scroll(x, y, vertical != 0 ? vertical : horizontal)) cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void comet$backHud(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && !commandSuggestions.isVisible() && comet$tools() != null && comet$hud.back()) cir.setReturnValue(true);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void comet$chatTools(MouseButtonEvent event, boolean doubled,
                                 CallbackInfoReturnable<Boolean> cir) {
        ChatTools tools = comet$tools();
        if (tools == null) return;
        if (comet$hud.click(event.x(), event.y(), event.button())) {
            cir.setReturnValue(true);
            return;
        }
        if (event.button() != 0) return;
        if (commandSuggestions.isVisible()) return;

        var mc = net.minecraft.client.Minecraft.getInstance();
        var finder = new PlayerNameStyleFinder(mc.font, (int) event.x(), (int) event.y(), onlineNames(mc))
            .includeInsertions(true);
        mc.gui.getChat().captureClickableText(finder, mc.getWindow().getGuiScaledHeight(),
            mc.gui.getGuiTicks(), displayMode);
        Style style = finder.result();
        String player = tools.playerFor(style);
        if (player != null) {
            comet$hud.selectPlayer(player, tools);
            cir.setReturnValue(true);
        }
    }

    private static Map<String, String> onlineNames(net.minecraft.client.Minecraft mc) {
        Map<String, String> names = new HashMap<>();
        if (mc.getConnection() == null) return names;
        mc.getConnection().getOnlinePlayers().forEach(info -> {
            String name = info.getProfile().name();
            names.put(name.toLowerCase(Locale.ROOT), name);
        });
        return names;
    }

    /** Makes plain, unstyled player names discoverable without changing what chat renders. */
    private static final class PlayerNameStyleFinder extends ActiveTextCollector.ClickableStyleFinder {
        private final Map<String, String> onlineNames;

        private PlayerNameStyleFinder(Font font, int x, int y, Map<String, String> onlineNames) {
            super(font, x, y);
            this.onlineNames = onlineNames;
        }

        @Override
        public void accept(TextAlignment alignment, int x, int y, ActiveTextCollector.Parameters parameters,
                           FormattedCharSequence text) {
            List<Glyph> glyphs = new ArrayList<>();
            text.accept((index, style, codePoint) -> {
                glyphs.add(new Glyph(style, codePoint));
                return true;
            });

            String[] insertions = new String[glyphs.size()];
            for (int start = 0; start < glyphs.size();) {
                if (!nameCharacter(glyphs.get(start).codePoint())) {
                    start++;
                    continue;
                }
                int end = start + 1;
                StringBuilder token = new StringBuilder();
                token.appendCodePoint(glyphs.get(start).codePoint());
                while (end < glyphs.size() && nameCharacter(glyphs.get(end).codePoint())) {
                    token.appendCodePoint(glyphs.get(end).codePoint());
                    end++;
                }
                String name = onlineNames.get(token.toString().toLowerCase(Locale.ROOT));
                if (name != null) for (int i = start; i < end; i++) insertions[i] = name;
                start = end;
            }

            FormattedCharSequence decorated = sink -> {
                for (int i = 0; i < glyphs.size(); i++) {
                    Glyph glyph = glyphs.get(i);
                    Style style = insertions[i] == null ? glyph.style() : glyph.style().withInsertion(insertions[i]);
                    if (!sink.accept(i, style, glyph.codePoint())) return false;
                }
                return true;
            };
            super.accept(alignment, x, y, parameters, decorated);
        }

        private static boolean nameCharacter(int codePoint) {
            return codePoint == '_' || codePoint >= '0' && codePoint <= '9'
                || codePoint >= 'A' && codePoint <= 'Z' || codePoint >= 'a' && codePoint <= 'z';
        }

        private record Glyph(Style style, int codePoint) {}
    }
}
