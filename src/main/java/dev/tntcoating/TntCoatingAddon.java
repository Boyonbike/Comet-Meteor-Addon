package dev.tntcoating;

import dev.tntcoating.modules.BaritoneElytra;
import dev.tntcoating.modules.BlockCoat;
import dev.tntcoating.modules.ElytraLog;
import dev.tntcoating.modules.ElytraReplace;
import dev.tntcoating.modules.SmartSponge;
import dev.tntcoating.modules.StashHunterFly;
import dev.tntcoating.modules.WaypointDelete;
import dev.tntcoating.modules.XaeroWaypoints;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.Category;
import net.minecraft.world.item.Items;

public final class TntCoatingAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Comet", () -> Items.TNT.getDefaultInstance());

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public void onInitialize() {
        Modules.get().add(new BlockCoat());
        Modules.get().add(new SmartSponge());
        Modules.get().add(new XaeroWaypoints());
        Modules.get().add(new WaypointDelete());
        Modules.get().add(new BaritoneElytra());
        Modules.get().add(new ElytraReplace());
        Modules.get().add(new ElytraLog());
        Modules.get().add(new StashHunterFly());
    }

    @Override
    public String getPackage() {
        return "dev.tntcoating";
    }
}
