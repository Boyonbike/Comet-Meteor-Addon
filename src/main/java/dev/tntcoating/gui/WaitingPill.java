package dev.tntcoating.gui;

import dev.tntcoating.modules.ChatTools;
import java.util.ArrayList;
import java.util.List;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import static dev.tntcoating.gui.ChatToolsStyle.*;

/** The in-game reminder that stays on screen while teleport requests, messages or a sync problem wait for the player. */
public final class WaitingPill {
    private WaitingPill() {}

    public static String summary(ChatTools.Waiting waiting) {
        List<String> parts = new ArrayList<>();
        if (waiting.requests() > 0) parts.add(waiting.requests() + " teleport request" + (waiting.requests() == 1 ? "" : "s"));
        if (waiting.unread() > 0) parts.add(waiting.unread() + " message" + (waiting.unread() == 1 ? "" : "s"));
        if (waiting.problem() != null) parts.add("Discord sync needs attention");
        return String.join(" · ", parts);
    }

    public static void draw(GuiGraphicsExtractor g, int screenWidth, ChatTools.Waiting waiting, Keybind key) {
        float previous = pixelsPerUnit();
        pixelsPerUnit(Minecraft.getInstance().getWindow().getGuiScale());
        try {
            String title = summary(waiting);
            String hint = key == null || !key.isSet() ? "Open Chat Tools to answer" : "Press " + key + " to open Chat Tools";
            float body = bodyScale(), small = smallScale();
            int textWidth = Math.max(boldWidth(title, body), width(hint, small));
            int height = 30, width = 8 + 20 + 8 + textWidth + 12;
            int x = screenWidth - width - 8, y = 8;
            round(g, x, y + 1, width, height, height / 2, 0x40000000);
            panel(g, x, y, width, height, height / 2, BORDER, alpha(CHROME, 0.92));
            round(g, x + 5, y + 5, 20, 20, 10, accent());
            icon(g, Icon.BELL, x + 5, y + 5, 20, 20, onAccent(), 10);
            bold(g, title, x + 33, y + 6, textWidth + 2, TEXT, body);
            small(g, hint, x + 33, y + 18, textWidth + 2, MUTED);
        } finally {
            pixelsPerUnit(previous);
        }
    }
}
