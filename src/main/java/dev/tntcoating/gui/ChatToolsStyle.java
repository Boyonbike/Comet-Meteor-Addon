package dev.tntcoating.gui;

import dev.tntcoating.mixin.FontAccessor;
import java.util.HashMap;
import java.util.Map;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * The chat tools design system: one palette, one spacing scale, a smooth typeface and vector icons. Shapes are drawn in
 * real screen pixels with anti-aliased corners, so nothing looks blocky at any GUI scale. Meteor's accent colour
 * personalises selection and primary actions.
 */
public final class ChatToolsStyle {
    // Surfaces, darkest first: window chrome (sidebar and header), body, cards and lists, raised controls.
    public static final int CHROME = 0xff0e1014;
    public static final int BACKGROUND = 0xff13151b;
    public static final int SURFACE = 0xff1a1d24;
    public static final int RAISED = 0xff242832;
    public static final int BORDER = 0xff2c303b;
    public static final int DIVIDER = 0xff22252d;
    public static final int TEXT = 0xffeceef2;
    public static final int SECONDARY = 0xffa9afba;
    public static final int MUTED = 0xff838a98;
    public static final int GREEN = 0xff3ccf8e;
    public static final int AMBER = 0xfff2b54a;
    public static final int RED = 0xffef6767;

    // Spacing and component sizes in interface units.
    public static final int GAP = 6;
    public static final int PAD = 12;
    public static final int RADIUS = 6;
    public static final int CONTROL = 24;
    public static final int ROW = 40;

    private static final float BODY = 0.86f, SMALL = 0.76f;
    private static final FontDescription UI = font("ui"), UI_BOLD = font("ui_bold"), ICONS = font("icons");
    private static final Style BOLD = Style.EMPTY.withFont(UI_BOLD), ICON = Style.EMPTY.withFont(ICONS);

    /** Lucide glyphs with their bounds in font units (1000 per em), so each icon can be centred exactly. */
    public enum Icon {
        GRID(0xe0ff, 917, 917), USERS(0xe1a4, 959, 917), SHIELD(0xe158, 876, 962), PIN(0xe111, 884, 962),
        FLAME(0xe0d2, 840, 917), ZAP(0xe1b4, 877, 961), SETTINGS(0xe154, 921, 958), BELL(0xe059, 917, 961),
        CLOSE(0xe1b2, 791, 792), SEND(0xe152, 960, 962), SEARCH(0xe151, 916, 917), STAR(0xe176, 959, 959),
        MORE(0xe0b6, 880, 586), MESSAGE(0xe116, 965, 960), REFRESH(0xe145, 917, 917), PLUS(0xe13d, 834, 835),
        PENCIL(0xe1f9, 964, 961), TRASH(0xe18e, 916, 959), TELEPORT(0xe04d, 750, 750), CLOCK(0xe087, 965, 961),
        GLOBE(0xe0e8, 969, 960), AT(0xe04e, 959, 959), NEXT(0xe06f, 666, 792), DOWN(0xe06d, 791, 667),
        BACK(0xe048, 834, 835), ALERT(0xe193, 958, 918), CHECK(0xe06c, 875, 792), UPLOAD(0xe19e, 916, 917),
        REPLY(0xe22a, 876, 753), USER_PLUS(0xe1a2, 960, 917), USER_MINUS(0xe1a1, 960, 917), BAN(0xe051, 965, 961),
        DONE(0xe226, 965, 961), INFO(0xe0f9, 965, 961), HOUSE(0xe0f5, 916, 962), LOCK(0xe10b, 916, 962);

        final String glyph;
        final float width, height;
        private Component component;
        Component component() {
            if (component == null) component = Component.literal(glyph).withStyle(ICON);
            return component;
        }
        Icon(int codepoint, int width, int height) {
            this.glyph = Character.toString(codepoint);
            this.width = width / 1000f;
            this.height = height / 1000f;
        }
    }

