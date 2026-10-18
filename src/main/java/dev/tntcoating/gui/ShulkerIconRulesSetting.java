package dev.tntcoating.gui;

import dev.tntcoating.core.ShulkerIconSelector.Rule;
import dev.tntcoating.core.ShulkerIconSelector.Icon;
import dev.tntcoating.core.ShulkerIconSelector.Style;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.screens.settings.ColorSettingScreen;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** A regular Meteor setting, so combos participate in config save/load, reset and clipboard sharing. */
public final class ShulkerIconRulesSetting extends Setting<List<Rule<Item>>> {
    public ShulkerIconRulesSetting(Runnable changed) {
        super("combo-rules", "Ordered contents rules. The first enabled match selects the icon.",
            List.of(), ignored -> changed.run(), null, null);
    }

    public static void registerWidget() {
        SettingsWidgetFactory.registerCustomFactory(ShulkerIconRulesSetting.class, theme -> (table, setting) -> {
            var rules = (ShulkerIconRulesSetting) setting;
            var row = table.add(theme.horizontalList()).expandX().widget();
            var edit = row.add(theme.button("Edit Combos")).expandX().widget();
            edit.action = () -> mc.gui.setScreen(new Editor(theme, rules));
            var reset = row.add(theme.button("Reset")).widget();
            reset.action = rules::reset;
        });
    }

    @Override protected List<Rule<Item>> parseImpl(String text) { return null; }

    @Override protected boolean isValueValid(List<Rule<Item>> rules) {
        return rules != null && rules.stream().allMatch(rule -> rule != null && rule.icon() != null
            && rule.icon() != Items.AIR && rule.required().stream().allMatch(item -> item != null && item != Items.AIR));
    }

    @Override public boolean set(List<Rule<Item>> rules) {
        return rules != null && super.set(List.copyOf(rules));
    }

    @Override protected CompoundTag save(CompoundTag tag) {
        var list = new ListTag();
        for (Rule<Item> rule : get()) {
            var entry = new CompoundTag();
            entry.putString("name", rule.name());
            entry.putBoolean("enabled", rule.enabled());
            entry.putString("icon", BuiltInRegistries.ITEM.getKey(rule.icon()).toString());
            entry.putBoolean("solid-colour", rule.style() == Style.SolidColour);
            entry.putInt("colour", rule.colour());
            var required = new ListTag();
            for (Item item : rule.required()) required.add(StringTag.valueOf(BuiltInRegistries.ITEM.getKey(item).toString()));
            entry.put("required", required);
            list.add(entry);
        }
        tag.put("value", list);
        return tag;
    }

    @Override protected List<Rule<Item>> load(CompoundTag tag) {
        var rules = new ArrayList<Rule<Item>>();
        for (Tag raw : tag.getListOrEmpty("value")) {
            if (!(raw instanceof CompoundTag entry)) continue;
            Item icon = resolve(entry.getStringOr("icon", ""));
            if (icon == null) continue;
            var required = new ArrayList<Item>();
            boolean valid = true;
            for (Tag itemTag : entry.getListOrEmpty("required")) {
                Item item = resolve(itemTag.asString().orElse(""));
                if (item == null) { valid = false; break; }
                required.add(item);
            }
            // Never turn a corrupt/unknown requirement into a broader matching rule.
            if (valid) rules.add(new Rule<>(entry.getStringOr("name", "Combo"),
                entry.getBooleanOr("enabled", true), required, icon, readStyle(entry), entry.getIntOr("colour", 0xffffffff)));
        }
        value = List.copyOf(rules);
        return value;
    }

    private static Item resolve(String text) {
        Identifier id = Identifier.tryParse(text);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == Items.AIR ? null : item;
    }

    private static Style readStyle(CompoundTag entry) {
        if (entry.contains("solid-colour")) return entry.getBooleanOr("solid-colour", false) ? Style.SolidColour : Style.Normal;
        try { return Style.valueOf(entry.getStringOr("style", "Normal")); }
        catch (IllegalArgumentException ignored) { return Style.Normal; }
    }

    private static final class Editor extends WindowScreen {
        private final ShulkerIconRulesSetting rules;

        private Editor(GuiTheme theme, ShulkerIconRulesSetting rules) {
            super(theme, "Shulker Icon Combos");
            this.rules = rules;
        }

