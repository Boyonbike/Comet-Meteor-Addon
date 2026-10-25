package dev.tntcoating.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Converts the old separate module records before Meteor resolves their names. */
public final class HighwayFlyConfigMigration {
    private HighwayFlyConfigMigration() {}

    /** Rehomes saved values using the module's current setting names and groups before Meteor loads them. */
    public static void regroupHighwaySettings(CompoundTag root, Map<String, String> destinationGroups) {
        if (destinationGroups.isEmpty()) return;
        for (Tag entry : root.getListOrEmpty("modules")) {
            if (!(entry instanceof CompoundTag module) || !module.getStringOr("name", "").equals("highway-fly")) continue;
            CompoundTag settings = module.getCompoundOrEmpty("settings");
            ListTag groups = settings.getListOrEmpty("groups");
            Map<String, CompoundTag> groupsByName = new LinkedHashMap<>();
            var originalGroups = new ArrayList<CompoundTag>();
            Set<String> destinationValues = new HashSet<>();
            for (Tag candidate : groups) {
                if (!(candidate instanceof CompoundTag group)) continue;
                String groupName = group.getStringOr("name", "");
                originalGroups.add(group);
                groupsByName.putIfAbsent(groupName, group);
                for (Tag value : group.getListOrEmpty("settings")) {
                    if (!(value instanceof CompoundTag setting)) continue;
                    String name = setting.getStringOr("name", "");
                    if (groupName.equals(destinationGroups.get(name))) destinationValues.add(name);
                }
            }

            boolean changed = false;
            for (CompoundTag oldGroup : originalGroups) {
                String oldName = oldGroup.getStringOr("name", "");
                ListTag values = oldGroup.getListOrEmpty("settings");
                boolean groupChanged = false;
                for (int i = 0; i < values.size(); i++) {
                    if (!(values.get(i) instanceof CompoundTag setting)) continue;
                    String name = setting.getStringOr("name", "");
                    String destination = destinationGroups.get(name);
                    if (destination == null || destination.equals(oldName)) continue;
                    if (destinationValues.add(name)) {
                        CompoundTag targetGroup = groupsByName.get(destination);
                        if (targetGroup == null) {
                            targetGroup = group(destination, new ListTag());
                            targetGroup.putBoolean("sectionExpanded", oldGroup.getBooleanOr("sectionExpanded", true));
                            groups.add(targetGroup);
                            groupsByName.put(destination, targetGroup);
                        }
                        ListTag targetValues = targetGroup.getListOrEmpty("settings");
                        targetValues.add(setting.copy());
                        targetGroup.put("settings", targetValues);
                    }
                    values.remove(i--);
                    groupChanged = true;
                }
                if (groupChanged) {
                    oldGroup.put("settings", values);
                    changed = true;
                }
            }
            if (changed) {
                settings.put("groups", groups);
                module.put("settings", settings);
            }
        }
    }

    /** Save the user's preference, rather than Highway Fly's temporary retry settings. */
    public static void restoreReconnectPreference(CompoundTag root, boolean active, double delay) {
        for (Tag entry : root.getListOrEmpty("modules")) {
            if (!(entry instanceof CompoundTag module) || !module.getStringOr("name", "").equals("auto-reconnect")) continue;
            module.putBoolean("active", active);
            CompoundTag settings = module.getCompoundOrEmpty("settings");
            ListTag groups = settings.getListOrEmpty("groups");
            CompoundTag general = null;
            for (Tag candidate : groups) {
                if (candidate instanceof CompoundTag group && group.getStringOr("name", "").equals("General")) {
                    general = group;
                    break;
                }
            }
            if (general == null) {
                general = group("General", new ListTag());
                groups.add(general);
            }
            ListTag values = general.getListOrEmpty("settings");
            CompoundTag delayTag = null;
            for (Tag value : values) {
                if (value instanceof CompoundTag setting && setting.getStringOr("name", "").equals("delay")) {
                    delayTag = setting;
                    break;
                }
            }
            if (delayTag == null) {
                delayTag = new CompoundTag();
                delayTag.putString("name", "delay");
                values.add(delayTag);
            }
            delayTag.putDouble("value", delay);
            general.put("settings", values);
            settings.put("groups", groups);
            module.put("settings", settings);
            return;
        }
    }

    public static void migrate(CompoundTag tag) {
        migrate(tag, false);
    }

    public static void migrate(CompoundTag tag, boolean recoveryPending) {
        ListTag modules = tag.getListOrEmpty("modules");
        Map<String, CompoundTag> legacy = new LinkedHashMap<>();
        for (Tag entry : modules) {
            if (!(entry instanceof CompoundTag module)) continue;
            String name = module.getStringOr("name", "");
            if (name.equals("highway-fly")) return;
            if (name.equals("smart-auto-fly") || name.equals("auto-set-home")
                || name.equals("auto-lobby") || name.equals("anti-portal")) legacy.put(name, module);
        }
        if (legacy.isEmpty()) return;

        CompoundTag flight = legacy.get("smart-auto-fly");
        CompoundTag merged = flight == null ? new CompoundTag() : flight.copy();
        merged.putString("name", "highway-fly");
        merged.putBoolean("active", enabled(flight) || recoveryPending && enabled(legacy.get("auto-lobby")));
        CompoundTag settings = merged.getCompoundOrEmpty("settings");
        ListTag groups = settings.getListOrEmpty("groups");
        ListTag switches = new ListTag();
        switches.add(toggle("auto-set-home", enabled(legacy.get("auto-set-home"))));
        switches.add(toggle("lobby-recovery", enabled(legacy.get("auto-lobby"))));
        switches.add(toggle("anti-portal", enabled(legacy.get("anti-portal"))));
        groups.add(group("Helpers", switches));
        appendGroup(groups, legacy.get("auto-set-home"), "Auto Set Home");
        appendGroup(groups, legacy.get("auto-lobby"), "Lobby Recovery");
        appendGroup(groups, legacy.get("anti-portal"), "Portal Protection");
        settings.put("groups", groups);
        merged.put("settings", settings);

        ListTag migrated = new ListTag();
        for (Tag entry : modules) {
            if (entry instanceof CompoundTag module && legacy.containsKey(module.getStringOr("name", ""))) continue;
            migrated.add(entry);
        }
        migrated.add(merged);
        tag.put("modules", migrated);
    }

    private static boolean enabled(CompoundTag module) {
        return module != null && module.getBooleanOr("active", false);
    }

    private static CompoundTag toggle(String name, boolean enabled) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putBoolean("value", enabled);
        return tag;
    }

    private static CompoundTag group(String name, ListTag values) {
        CompoundTag group = new CompoundTag();
        group.putString("name", name);
        group.put("settings", values);
        return group;
    }

    private static void appendGroup(ListTag groups, CompoundTag module, String name) {
        if (module == null) return;
        ListTag values = new ListTag();
        for (Tag entry : module.getCompoundOrEmpty("settings").getListOrEmpty("groups")) {
            if (!(entry instanceof CompoundTag oldGroup)) continue;
            for (Tag value : oldGroup.getListOrEmpty("settings")) {
                if (!(value instanceof CompoundTag setting)) continue;
                String settingName = setting.getStringOr("name", "");
                if (name.equals("Lobby Recovery") && settingName.equals("resume-auto-set-home")) continue;
                CompoundTag copy = setting.copy();
                if (name.equals("Lobby Recovery") && settingName.equals("debug")) copy.putString("name", "lobby-debug");
                values.add(copy);
            }
        }
        groups.add(group(name, values));
    }
}
