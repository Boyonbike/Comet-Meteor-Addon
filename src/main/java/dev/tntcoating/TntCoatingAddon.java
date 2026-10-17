package dev.tntcoating;

import dev.tntcoating.modules.AdaptiveElytraFly;
import dev.tntcoating.modules.BaritoneElytra;
import dev.tntcoating.modules.BlockCoat;
import dev.tntcoating.modules.ElytraLog;
import dev.tntcoating.modules.ElytraReplace;
import dev.tntcoating.modules.HomePacketLog;
import dev.tntcoating.modules.FastStorageESP;
import dev.tntcoating.modules.SafeFastUse;
import dev.tntcoating.modules.SmartSponge;
import dev.tntcoating.modules.StashHunterFly;
import dev.tntcoating.modules.WaypointDelete;
import dev.tntcoating.modules.XaeroWaypoints;
import java.util.Comparator;
import java.util.List;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.combat.AutoTotem;
import meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import meteordevelopment.meteorclient.systems.modules.render.Trajectories;
import net.minecraft.world.item.Items;

public final class TntCoatingAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Comet", () -> Items.TNT.getDefaultInstance());

    private static List<Module> orderedModules = List.of();

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public void onInitialize() {
        Modules modules = Modules.get();
        orderedModules = List.of(
            // Flight and flight safety.
            new AdaptiveElytraFly(),
            new BaritoneElytra(),
            new StashHunterFly(),
            new ElytraReplace(),
            new ElytraLog(),
            // Rendering, storage discovery and waypoints.
            new FastStorageESP(),
            modules.get(Trajectories.class),
            new XaeroWaypoints(),
            new WaypointDelete(),
            // Building and block use.
            modules.get(Scaffold.class),
            new BlockCoat(),
            new SmartSponge(),
            new SafeFastUse(),
            // Combat and inventory.
            modules.get(AutoTotem.class),
            modules.get(InventoryTweaks.class),
            // Diagnostics.
            new HomePacketLog()
        );
        orderedModules.forEach(module -> {
            if (module.category == CATEGORY) {
                module.settings.forEach(group -> group.sectionExpanded = false);
                modules.add(module);
            } else {
                // A second menu entry for the original instance, sharing settings, binds and state.
                // Keep its original registration and category intact.
                modules.getGroup(CATEGORY).add(module);
            }
        });
    }

    /** Restore Comet's functional grouping after Meteor alphabetizes every category. */
    public static void sortModuleGroup() {
        Modules.get().getGroup(CATEGORY).sort(Comparator.comparingInt(module -> {
            int index = orderedModules.indexOf(module);
            return index >= 0 ? index : orderedModules.size();
        }));
    }

    @Override
    public String getPackage() {
        return "dev.tntcoating";
    }
}
