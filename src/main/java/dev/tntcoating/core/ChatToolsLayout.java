package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Window geometry shared by every size: a header bar with the page title, a sidebar with the page navigation, a scrolling
 * body and a footer holding the send queue tray above the composer.
 */
public final class ChatToolsLayout {
    public static final int HEADER = 48;
    public static final int FOOTER = 44;
    public static final int PADDING = 14;
    public static final int COMPOSER = 28;
    private static final int MIN_BODY = 100, ITEM = 30, ITEM_STEP = 32;

    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double mx, double my) { return mx >= x && mx < right() && my >= y && my < bottom(); }
    }
    /** {@code content} spans from the header to the footer so pages scroll under both dividers. */
    public record Frame(Rect header, Rect sidebar, Rect content, Rect footer, Rect tray, Rect composer) {}
    private ChatToolsLayout() {}

    public static Frame frame(int x, int y, int width, int height) { return frame(x, y, width, height, 0); }

    /** {@code tray} is the height the send queue wants; it never squeezes the body below a usable height. */
    public static Frame frame(int x, int y, int width, int height, int tray) {
        int side = width >= 640 ? 168 : 52;
        var header = new Rect(x, y, width, HEADER);
        var sidebar = new Rect(x + 1, header.bottom(), side - 1, Math.max(1, height - HEADER - 1));
        int trayHeight = Math.clamp(tray, 0, Math.max(0, height - HEADER - FOOTER - MIN_BODY));
        int footerHeight = FOOTER + trayHeight;
        var footer = new Rect(x + side, y + height - footerHeight, width - side, footerHeight);
        int inner = Math.max(1, width - side - PADDING * 2);
        int left = x + side + PADDING;
        var composer = new Rect(left, y + height - FOOTER + (FOOTER - COMPOSER) / 2, inner, COMPOSER);
        var trayRect = new Rect(left, footer.y() + (trayHeight == 0 ? 0 : 8), inner, Math.max(0, trayHeight - (trayHeight == 0 ? 0 : 8)));
        return new Frame(header, sidebar, new Rect(left, header.bottom(), inner, Math.max(1, footer.y() - header.bottom())), footer,
            trayRect, composer);
    }

    public static int navigationScrollMax(Frame frame, int count) {
        int available = Math.max(1, frame.sidebar().height() - 40);
        return Math.max(0, count * ITEM_STEP - available);
    }

    public static Rect navigationItem(Frame frame, int index, int scroll) {
        return new Rect(frame.sidebar().x() + 8, frame.sidebar().y() + 10 + index * ITEM_STEP - scroll,
            frame.sidebar().width() - 16, ITEM);
    }

    /** Favourites fill each horizontal row before wrapping; even a compact 320px frame fits two tiles. */
    public static List<Rect> favoriteTiles(int x, int y, int width, int count) {
        if (count <= 0 || width <= 0) return List.of();
        int gap = 8;
        int columns = Math.max(1, (width + gap) / 136);
        int cellWidth = Math.min(180, (width - gap * (columns - 1)) / columns);
        List<Rect> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(new Rect(x + i % columns * (cellWidth + gap), y + i / columns * 48, cellWidth, 40));
        return List.copyOf(result);
    }
}
