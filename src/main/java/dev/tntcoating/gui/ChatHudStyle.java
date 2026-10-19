package dev.tntcoating.gui;

import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Compact chat controls using the current Meteor theme's colours and outlined buttons. */
public final class ChatHudStyle {
    public static final int HEIGHT = 12;
    public static final float TEXT_SCALE = 0.75f;

    private ChatHudStyle() {}

    public static int background(boolean hovered) {
        return GuiThemes.get() instanceof MeteorGuiTheme theme
            ? theme.backgroundColor.get(false, hovered, true).getPacked() : hovered ? 0xc81e1e1e : 0xc8141414;
    }

    public static int outline(boolean hovered) {
        return GuiThemes.get() instanceof MeteorGuiTheme theme
            ? theme.outlineColor.get(false, hovered, true).getPacked() : 0xff000000;
    }

    public static int accent() {
        return GuiThemes.get() instanceof MeteorGuiTheme theme ? theme.accentColor.get().getPacked() : 0xff913de2;
    }

    public static int textColor() { return GuiThemes.get() == null ? 0xffffffff : GuiThemes.get().textColor().getPacked(); }

    public static int width(String label) {
        return (int) Math.ceil(Minecraft.getInstance().font.width(label) * TEXT_SCALE) + 6;
    }

    public static void text(GuiGraphicsExtractor graphics, String label, int x, int y, int width, int color) {
        if (width <= 0) return;
        var font = Minecraft.getInstance().font;
        String clipped = font.plainSubstrByWidth(label, (int) (width / TEXT_SCALE));
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(TEXT_SCALE);
        graphics.text(font, clipped, 0, 0, color, false);
        graphics.pose().popMatrix();
    }
}
