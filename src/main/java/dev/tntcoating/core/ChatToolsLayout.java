package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;

/** Window geometry shared by every size: a header bar with the page tabs, a scrolling body and a composer bar. */
public final class ChatToolsLayout {
    public static final int HEADER = 32;
    public static final int FOOTER = 36;
    public static final int PADDING = 12;
    public static final int COMPOSER = 20;

    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean contains(double mx, double my) { return mx >= x && mx < right() && my >= y && my < bottom(); }
    }
    /** {@code content} spans from the header to the composer bar so pages scroll under both dividers. */
    public record Frame(Rect header, Rect content, Rect footer, Rect composer) {}
    private ChatToolsLayout() {}

    public static Frame frame(int x, int y, int width, int height) {
        var header = new Rect(x, y, width, HEADER);
        var footer = new Rect(x, y + height - FOOTER, width, FOOTER);
        int inner = Math.max(1, width - PADDING * 2);
        return new Frame(header, new Rect(x + PADDING, header.bottom(), inner, Math.max(1, footer.y() - header.bottom())), footer,
            new Rect(x + PADDING, footer.y() + (FOOTER - COMPOSER) / 2, inner, COMPOSER));
    }

    /** Favourites fill each horizontal row before wrapping; even a compact 320px frame fits two tiles. */
    public static List<Rect> favoriteTiles(int x, int y, int width, int count) {
        if (count <= 0 || width <= 0) return List.of();
        int gap = 6;
        int columns = Math.max(1, (width + gap) / 136);
        int cellWidth = Math.min(180, (width - gap * (columns - 1)) / columns);
        List<Rect> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(new Rect(x + i % columns * (cellWidth + gap), y + i / columns * 46, cellWidth, 40));
        return List.copyOf(result);
    }
}