    private static Font font;
    private static Font.Provider fontBase;
    /** Screen pixels per interface unit for whatever is being drawn now. */
    private static float pixels = 2;
    // Measuring and clipping text walks every glyph; the same labels are drawn every frame, so results are remembered.
    private static final Map<String, Integer> WIDTHS = new HashMap<>(), BOLD_WIDTHS = new HashMap<>();
    private static final Map<String, String> CLIPPED = new HashMap<>();
    private static final Map<String, Component> BOLD_LINES = new HashMap<>();
    private static final int TEXT_CACHE = 4096;

    /**
     * Every shape comes from one texture: a white block for flat fills plus an anti-aliased disc and a one-pixel ring for
     * each radius. Sharing one texture and pipeline lets Minecraft draw whole pages in a handful of batches.
     */
    private static final Identifier SHAPES = Identifier.fromNamespaceAndPath("tnt-coating", "chat_tools/shapes");
    private static final int ATLAS = 1024, MAX_RADIUS = 64;
    private static final int[][] DISC = new int[MAX_RADIUS + 1][], RING = new int[MAX_RADIUS + 1][];
    private static int atlasHeight;

    private ChatToolsStyle() {}

    private static FontDescription font(String path) {
        return new FontDescription.Resource(Identifier.fromNamespaceAndPath("tnt-coating", path));
    }

    /** Minecraft's own font with its default typeface swapped for Inter, so text boxes measure what they draw. */
    public static Font font() {
        Font.Provider base = ((FontAccessor) Minecraft.getInstance().font).comet$provider();
        if (font == null || base != fontBase) {
            fontBase = base;
            WIDTHS.clear(); BOLD_WIDTHS.clear(); CLIPPED.clear(); BOLD_LINES.clear();
            font = new Font(new Font.Provider() {
                @Override public GlyphSource glyphs(FontDescription description) {
                    return base.glyphs(FontDescription.DEFAULT.equals(description) ? UI : description);
                }
                @Override public EffectGlyph effect() { return base.effect(); }
            });
        }
        return font;
    }

    /** Sets how many screen pixels one interface unit covers; the screen chooses this from the window size. */
    public static void pixelsPerUnit(float value) { pixels = Math.max(0.5f, value); }
    public static float pixelsPerUnit() { return pixels; }
    /** One screen pixel in interface units, for hairlines and borders. */
    public static float hairline() { return 1f / pixels; }

