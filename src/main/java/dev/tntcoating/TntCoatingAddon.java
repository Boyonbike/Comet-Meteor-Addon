package dev.tntcoating;

import dev.tntcoating.compat.MeteorUpgrades;
import dev.tntcoating.core.InventorySlotLoan;
import dev.tntcoating.core.InventoryWeaponAttack;
import dev.tntcoating.modules.AdaptiveElytraFly;
import dev.tntcoating.modules.AutoWasp;
import dev.tntcoating.modules.AllayFarm;
import dev.tntcoating.modules.AllayShulkerKiller;
import dev.tntcoating.modules.AngleCalculator;
import dev.tntcoating.modules.AntiSpawnSet;
import dev.tntcoating.modules.NoHurtCam;
import dev.tntcoating.modules.BaritoneElytra;
import dev.tntcoating.modules.BlockCoat;
import dev.tntcoating.modules.ChatTools;
import dev.tntcoating.modules.DubCounter;
import dev.tntcoating.modules.ElytraLog;
import dev.tntcoating.modules.CallToArms;
import dev.tntcoating.modules.StashReport;
import dev.tntcoating.modules.FastStorageESP;
import dev.tntcoating.modules.SafeFastUse;
import dev.tntcoating.modules.ShulkerIcons;
import dev.tntcoating.gui.ShulkerIconRulesSetting;
import dev.tntcoating.hud.CometHud;
import dev.tntcoating.render.ShulkerIconRenderer;
import dev.tntcoating.modules.SmartSponge;
import dev.tntcoating.modules.Speedmine;
import dev.tntcoating.modules.WaypointDelete;
import dev.tntcoating.modules.XaeroWaypoints;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.combat.AutoWeapon;
import meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import meteordevelopment.meteorclient.systems.modules.player.AutoTool;
import meteordevelopment.meteorclient.systems.modules.player.AutoReplenish;
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
        ShulkerIconRulesSetting.registerWidget();
        CometHud.register();
        MeteorClient.EVENT_BUS.subscribe(InventorySlotLoan.INSTANCE);
        MeteorClient.EVENT_BUS.subscribe(InventoryWeaponAttack.INSTANCE);
        MeteorClient.EVENT_BUS.subscribe(ShulkerIconRenderer.RELOAD_LISTENER);
        Modules modules = Modules.get();
        List<Module> ordered = new ArrayList<>();
        // Flight and flight safety.
        ordered.add(new AdaptiveElytraFly());
        ordered.add(new AutoWasp());
        ordered.add(new BaritoneElytra());
        ordered.add(new ElytraLog());
        ordered.add(new AngleCalculator());
        // Rendering, storage discovery and waypoints.
        ordered.add(new FastStorageESP());
        ordered.add(new DubCounter());
        ordered.add(new ShulkerIcons());
        ordered.add(new NoHurtCam());
        ordered.add(new XaeroWaypoints());
        ordered.add(new WaypointDelete());
        ordered.add(new ChatTools());
        ordered.add(new CallToArms());
        ordered.add(new StashReport());
        // Building and block use.
        upgraded(ordered, modules.get(Scaffold.class), "ScaffoldMixin");
        ordered.add(new BlockCoat());
        ordered.add(new SmartSponge());
        ordered.add(new Speedmine());
        ordered.add(new SafeFastUse());
        ordered.add(new AntiSpawnSet());
        // Combat and inventory.
        upgraded(ordered, modules.get(AutoTool.class), "AutoToolMixin");
        upgraded(ordered, modules.get(AutoWeapon.class), "AutoWeaponMixin");
        upgraded(ordered, modules.get(InventoryTweaks.class), "InventoryTweaksMixin");
        upgraded(ordered, modules.get(AutoReplenish.class), "AutoReplenishMixin");
        // Allays.
        ordered.add(new AllayFarm());
        ordered.add(new AllayShulkerKiller());
        orderedModules = List.copyOf(ordered);
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
        Map<String, String> skipped = MeteorUpgrades.disabled();
        if (!skipped.isEmpty()) {
            skipped.forEach((mixin, reason) -> MeteorClient.LOG.warn("Comet upgrade {} is off for this Meteor build: {}", mixin, reason));
            MeteorClient.EVENT_BUS.subscribe(new SkippedUpgradesNotice(skipped.keySet()));
        }
    }

    /** A stock module appears under Comet only when Comet's upgrade to it was applied to this Meteor build. */
    private static void upgraded(List<Module> ordered, Module module, String mixin) {
        if (module != null && MeteorUpgrades.applied(mixin)) ordered.add(module);
    }

    /** Tells the player once, on the first world join, which upgrades this Meteor build could not take. */
    private static final class SkippedUpgradesNotice {
        private final String names;

        private SkippedUpgradesNotice(Collection<String> mixins) {
            names = String.join(", ", mixins.stream().map(name -> name.replace("Mixin", "")).toList());
        }

        @EventHandler private void onJoin(GameJoinedEvent event) {
            MeteorClient.EVENT_BUS.unsubscribe(this);
            ChatUtils.warningPrefix("Comet", "This Meteor build changed parts Comet upgrades, so these stay stock: %s. See the log for details.", names);
        }
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
