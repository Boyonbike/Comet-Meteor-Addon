package dev.tntcoating.gui;

import dev.tntcoating.core.ShulkerIconSelector.Icon;
import dev.tntcoating.core.ShulkerIconSelector.Style;
import dev.tntcoating.core.ShulkerIconShape;
import dev.tntcoating.render.ShulkerIconRenderer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WItem;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Normal icons keep every item. Solid icons collapse repeated block model geometry into one choice. */
public final class ShulkerIconPicker extends WindowScreen {
    private final Style style;
    private final int colour;
    private final Consumer<Item> selected;
    private final List<Choice> choices;
    private WTable table;

    /** Search text is built once per picker: every alias's display name and registry id, lower-cased. */
    private record Choice(Item item, List<Item> aliases, String search) {}

    public ShulkerIconPicker(GuiTheme theme, Style style, int colour, Consumer<Item> selected) {
        super(theme, style == Style.Normal ? "Select Item Icon" : "Select Solid Icon Shape");
        this.style = style;
        this.colour = colour;
        this.selected = selected;
        // Rebuilt when opening the picker, so resource-pack model changes are respected.
        choices = choices(style);
    }

    @Override public void initWidgets() {
        if (style == Style.SolidColour) add(theme.label("Identical block model shapes are grouped. Search also finds their block names."));
        var filter = add(theme.textBox("")).minWidth(400).expandX().widget();
        table = add(theme.table()).expandX().widget();
        filter.action = () -> fill(filter.get());
        filter.setFocused(true);
        fill("");
    }

    private void fill(String text) {
        table.clear();
        String filter = text.trim().toLowerCase(Locale.ROOT);
        for (Choice choice : choices) {
            if (!choice.search().contains(filter)) continue;
            Item item = choice.item();
            var row = table.add(theme.horizontalList()).widget();
            var preview = new Preview(() -> new Icon<>(item, style, colour));
            preview.theme = theme;
            row.add(preview);
            row.add(theme.label(Names.get(item) + (choice.aliases().size() > 1 ? " (shared block shape)" : "")));
            var button = table.add(theme.button("Select")).expandCellX().right().widget();
            button.action = () -> { selected.accept(item); onClose(); };
            table.row();
        }
    }

    private static List<Choice> choices(Style style) {
        var groups = new LinkedHashMap<Object, List<Item>>();
        var model = new TrackingItemStackRenderState();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            Object key = item;
            if (style == Style.SolidColour && item instanceof BlockItem) {
                model.clear();
                mc.getItemModelResolver().updateForTopItem(model, item.getDefaultInstance(), ItemDisplayContext.GUI, mc.level, mc.player, 0);
                var vertices = new ArrayList<ShulkerIconShape.Point>();
                model.visitExtents(point -> vertices.add(ShulkerIconShape.Point.of(point.x(), point.y(), point.z())));
                if (!vertices.isEmpty()) key = ShulkerIconShape.key(vertices);
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        return groups.values().stream().map(items -> new Choice(items.getFirst(), List.copyOf(items), searchText(items))).toList();
    }

    private static String searchText(List<Item> items) {
        StringBuilder text = new StringBuilder();
        for (Item item : items) {
            // A separator no filter can contain keeps one alias's text from matching across into the next.
            text.append(Names.get(item).toLowerCase(Locale.ROOT)).append('\n')
                .append(BuiltInRegistries.ITEM.getKey(item)).append('\n');
        }
        return text.toString();
    }

    /** Uses the same silhouette path as inventory overlays, including deferred Meteor widget drawing. */
    public static final class Preview extends WItem {
        private final Supplier<Icon<Item>> icon;

        public Preview(Supplier<Icon<Item>> icon) {
            super(ItemStack.EMPTY);
            this.icon = icon;
        }

        @Override protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            Icon<Item> selected = icon.get();
            renderer.post(() -> ShulkerIconRenderer.withColour(selected.style() == Style.SolidColour ? selected.colour() : null,
                () -> renderer.item(selected.item().getDefaultInstance(), (int) x, (int) y, (float) theme.scale(2), false)));
        }
    }
}
