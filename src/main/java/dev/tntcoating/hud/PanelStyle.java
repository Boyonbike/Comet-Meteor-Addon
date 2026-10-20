package dev.tntcoating.hud;

import java.util.List;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/** The compact panel look shared by Comet's HUD elements: background, accent bar, optional title and text lines. */
final class PanelStyle {
    private static final double PADDING = 4;
    private static final double ACCENT_WIDTH = 2;

    private final Setting<Boolean> shadow, background, customScale;
    private final Setting<Double> scale;
    private final Setting<SettingColor> textColor, accentColor, backgroundColor;

    PanelStyle(Settings settings) {
        SettingGroup general = settings.getDefaultGroup();
        textColor = general.add(new ColorSetting.Builder().name("text-color").description("Colour of the text.")
            .defaultValue(new SettingColor(238, 238, 238)).build());
        accentColor = general.add(new ColorSetting.Builder().name("accent-color").description("Colour of the title and side bar.")
            .defaultValue(new SettingColor(85, 170, 255)).build());
        shadow = general.add(new BoolSetting.Builder().name("shadow").description("Draw a shadow behind the text.").defaultValue(true).build());
        SettingGroup look = settings.createGroup("Background and Scale");
        background = look.add(new BoolSetting.Builder().name("background").description("Draw the panel background and side bar.")
            .defaultValue(true).build());
        backgroundColor = look.add(new ColorSetting.Builder().name("background-color").description("Colour of the panel background.")
            .defaultValue(new SettingColor(0, 0, 0, 176)).visible(background::get).build());
        customScale = look.add(new BoolSetting.Builder().name("custom-scale").description("Use a scale other than the HUD's text scale.")
            .defaultValue(false).build());
        scale = look.add(new DoubleSetting.Builder().name("scale").description("Text scale used while custom-scale is on.")
            .defaultValue(1).min(0.5).sliderRange(0.5, 3).visible(customScale::get).build());
    }

    /** Sizes the element to fit, then draws it at its current position. A null title draws only the lines. */
    void render(HudElement element, HudRenderer renderer, String title, List<String> lines) {
        double textScale = customScale.get() ? scale.get() : Hud.get().getTextScale();
        boolean textShadow = shadow.get();
        double lineHeight = renderer.textHeight(textShadow, textScale);
        int rows = lines.size() + (title == null ? 0 : 1);
        double width = title == null ? 0 : renderer.textWidth(title, textShadow, textScale);
        for (String line : lines) width = Math.max(width, renderer.textWidth(line, textShadow, textScale));
        element.setSize(width + PADDING * 2 + ACCENT_WIDTH, lineHeight * rows + PADDING * 2);

        double x = element.x, y = element.y;
        if (background.get()) {
            renderer.quad(x, y, element.getWidth(), element.getHeight(), backgroundColor.get());
            renderer.quad(x, y, ACCENT_WIDTH, element.getHeight(), accentColor.get());
        }
        double textX = x + ACCENT_WIDTH + PADDING, textY = y + PADDING;
        if (title != null) {
            renderer.text(title, textX, textY, accentColor.get(), textShadow, textScale);
            textY += lineHeight;
        }
        for (String line : lines) {
            renderer.text(line, textX, textY, textColor.get(), textShadow, textScale);
            textY += lineHeight;
        }
    }
}
