package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.ShulkerUseGuard;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.PlaceBlockEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.List;

/** Meteor-compatible fast-use controls with protection against opening a just-placed shulker. */
public final class SafeFastUse extends Module {
    public enum Mode { All, Some }

    private final ShulkerUseGuard shulkers = new ShulkerUseGuard();
    private final SettingGroup general = settings.getDefaultGroup();

    private final Setting<Mode> mode = general.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Apply the cooldown to all items or a selection.")
        .defaultValue(Mode.All).build());
    private final Setting<List<Item>> items = general.add(new ItemListSetting.Builder()
        .name("items").description("Items to speed up in Some mode.")
        .visible(() -> mode.get() == Mode.Some).build());
    private final Setting<Boolean> blocks = general.add(new BoolSetting.Builder()
        .name("blocks").description("Also speed up all block items in Some mode.")
        .defaultValue(false).visible(() -> mode.get() == Mode.Some).build());
    private final Setting<Integer> cooldown = general.add(new IntSetting.Builder()
        .name("cooldown").description("Ticks between repeated uses; vanilla uses four.")
        .defaultValue(0).min(0).sliderMax(4).build());
    private final Setting<Boolean> preventShulkerOpen = general.add(new BoolSetting.Builder()
        .name("prevent-shulker-open")
        .description("Keep holding Use after placing a shulker without opening it. Release and click again to open.")
        .defaultValue(true).onChanged(value -> shulkers.clear()).build());

    private ClientLevel world;

    public SafeFastUse() {
        super(TntCoatingAddon.CATEGORY, "safe-fast-use", "Speeds up item use without opening shulkers during the placement click.");
    }

    /** Called at the same per-hand point as Meteor's Fast Use, after its cooldown hook. */
    public int getItemUseCooldown(ItemStack stack) {
        boolean selected = mode.get() == Mode.All
            || items.get().contains(stack.getItem())
            || (blocks.get() && stack.getItem() instanceof BlockItem);
        return selected ? cooldown.get() : 4;
    }

    @Override public void onActivate() {
        reset();
        if (nothingSelected()) warning("Some mode has no items selected and blocks off, so nothing is sped up.");
    }

    @Override public String getInfoString() { return nothingSelected() ? "Nothing selected" : null; }

    private boolean nothingSelected() {
        return mode.get() == Mode.Some && items.get().isEmpty() && !blocks.get();
    }
    @Override public void onDeactivate() { reset(); }

    private void reset() {
        shulkers.clear();
        world = mc.level;
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (world != mc.level) reset();
        shulkers.update(mc.options.keyUse.isDown());
    }

    @EventHandler private void onGameLeft(GameLeftEvent event) { reset(); }

    @EventHandler private void onKey(KeyEvent event) {
        if (event.action == KeyAction.Release && mc.options.keyUse.matches(event.input)) shulkers.clear();
    }

    @EventHandler private void onMouse(MouseClickEvent event) {
        if (event.action == KeyAction.Release && mc.options.keyUse.matchesMouse(event.click)) shulkers.clear();
    }

    @EventHandler private void onPlace(PlaceBlockEvent event) {
        if (!preventShulkerOpen.get() || event.isCancelled()) return;
        // Meteor emits this before prediction. Only a live shulker at this position is ever blocked.
        shulkers.placed(event.blockPos, event.block instanceof ShulkerBoxBlock, mc.options.keyUse.isDown());
    }

    @EventHandler private void onInteract(InteractBlockEvent event) {
        if (!preventShulkerOpen.get() || mc.level == null || mc.player == null) return;
        shulkers.update(mc.options.keyUse.isDown());
        var pos = event.result.getBlockPos();
        boolean hasHeldItem = !mc.player.getMainHandItem().isEmpty() || !mc.player.getOffhandItem().isEmpty();
        if (shulkers.shouldBlock(pos, mc.level.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock,
            mc.player.isSecondaryUseActive(), hasHeldItem)) event.cancel();
    }
}