    public static int accent() {
        return GuiThemes.get() instanceof MeteorGuiTheme theme ? theme.accentColor.get().getPacked() | 0xff000000 : 0xff913de2;
    }
    public static int mix(int a, int b, double ratio) {
        int r = (int) (((a >> 16) & 255) * (1 - ratio) + ((b >> 16) & 255) * ratio);
        int g = (int) (((a >> 8) & 255) * (1 - ratio) + ((b >> 8) & 255) * ratio);
        int bl = (int) ((a & 255) * (1 - ratio) + (b & 255) * ratio);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
    public static int alpha(int color, double alpha) {
        return (int) Math.round(Math.clamp(alpha, 0, 1) * (color >>> 24)) << 24 | color & 0xffffff;
    }
    public static int accentSurface() { return mix(SURFACE, accent(), 0.2); }
    public static int accentText() { return mix(TEXT, accent(), 0.35); }
    public static int onAccent() {
        int c = accent();
        return ((c >> 16 & 255) * 299 + (c >> 8 & 255) * 587 + (c & 255) * 114) / 1000 > 150 ? 0xff10131a : 0xfffafbff;
    }

    // Type. Inter's cap height is 7 units at scale 1, matching the layout maths used throughout the screen.

    public static float bodyScale() { return BODY; }
    public static float smallScale() { return SMALL; }
    private static int raw(String text) {
        Font font = font();
        Integer cached = WIDTHS.get(text);
        if (cached != null) return cached;
        if (WIDTHS.size() > TEXT_CACHE) WIDTHS.clear();
        int width = font.width(text);
        WIDTHS.put(text, width);
        return width;
    }
    private static int rawBold(String text) {
        Font font = font();
        Integer cached = BOLD_WIDTHS.get(text);
        if (cached != null) return cached;
        if (BOLD_WIDTHS.size() > TEXT_CACHE) BOLD_WIDTHS.clear();
        int width = font.width(Component.literal(text).withStyle(BOLD));
        BOLD_WIDTHS.put(text, width);
        return width;
    }
    public static int width(String text, float scale) { return (int) Math.ceil(raw(text) * scale); }
    public static int boldWidth(String text, float scale) { return (int) Math.ceil(rawBold(text) * scale); }
    /** Cap height, used to centre a line of text vertically in a box. */
    public static int capHeight(float scale) { return Math.round(7 * scale); }

    public static void text(GuiGraphics g, String text, int x, int y, int width, int color) {
        type(g, text, x, y, width, color, bodyScale());
    }
    public static void small(GuiGraphics g, String text, int x, int y, int width, int color) {
        type(g, text, x, y, width, color, smallScale());
    }
    /** Draws one line, ending in an ellipsis when it is wider than {@code width}. */
    public static void type(GuiGraphics g, String text, int x, int y, int width, int color, float scale) {
        if (width <= 0 || text == null) return;
        Font font = font();
        int max = Math.max(0, (int) (width / scale));
        String clipped = text;
        if (raw(text) > max) {
            String key = max + "\u0000" + text;
            clipped = CLIPPED.get(key);
            if (clipped == null) {
                if (CLIPPED.size() > TEXT_CACHE) CLIPPED.clear();
                clipped = max > raw("…") ? font.plainSubstrByWidth(text, max - raw("…")) + "…" : font.plainSubstrByWidth(text, max);
                CLIPPED.put(key, clipped);
            }
        }
        g.pose().pushMatrix();
        g.pose().translate(snap(x), snap(y));
        g.pose().scale(scale);
        g.drawString(font, clipped, 0, 0, color, false);
        g.pose().popMatrix();
    }
    /** Semi-bold text for titles and emphasis. */
    public static void bold(GuiGraphics g, String text, int x, int y, int width, int color, float scale) {
        if (width <= 0 || text == null) return;
        Font font = font();
        int max = Math.max(0, (int) (width / scale));
        String key = max + "\u0000" + text;
        Component line = BOLD_LINES.get(key);
        if (line == null) {
            if (BOLD_LINES.size() > TEXT_CACHE) BOLD_LINES.clear();
            String cut = text;
            if (rawBold(text) > max) {
                int ellipsis = rawBold("…");
                while (!cut.isEmpty() && rawBold(cut) + ellipsis > max) cut = cut.substring(0, cut.length() - 1);
                cut = cut.stripTrailing() + "…";
            }
            line = Component.literal(cut).withStyle(BOLD);
            BOLD_LINES.put(key, line);
        }
        g.pose().pushMatrix();
        g.pose().translate(snap(x), snap(y));
        g.pose().scale(scale);
        g.drawString(font, line, 0, 0, color, false);
        g.pose().popMatrix();
    }
    private static float snap(float value) { return Math.round(value * pixels) / pixels; }

    // Icons.

    public static void icon(GuiGraphics g, Icon icon, int x, int y, int width, int height, int color) {
        icon(g, icon, x, y, width, height, color, 10);
    }
    /** Draws an icon centred in a box; {@code size} is the icon grid in interface units. */
    public static void icon(GuiGraphics g, Icon icon, int x, int y, int width, int height, int color, float size) {
        float scale = size / 10f;
        float glyphWidth = size * icon.width, glyphHeight = size * icon.height;
        float left = x + (width - glyphWidth) / 2f, top = y + (height - glyphHeight) / 2f;
        g.pose().pushMatrix();
        // Glyphs sit on the baseline at 7 font units, rising by their height.
        g.pose().translate(snap(left), snap(top - scale * (7 - 10 * icon.height)));
        g.pose().scale(scale);
        g.drawString(font(), icon.component(), 0, 0, color, false);
        g.pose().popMatrix();
    }

    // Shapes, drawn in screen pixels so corners and hairlines stay smooth and thin at every scale.

    private static void ensureAtlas() {
        if (atlasHeight != 0) return;
        int x = 6, y = 0, shelf = 4;
        for (int r = 1; r <= MAX_RADIUS; r++) {
            for (int[][] slots : new int[][][] {DISC, RING}) {
                int size = r * 2;
                if (x + size > ATLAS) { x = 0; y += shelf + 1; shelf = 0; }
                slots[r] = new int[] {x, y};
                x += size + 1;
                shelf = Math.max(shelf, size);
            }
        }
        int height = Integer.highestOneBit(y + shelf);
        if (height < y + shelf) height <<= 1;
        NativeImage image = new NativeImage(ATLAS, height, true);
        for (int py = 0; py < 4; py++) for (int px = 0; px < 4; px++) image.setPixel(px, py, 0xffffffff);
        for (int r = 1; r <= MAX_RADIUS; r++) { paint(image, DISC[r], r, false); paint(image, RING[r], r, true); }
        Minecraft.getInstance().getTextureManager().register(SHAPES, new DynamicTexture(() -> "Chat Tools shapes", image));
        atlasHeight = height;
    }

    private static void paint(NativeImage image, int[] at, int r, boolean ring) {
        for (int py = 0; py < r * 2; py++) {
            for (int px = 0; px < r * 2; px++) {
                double dx = px + 0.5 - r, dy = py + 0.5 - r, distance = Math.sqrt(dx * dx + dy * dy);
                double cover = Math.clamp(r - distance + 0.5, 0, 1) - (ring ? Math.clamp(r - 1 - distance + 0.5, 0, 1) : 0);
                image.setPixel(at[0] + px, at[1] + py, (int) Math.round(Math.max(0, cover) * 255) << 24 | 0xffffff);
            }
        }
    }

    /** A flat rectangle in screen pixels, drawn from the atlas's white block so it batches with the corners. */
    private static void quad(GuiGraphics g, int x, int y, int width, int height, int color) {
        if (width > 0 && height > 0) g.blit(RenderPipelines.GUI_TEXTURED, SHAPES, x, y, 1, 1, width, height, 1, 1, ATLAS, atlasHeight, color);
    }
    private static void corner(GuiGraphics g, int[] at, int r, int x, int y, boolean right, boolean lower, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, SHAPES, x, y, at[0] + (right ? r : 0), at[1] + (lower ? r : 0), r, r, ATLAS, atlasHeight, color);
    }
    private static int radius(int radius, int width, int height, boolean top, boolean bottom) {
        return Math.max(0, Math.min(Math.min(radius, MAX_RADIUS), Math.min(width / 2, top && bottom ? height / 2 : height)));
    }
    private static void begin(GuiGraphics g) {
        ensureAtlas();
        g.pose().pushMatrix();
        g.pose().scale(1f / pixels);
    }
    private static void end(GuiGraphics g) { g.pose().popMatrix(); }

