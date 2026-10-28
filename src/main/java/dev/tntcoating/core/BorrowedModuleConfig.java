/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Prevent temporary trip settings and module activation from replacing independent saved preferences. */
public final class BorrowedModuleConfig {
    private BorrowedModuleConfig() {}

    public static void restorePaused(CompoundTag root, Set<String> pausedNames) {
        for (Tag tag : root.getListOrEmpty("modules"))
            if (tag instanceof CompoundTag module && pausedNames.contains(module.getStringOr("name", "")))
                module.putBoolean("active", true);
    }

    public static void restore(CompoundTag root, String moduleName, CompoundTag original, Set<String> borrowedSettings) {
        Map<String, CompoundTag> originals = new HashMap<>();
        for (Tag groupTag : original.getCompoundOrEmpty("settings").getListOrEmpty("groups")) {
            if (!(groupTag instanceof CompoundTag group)) continue;
            for (Tag value : group.getListOrEmpty("settings")) if (value instanceof CompoundTag setting)
                originals.put(setting.getStringOr("name", ""), setting);
        }
        for (Tag moduleTag : root.getListOrEmpty("modules")) {
            if (!(moduleTag instanceof CompoundTag module) || !moduleName.equals(module.getStringOr("name", ""))) continue;
            module.putBoolean("active", original.getBooleanOr("active", false));
            for (Tag groupTag : module.getCompoundOrEmpty("settings").getListOrEmpty("groups")) {
                if (!(groupTag instanceof CompoundTag group)) continue;
                var values = group.getListOrEmpty("settings");
                for (int i = 0; i < values.size(); i++) if (values.get(i) instanceof CompoundTag setting) {
                    String name = setting.getStringOr("name", "");
                    if (borrowedSettings.contains(name) && originals.containsKey(name)) values.set(i, originals.get(name).copy());
                }
            }
        }
    }
}
