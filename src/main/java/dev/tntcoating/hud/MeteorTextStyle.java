package dev.tntcoating.hud;

import java.util.List;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.hud.Alignment;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.hud.elements.TextHud;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * The look of Meteor's own text HUD elements, such as FPS and Speed: shadowed text in the HUD's text-colors with no
 * background, and the same shadow, alignment, border, scale and background settings as Meteor's elements.
 */
final class MeteorTextStyle {
    private final Setting<Boolean> shadow, customScale, background;
    private final Setting<Alignment> alignment;
    private final Setting<Integer> border;
    private final Setting<Double> scale;
    private final Setting<SettingColor> backgroundColor;

    MeteorTextStyle(Settings settings) {
        SettingGroup general = settings.getDefaultGroup();
        shadow = general.add(new BoolSetting.Builder().name("shadow").description("Renders shadow behind text.")
            .defaultValue(true).build());
        alignment = general.add(new EnumSetting.Builder<Alignment>().name("alignment").description("Horizontal alignment.")
            .defaultValue(Alignment.Auto).build());
        border = general.add(new IntSetting.Builder().name("border").description("How much space to add around the element.")
            .defaultValue(0).build());
        SettingGroup scaling = settings.createGroup("Scale");
        customScale = scaling.add(new BoolSetting.Builder().name("custom-scale")
            .description("Applies a custom scale to this hud element.").defaultValue(false).build());
        scale = scaling.add(new DoubleSetting.Builder().name("scale").description("Custom scale.")
            .visible(customScale::get).defaultValue(1).min(0.5).sliderRange(0.5, 3).build());
        SettingGroup look = settings.createGroup("Background");
        background = look.add(new BoolSetting.Builder().name("background").description("Displays background.")
            .defaultValue(false).build());
        backgroundColor = look.add(new ColorSetting.Builder().name("background-color").description("Color used for the background.")
            .visible(background::get).defaultValue(new SettingColor(25, 25, 25, 50)).build());
    }

    /**
     * Sizes the element to fit, then draws it at its current position. As in Meteor's "Speed: #1{...}" presets, a
     * line's label up to its first ": " takes the first text colour and the rest the second; a line without a label
     * is all value. A null title draws only the lines; a title is all label.
     */
    void render(HudElement element, HudRenderer renderer, String title, List<String> lines) {
        double textScale = customScale.get() ? scale.get() : Hud.get().getTextScale();
        boolean textShadow = shadow.get();
        double lineHeight = renderer.textHeight(textShadow, textScale);
        int rows = lines.size() + (title == null ? 0 : 1);
        double width = title == null ? 0 : renderer.textWidth(title, textShadow, textScale);
        for (String line : lines) width = Math.max(width, renderer.textWidth(line, textShadow, textScale));
        int space = border.get();
        element.setSize(width + space * 2, lineHeight * rows + space * 2);

        if (background.get()) {
            renderer.quad(element.x, element.y, element.getWidth(), element.getHeight(), backgroundColor.get());
        }
        double y = element.y + space;
        if (title != null) {
            row(element, renderer, title, "", y, textShadow, textScale);
            y += lineHeight;
        }
        for (String line : lines) {
            int colon = line.indexOf(": ");
            int split = colon < 0 ? 0 : colon + 2;
            row(element, renderer, line.substring(0, split), line.substring(split), y, textShadow, textScale);
            y += lineHeight;
        }
    }

    private void row(HudElement element, HudRenderer renderer, String label, String value, double y,
                     boolean textShadow, double textScale) {
        int space = border.get();
        double x = element.x + space + element.box.alignX(element.getWidth() - space * 2,
            renderer.textWidth(label + value, textShadow, textScale), alignment.get());
        if (!label.isEmpty()) x = renderer.text(label, x, y, TextHud.getSectionColor(0), textShadow, textScale);
        if (!value.isEmpty()) renderer.text(value, x, y, TextHud.getSectionColor(1), textShadow, textScale);
    }
}
