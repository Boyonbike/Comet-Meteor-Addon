package dev.tntcoating.render;

import java.util.List;
import meteordevelopment.meteorclient.events.render.Render2DEvent;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Small fixed-position overviews for the two adaptive movement engines. */
public final class AdaptiveEngineHud {
    private static final int MARGIN = 6;
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int BACKGROUND = 0xb0000000;
    private static final int BORDER = 0xff55aaff;
    private static final int TITLE = 0xff55aaff;
    private static final int TEXT = 0xffeeeeee;

    private AdaptiveEngineHud() {}

    public static void renderLeft(Render2DEvent event, String title, List<String> lines) {
        render(event, MARGIN, title, lines);
    }

    public static void renderRight(Render2DEvent event, String title, List<String> lines) {
        int width = width(title, lines);
        render(event, Math.max(MARGIN, event.screenWidth - MARGIN - width), title, lines);
    }

    private static void render(Render2DEvent event, int x, String title, List<String> lines) {
        if (mc.player == null || mc.level == null || mc.screen != null || mc.options.hideGui) return;
        int width = width(title, lines);
        int height = PADDING * 2 + LINE_HEIGHT * (lines.size() + 1);
        int y = MARGIN;
        event.graphics.fill(x, y, x + width, y + height, BACKGROUND);
        event.graphics.fill(x, y, x + 2, y + height, BORDER);
        event.graphics.text(mc.font, title, x + PADDING + 1, y + PADDING, TITLE, true);
        for (int i = 0; i < lines.size(); i++) {
            event.graphics.text(mc.font, lines.get(i), x + PADDING + 1,
                y + PADDING + LINE_HEIGHT * (i + 1), TEXT, true);
        }
    }

    private static int width(String title, List<String> lines) {
        int textWidth = mc.font.width(title);
        for (String line : lines) textWidth = Math.max(textWidth, mc.font.width(line));
        return textWidth + PADDING * 2 + 1;
    }
}
