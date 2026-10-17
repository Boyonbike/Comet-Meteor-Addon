package dev.tntcoating.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Keeps saved TNT-saving values when the setting moves out of General. */
public final class BlockCoatSettings {
    private static final String SETTING = "conservative-tnt";
    private static final String GROUP = "TNT Saving";

    private BlockCoatSettings() {}

    public static CompoundTag migrate(CompoundTag moduleTag) {
        ListTag groups = moduleTag.getCompoundOrEmpty("settings").getListOrEmpty("groups");
        CompoundTag legacy = null;
        for (Tag tag : groups) {
            if (!(tag instanceof CompoundTag group)) continue;
            String name = group.getStringOr("name", "");
            if (!name.equals("General") && !name.equals(GROUP)) continue;
            for (Tag entry : group.getListOrEmpty("settings")) {
                if (!(entry instanceof CompoundTag setting) || !setting.getStringOr("name", "").equals(SETTING)) continue;
                // An explicitly saved value in the new group always takes precedence.
                if (name.equals(GROUP)) return moduleTag;
                legacy = setting;
            }
        }
        if (legacy == null) return moduleTag;

        CompoundTag migrated = moduleTag.copy();
        ListTag migratedGroups = migrated.getCompoundOrEmpty("settings").getListOrEmpty("groups");
        CompoundTag destination = null;
        for (Tag tag : migratedGroups) {
            if (tag instanceof CompoundTag group && group.getStringOr("name", "").equals(GROUP)) {
                destination = group;
                break;
            }
        }
        if (destination == null) {
            destination = new CompoundTag();
            destination.putString("name", GROUP);
            destination.putBoolean("sectionExpanded", true);
            migratedGroups.add(destination);
        }
        ListTag migratedSettings = destination.getListOrEmpty("settings");
        migratedSettings.add(legacy.copy());
        destination.put("settings", migratedSettings);
        return migrated;
    }
}