    public static void round(GuiGraphics g, int x, int y, int width, int height, int radius, int color) {
        round(g, x, y, width, height, radius, color, true, true);
    }
    /** A rectangle whose top and/or bottom corners are rounded; list rows use it to join into one grouped card. */
    public static void round(GuiGraphics g, int x, int y, int width, int height, int radius, int color, boolean top, boolean bottom) {
        if (width <= 0 || height <= 0) return;
        int px = Math.round(x * pixels), py = Math.round(y * pixels);
        begin(g);
        shape(g, px, py, Math.round((x + width) * pixels) - px, Math.round((y + height) * pixels) - py, Math.round(radius * pixels), color, top, bottom);
        end(g);
    }

    private static void shape(GuiGraphics g, int x, int y, int width, int height, int radius, int color, boolean top, boolean bottom) {
        if (width <= 0 || height <= 0) return;
        int r = radius(radius, width, height, top, bottom);
        if (r < 1 || !top && !bottom) { quad(g, x, y, width, height, color); return; }
        if (top && bottom && height == r * 2) { capsule(g, DISC[r], r, x, y, width, color); return; }
        int bodyTop = top ? y + r : y, bodyBottom = bottom ? y + height - r : y + height;
        quad(g, x, bodyTop, width, bodyBottom - bodyTop, color);
        int[] disc = DISC[r];
        if (top) {
            quad(g, x + r, y, width - r * 2, r, color);
            corner(g, disc, r, x, y, false, false, color);
            corner(g, disc, r, x + width - r, y, true, false, color);
        }
        if (bottom) {
            quad(g, x + r, y + height - r, width - r * 2, r, color);
            corner(g, disc, r, x, y + height - r, false, true, color);
            corner(g, disc, r, x + width - r, y + height - r, true, true, color);
        }
    }

