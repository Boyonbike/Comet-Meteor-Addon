/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.modules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.AutoWalk;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import net.minecraft.world.item.Item;

/** Borrows existing Meteor modules and restores their pre-trip settings and enabled preference. */
final class BoatAutomationLease {
    private record Borrowed(boolean enabled, Map<Setting<?>, Object> values, net.minecraft.nbt.CompoundTag original) {}
    private final Map<Module, Borrowed> borrowed = new LinkedHashMap<>();

    void walking(boolean wanted) {
        AutoWalk walk = Modules.get().get(AutoWalk.class);
        if (!wanted) { release(walk); return; }
        Borrowed previous = borrowed.get(walk);
        if (previous != null && !previous.values().isEmpty()) { walk.enable(); return; }
        Map<Setting<?>, Object> values = previous == null ? new LinkedHashMap<>() : previous.values();
        if (previous == null) borrowed.put(walk, new Borrowed(walk.isActive(), values, walk.toTag().copy()));
        set(walk, values, "mode", AutoWalk.Mode.Simple);
        set(walk, values, "simple-direction", AutoWalk.Direction.Forwards);
        set(walk, values, "disable-on-y-change", false);
        walk.enable();
    }

    void positioningWalk() {
        AutoWalk walk = Modules.get().get(AutoWalk.class);
        borrowed.computeIfAbsent(walk, module -> new Borrowed(module.isActive(), new LinkedHashMap<>(), module.toTag().copy()));
        walk.disable();
    }

    void direction(boolean owned) {
        Module angle = Modules.get().get(AngleCalculator.class);
        if (!owned) { release(angle); return; }
        borrowed.computeIfAbsent(angle, module -> new Borrowed(module.isActive(), new LinkedHashMap<>(), module.toTag().copy()));
        angle.disable();
    }

    @SuppressWarnings("unchecked")
    void eating(boolean wanted, double health, int hunger, boolean inventorySafe) {
        AutoEat eat = Modules.get().get(AutoEat.class);
        if (!wanted) { release(eat); return; }
        Borrowed previous = borrowed.computeIfAbsent(eat,
            module -> new Borrowed(module.isActive(), new LinkedHashMap<>(), module.toTag().copy()));
        Map<Setting<?>, Object> values = previous.values();
        List<Item> original = (List<Item>) values.getOrDefault(eat.blacklist, eat.blacklist.get());
        var player = meteordevelopment.meteorclient.MeteorClient.mc.player;
        List<Item> blacklist = dev.tntcoating.core.BoatSupplies.eatingBlacklist(original,
            player == null || player.getFoodData().needsFood());
        set(eat, values, "blacklist", blacklist);
        set(eat, values, "search-inventory", true);
        set(eat, values, "threshold-mode", AutoEat.ThresholdMode.Any);
        set(eat, values, "health-threshold", health);
        set(eat, values, "hunger-threshold", hunger);
        if (inventorySafe) eat.enable(); else eat.disable();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void set(Module module, Map<Setting<?>, Object> values, String name, Object value) {
        Setting setting = module.settings.get(name);
        if (setting == null) return;
        Object previous = setting.get();
        values.putIfAbsent(setting, previous instanceof List<?> list ? new ArrayList<>(list) : previous);
        if (!java.util.Objects.equals(previous, value)) setting.set(value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void release(Module module) {
        Borrowed previous = borrowed.remove(module);
        if (previous == null) return;
        // End item use or walking before restoring settings that may restart a path manager.
        module.disable();
        for (var entry : previous.values().entrySet()) ((Setting) entry.getKey()).set(entry.getValue());
        if (previous.enabled()) module.enable();
    }

    void restoreSavedPreferences(net.minecraft.nbt.CompoundTag root) {
        for (var entry : borrowed.entrySet()) {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Setting<?> setting : entry.getValue().values().keySet()) names.add(setting.name);
            dev.tntcoating.core.BorrowedModuleConfig.restore(root, entry.getKey().name, entry.getValue().original(), names);
        }
    }

    void release() {
        for (Module module : new ArrayList<>(borrowed.keySet())) release(module);
    }
}
