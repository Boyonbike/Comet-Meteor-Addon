package dev.tntcoating.core;

/** Mirrors vanilla chat's scaled message background and bottom margin. */
public record ChatHudBounds(int left, int right, int top) {
    public int width() { return right - left; }

    public static ChatHudBounds of(int chatWidth, double chatScale, int screenWidth, int screenHeight) {
        return of(chatWidth, chatScale, screenWidth, screenHeight, 0);
    }

    public static ChatHudBounds of(int chatWidth, double chatScale, int screenWidth, int screenHeight, int extraColumns) {
        float scale = (float) Math.max(0.01, chatScale);
        int renderedWidth = (int) Math.ceil(((int) Math.ceil(chatWidth / scale) + extraColumns + 12) * scale);
        int bottom = (int) Math.floor((screenHeight - 40) / scale);
        return new ChatHudBounds(0, Math.max(0, Math.min(screenWidth, renderedWidth)), (int) (bottom * scale) + 3);
    }
}
