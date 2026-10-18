package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.ShulkerIconSelector;
import dev.tntcoating.gui.ShulkerIconRulesSetting;
import dev.tntcoating.render.ShulkerIconRenderer;
import dev.tntcoating.core.ShulkerIconSelector.Style;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

public final class ShulkerIcons extends Module {
    public enum Corner { TopLeft, TopRight, BottomLeft, BottomRight }

    // Immutable container components are suitable keys; never retain an unbounded history of boxes.
    private record RenderedIcon(ItemStack stack, Integer colour) {}
    private final Map<ItemContainerContents, RenderedIcon> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ItemContainerContents, RenderedIcon> eldest) {
            return size() > 256;
        }
    };
    private final Setting<Double> scale = settings.getDefaultGroup().add(new DoubleSetting.Builder()
        .name("icon-scale").description("Size of the item overlay relative to the shulker slot.")
        .defaultValue(0.5).range(0.25, 1).sliderRange(0.25, 1).build());
    private final Setting<Corner> corner = settings.getDefaultGroup().add(new EnumSetting.Builder<Corner>()
        .name("icon-corner").description("Where the overlay sits on the shulker.")
        .defaultValue(Corner.TopRight).build());
    private final ShulkerIconRulesSetting rules = settings.createGroup("Combos").add(new ShulkerIconRulesSetting(cache::clear));
    private boolean drawingOverlay;

    public ShulkerIcons() {
        super(TntCoatingAddon.CATEGORY, "shulker-icons", "Overlays shulkers in GUIs with their most common item or a matching combo icon.");
    }

    @Override public void onActivate() { cache.clear(); }
    @Override public void onDeactivate() { cache.clear(); }

    public void drawOverlay(GuiGraphicsExtractor graphics, ItemStack shulker, int x, int y) {
        if (!isActive() || drawingOverlay || mc.gui.screen() == null || shulker.isEmpty()
            || !(shulker.getItem() instanceof BlockItem block) || !(block.getBlock() instanceof ShulkerBoxBlock)) return;
        ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
        if (contents == null) return;
        RenderedIcon icon = cache.computeIfAbsent(contents, this::selectIcon);
        if (icon.stack().isEmpty()) return;

        float size = scale.get().floatValue();
        float offset = 16 * (1 - size);
        boolean right = corner.get() == Corner.TopRight || corner.get() == Corner.BottomRight;
        boolean bottom = corner.get() == Corner.BottomLeft || corner.get() == Corner.BottomRight;
        var pose = graphics.pose();
        drawingOverlay = true;
        pose.pushMatrix();
        try {
            pose.translate(x + (right ? offset : 0), y + (bottom ? offset : 0));
            pose.scale(size, size);
            ShulkerIconRenderer.draw(graphics, icon.stack(), icon.colour());
        } finally {
            pose.popMatrix();
            drawingOverlay = false;
        }
    }

    private RenderedIcon selectIcon(ItemContainerContents contents) {
        var entries = new ArrayList<ShulkerIconSelector.Entry<Item>>();
        contents.nonEmptyItemCopyStream().forEach(stack -> entries.add(new ShulkerIconSelector.Entry<>(stack.getItem(), stack.getCount())));
        var selected = ShulkerIconSelector.selectIcon(entries, rules.get());
        return selected == null ? new RenderedIcon(ItemStack.EMPTY, null)
            : new RenderedIcon(selected.item().getDefaultInstance(), selected.style() == Style.SolidColour ? selected.colour() : null);
    }
}