    /** Circles and pills: two half discs and the straight part between them, at most three quads. */
    private static void capsule(GuiGraphics g, int[] at, int r, int x, int y, int width, int color) {
        int size = r * 2;
        if (width == size) { g.blit(RenderPipelines.GUI_TEXTURED, SHAPES, x, y, at[0], at[1], size, size, ATLAS, atlasHeight, color); return; }
        g.blit(RenderPipelines.GUI_TEXTURED, SHAPES, x, y, at[0], at[1], r, size, ATLAS, atlasHeight, color);
        g.blit(RenderPipelines.GUI_TEXTURED, SHAPES, x + width - r, y, at[0] + r, at[1], r, size, ATLAS, atlasHeight, color);
        quad(g, x + r, y, width - size, size, color);
    }

    public static void panel(GuiGraphics g, int x, int y, int width, int height, int radius, int border, int fill) {
        panel(g, x, y, width, height, radius, border, fill, true, true);
    }
    /** A filled shape with a one-pixel border, drawn as a fill plus a thin ring so no pixel is painted twice. */
    public static void panel(GuiGraphics g, int x, int y, int width, int height, int radius, int border, int fill, boolean top, boolean bottom) {
        if (width <= 0 || height <= 0) return;
        int px = Math.round(x * pixels), py = Math.round(y * pixels);
        int pw = Math.round((x + width) * pixels) - px, ph = Math.round((y + height) * pixels) - py;
        begin(g);
        int r = radius(Math.round(radius * pixels), pw, ph, top, bottom);
        int insetTop = top ? 1 : 0, insetBottom = bottom ? 1 : 0;
        shape(g, px + 1, py + insetTop, pw - 2, ph - insetTop - insetBottom, Math.max(0, r - 1), fill, top, bottom);
        int edgeTop = top ? Math.max(r, 1) : 0, edgeBottom = bottom ? Math.max(r, 1) : 0;
        quad(g, px, py + edgeTop, 1, ph - edgeTop - edgeBottom, border);
        quad(g, px + pw - 1, py + edgeTop, 1, ph - edgeTop - edgeBottom, border);
        int[] ring = r > 0 ? RING[r] : null;
        if (top && bottom && ring != null && ph == r * 2) {
            // A pill's border is two half rings joined by its top and bottom edges.
            g.blit(RenderPipelines.GUI_TEXTURED, SHAPES, px, py, ring[0], ring[1], r, ph, ATLAS, atlasHeight, border);
            g.blit(RenderPipelines.GUI_TEXTURED, SHAPES, px + pw - r, py, ring[0] + r, ring[1], r, ph, ATLAS, atlasHeight, border);
            quad(g, px + r, py, pw - r * 2, 1, border);
            quad(g, px + r, py + ph - 1, pw - r * 2, 1, border);
            end(g);
            return;
        }
        if (top) {
            if (ring == null) quad(g, px, py, pw, 1, border);
            else {
                quad(g, px + r, py, pw - r * 2, 1, border);
                corner(g, ring, r, px, py, false, false, border);
                corner(g, ring, r, px + pw - r, py, true, false, border);
            }
        }
        if (bottom) {
            if (ring == null) quad(g, px, py + ph - 1, pw, 1, border);
            else {
                quad(g, px + r, py + ph - 1, pw - r * 2, 1, border);
                corner(g, ring, r, px, py + ph - r, false, true, border);
                corner(g, ring, r, px + pw - r, py + ph - r, true, true, border);
            }
        }
        end(g);
    }

    /** A one-pixel horizontal line, such as a list divider. */
    public static void divider(GuiGraphics g, int x, int y, int width, int color) {
        int px = Math.round(x * pixels), py = Math.round(y * pixels);
        begin(g);
        quad(g, px, py, Math.round((x + width) * pixels) - px, 1, color);
        end(g);
    }
    /** A one-pixel vertical line. */
    public static void rule(GuiGraphics g, int x, int y, int height, int color) {
        int px = Math.round(x * pixels), py = Math.round(y * pixels);
        begin(g);
        quad(g, px, py, 1, Math.round((y + height) * pixels) - py, color);
        end(g);
    }
}
