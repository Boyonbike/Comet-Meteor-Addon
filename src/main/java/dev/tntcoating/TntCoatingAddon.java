package dev.tntcoating;

import dev.tntcoating.modules.BlockCoat;
import dev.tntcoating.modules.SmartSponge;
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
    }

    @Override
    public String getPackage() {
        return "dev.tntcoating";
    }
}
