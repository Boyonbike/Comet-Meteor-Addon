package dev.tntcoating.gui;

import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The chat tools design system: one palette, one spacing and size scale, pixel-snapped type and bitmap icons.
 * Meteor's accent colour personalises selection and primary actions.
 */
public final class ChatToolsStyle {
    // Surfaces, darkest first: window chrome (header and composer bars), body, cards and lists, raised controls.
    public static final int CHROME = 0xff0d1015;
    public static final int BACKGROUND = 0xff12151c;
    public static final int SURFACE = 0xff181c25;
    public static final int RAISED = 0xff232937;
    public static final int BORDER = 0xff283041;
    public static final int DIVIDER = 0xff212734;
    public static final int TEXT = 0xffe9edf5;
    public static final int SECONDARY = 0xff9ba6b9;
    public static final int MUTED = 0xff657288;
    public static final int GREEN = 0xff78d8b3;
    public static final int AMBER = 0xffeac58b;
    public static final int RED = 0xfff09aa7;

    // Spacing and component sizes in GUI pixels.
    public static final int GAP = 6;
    public static final int PAD = 12;
    public static final int RADIUS = 6;
    public static final int CONTROL = 20;
    public static final int ROW = 34;

    private static final float BODY = 0.85f, SMALL = 0.72f;

    // Icons are drawn from these bitmaps one GUI pixel per cell, so they stay as crisp as the font.
    public static final String[] HOUSE = {
        "....#....", "...###...", "..#####..", ".#######.", "#########", ".##...##.", ".##.#.##.", ".##.#.##.", ".#######."};
    public static final String[] STAR = {
        "....#....", "...###...", "...###...", "#########", ".#######.", "..#####..", "..##.##..", ".##...##.", ".#.....#."};
    public static final String[] STAR_OUTLINE = {
        "....#....", "...#.#...", "...#.#...", "####.####", ".#.....#.", "..#...#..", "..#.#.#..", ".#.#.#.#.", ".##...##."};
    public static final String[] SEARCH = {
        ".####....", "#....#...", "#....#...", "#....#...", "#....#...", ".####.#..", "......##.", ".......##", "........#"};
    public static final String[] CLOSE = {
        "#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#"};
    public static final String[] BACK = {"...#", "..#.", ".#..", "#...", ".#..", "..#.", "...#"};
    public static final String[] NEXT = {"#...", ".#..", "..#.", "...#", "..#.", ".#..", "#..."};
    public static final String[] MORE = {"##.##.##", "##.##.##"};

    private ChatToolsStyle() {}

    public static int accent() {
        return GuiThemes.get() instanceof MeteorGuiTheme theme ? theme.accentColor.get().getPacked() | 0xff000000 : 0xffa392f2;
    }
    public static int mix(int a, int b, double ratio) {
        int r = (int) (((a >> 16) & 255) * (1 - ratio) + ((b >> 16) & 255) * ratio);
        int g = (int) (((a >> 8) & 255) * (1 - ratio) + ((b >> 8) & 255) * ratio);
        int bl = (int) ((a & 255) * (1 - ratio) + (b & 255) * ratio);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
    public static int accentSurface() { return mix(SURFACE, accent(), 0.2); }
    public static int accentText() { return mix(TEXT, accent(), 0.4); }
    public static int onAccent() {
        int c = accent();
        return ((c >> 16 & 255) * 299 + (c >> 8 & 255) * 587 + (c & 255) * 114) / 1000 > 150 ? 0xff10131a : 0xfffafbff;
    }

    /**
     * Minecraft's font is a pixel font, so a scale is rounded to whole screen pixels per font pixel. Small text keeps at
     * least two screen pixels per font pixel when the GUI scale allows it, so it never shrinks to an unreadable size.
     */
    public static float snap(float scale, int minimumPixels) {
        int gui = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        return Math.max(Math.min(minimumPixels, gui), Math.round(scale * gui)) / (float) gui;
    }
    public static float bodyScale() { return snap(BODY, 1); }
    public static float smallScale() { return snap(SMALL, 2); }
    public static int width(String text, float scale) { return (int) Math.ceil(Minecraft.getInstance().font.width(text) * scale); }
    /** Cap height, used to centre a line of text vertically in a box. */
    public static int capHeight(float scale) { return Math.round(7 * scale); }

    public static void text(GuiGraphicsExtractor g, String text, int x, int y, int width, int color) {
        type(g, text, x, y, width, color, bodyScale());
    }
    public static void small(GuiGraphicsExtractor g, String text, int x, int y, int width, int color) {
        type(g, text, x, y, width, color, smallScale());
    }
    /** Draws one line, ending in "..." when it is wider than {@code width}. */
    public static void type(GuiGraphicsExtractor g, String text, int x, int y, int width, int color, float scale) {
        if (width <= 0) return;
        var font = Minecraft.getInstance().font;
        int max = Math.max(0, (int) (width / scale));
        String clipped = font.width(text) > max && max > font.width("...")
            ? font.plainSubstrByWidth(text, max - font.width("...")) + "..." : font.plainSubstrByWidth(text, max);
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale);
        g.text(font, clipped, 0, 0, color, false);
        g.pose().popMatrix();
    }

    public static void round(GuiGraphicsExtractor g, int x, int y, int width, int height, int radius, int color) {
        round(g, x, y, width, height, radius, color, true, true);
    }
    /** A rectangle whose top and/or bottom corners are rounded; list rows use it to join into one grouped card. */
    public static void round(GuiGraphicsExtractor g, int x, int y, int width, int height, int radius, int color, boolean top, boolean bottom) {
        if (width <= 0 || height <= 0) return;
        int r = Math.min(radius, Math.min(width, height) / (top && bottom ? 2 : 1));
        if (r < 1 || !top && !bottom) { g.fill(x, y, x + width, y + height, color); return; }
        int bodyTop = top ? y + r : y, bodyBottom = bottom ? y + height - r : y + height;
        g.fill(x, bodyTop, x + width, bodyBottom, color);
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int inset = (int) Math.ceil(r - Math.sqrt(r * r - dy * dy));
            if (top) g.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
            if (bottom) g.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
        }
    }
    public static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height, int radius, int border, int fill) {
        panel(g, x, y, width, height, radius, border, fill, true, true);
    }
    public static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height, int radius, int border, int fill, boolean top, boolean bottom) {
        round(g, x, y, width, height, radius, border, top, bottom);
        round(g, x + 1, y + (top ? 1 : 0), width - 2, height - (top ? 1 : 0) - (bottom ? 1 : 0), Math.max(0, radius - 1), fill, top, bottom);
    }

    public static void icon(GuiGraphicsExtractor g, String[] bitmap, int x, int y, int color) {
        for (int row = 0; row < bitmap.length; row++) {
            String line = bitmap[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) != '#') continue;
                int end = col;
                while (end + 1 < line.length() && line.charAt(end + 1) == '#') end++;
                g.fill(x + col, y + row, x + end + 1, y + row + 1, color);
                col = end;
            }
        }
    }
    public static int iconWidth(String[] bitmap) { return bitmap[0].length(); }
    public static int iconHeight(String[] bitmap) { return bitmap.length; }
    /** Draws an icon centred in a box. */
    public static void icon(GuiGraphicsExtractor g, String[] bitmap, int x, int y, int width, int height, int color) {
        icon(g, bitmap, x + (width - iconWidth(bitmap)) / 2, y + (height - iconHeight(bitmap)) / 2, color);
    }
}
