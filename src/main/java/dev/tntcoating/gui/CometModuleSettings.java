package dev.tntcoating.gui;

import java.util.List;
import java.util.Map;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

/** Shared menu links from original module settings to their Comet upgrades. */
public final class CometModuleSettings {
    private static final Map<String, List<String>> UPGRADES = Map.of(
        "elytra-fly", List.of("adaptive-elytra-fly", "baritone-elytra", "stash-hunter-elytrafly", "elytra-replace", "elytra-log"),
        "storage-esp", List.of("fast-storage-esp"),
        "fast-use", List.of("safe-fast-use"),
        "waypoints", List.of("xaero-waypoints", "waypoint-delete"),
        "stash-hunter", List.of("stash-hunter-elytrafly")
    );

    private CometModuleSettings() {}

    public static List<Module> upgradesFor(Module original) {
        Modules modules = Modules.get();
        return UPGRADES.getOrDefault(original.name, List.of()).stream()
            .map(modules::get)
            .filter(module -> module != null && module != original)
            .toList();
    }

    public static WVerticalList createLinks(GuiTheme theme, Module original) {
        List<Module> upgrades = upgradesFor(original);
        if (upgrades.isEmpty()) return null;

        var list = theme.verticalList();
        var section = list.add(theme.section("Comet", false)).expandX().widget();
        section.add(theme.label("Left click to toggle; right click for settings."));
        for (Module upgrade : upgrades) section.add(theme.module(upgrade)).expandX();
        return list;
    }
}