        @Override public void initWidgets() {
            add(theme.label("First matching combo wins. All required items must be present; extras are allowed."));
            add(theme.label("Damage, enchantments and item names are ignored. Empty requirements never match."));
            for (int index = 0; index < rules.get().size(); index++) addRule(index);
            var addRule = add(theme.button("Add Combo")).expandX().widget();
            addRule.action = () -> {
                var updated = new ArrayList<>(rules.get());
                updated.add(new Rule<>("Combo " + (updated.size() + 1), true, List.of(), Items.DIAMOND_CHESTPLATE));
                rules.set(updated);
                reload();
            };
        }

        private void addRule(int index) {
            Rule<Item> rule = rules.get().get(index);
            var section = add(theme.section((index + 1) + ". " + rule.name(), false)).expandX().widget();
            var draft = new Draft(index, rule);
            section.add(theme.settings(draft.settings)).expandX();
            var iconRow = section.add(theme.horizontalList()).expandX().widget();
            iconRow.add(theme.label("Icon:"));
            var preview = new ShulkerIconPicker.Preview(() -> new Icon<>(draft.icon, draft.style(), draft.colour.get().getPacked()));
            preview.theme = theme;
            iconRow.add(preview);
            var iconName = iconRow.add(theme.label(Names.get(draft.icon))).widget();
            var select = iconRow.add(theme.button("Select Icon")).widget();
            select.action = () -> mc.gui.setScreen(new ShulkerIconPicker(theme, draft.style(), draft.colour.get().getPacked(), item -> {
                draft.icon = item;
                draft.commit();
                iconName.set(Names.get(item));
            }));
            var colourRow = section.add(theme.horizontalList()).expandX().widget();
            colourRow.add(theme.quad(draft.colour.get()));
            var colourButton = colourRow.add(theme.button("Select Colour")).widget();
            colourButton.action = () -> mc.gui.setScreen(new ColorSettingScreen(theme, draft.colour));
            var solid = colourRow.add(theme.checkbox(draft.solid.get())).widget();
            solid.tooltip = "On: use the selected solid colour. Off: use the original item design.";
            solid.action = () -> draft.solid.set(solid.checked);
            colourRow.add(theme.label("Solid Colour"));
            var controls = section.add(theme.horizontalList()).expandX().widget();
            var up = controls.add(theme.button("Move Up")).widget();
            up.action = () -> move(index, index - 1);
            var down = controls.add(theme.button("Move Down")).widget();
            down.action = () -> move(index, index + 1);
            var remove = controls.add(theme.button("Delete")).widget();
            remove.action = () -> {
                var updated = new ArrayList<>(rules.get());
                updated.remove(index);
                rules.set(updated);
                reload();
            };
        }

        private void move(int from, int to) {
            if (to < 0 || to >= rules.get().size()) return;
            var updated = new ArrayList<>(rules.get());
            Collections.swap(updated, from, to);
            rules.set(updated);
            reload();
        }

        /** Standard Meteor pickers commit immediately, including when a child picker is open. */
        private final class Draft {
            private final Settings settings = new Settings();
            private final int index;
            private final Setting<String> name;
            private final Setting<Boolean> enabled;
            private final Setting<List<Item>> required;
            private final Setting<Boolean> solid;
            private final Setting<SettingColor> colour;
            private Item icon;
            private Draft(int index, Rule<Item> rule) {
                this.index = index;
                this.icon = rule.icon();
                var group = settings.getDefaultGroup();
                name = group.add(new StringSetting.Builder().name("name").defaultValue(rule.name())
                    .onChanged(ignored -> commit()).build());
                enabled = group.add(new BoolSetting.Builder().name("enabled").defaultValue(rule.enabled())
                    .onChanged(ignored -> commit()).build());
                required = group.add(new ItemListSetting.Builder().name("required-items")
                    .description("At least one of EACH selected item must be inside the shulker.")
                    .defaultValue(rule.required()).filter(item -> item != Items.AIR)
                    .onChanged(ignored -> commit()).build());
                solid = new BoolSetting.Builder().name("solid-colour")
                    .description("Use the selected colour instead of the original item design.")
                    .defaultValue(rule.style() == Style.SolidColour)
                    .onChanged(ignored -> commit()).build();
                colour = new ColorSetting.Builder().name("icon-colour")
                    .description("Colour used by Solid Colour icons.")
                    .defaultValue(new SettingColor(rule.colour()))
                    .onChanged(ignored -> commit()).build();
            }

            private Style style() { return solid.get() ? Style.SolidColour : Style.Normal; }

            private void commit() {
                var updated = new ArrayList<>(rules.get());
                updated.set(index, new Rule<>(name.get(), enabled.get(), required.get(), icon, style(), colour.get().getPacked()));
                rules.set(updated);
            }
        }
    }
}
