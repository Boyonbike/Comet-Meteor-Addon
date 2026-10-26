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
            boolean changed = destinationGroups.containsKey("minimum-health") && renameDamageLimit(groups);
            if (destinationGroups.containsKey("home-replaced-events")) changed |= renameHomeWebhook(groups);
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

    private static boolean renameHomeWebhook(ListTag groups) {
        boolean explicit = false;
        for (Tag entry : groups) if (entry instanceof CompoundTag group) {
            for (Tag value : group.getListOrEmpty("settings"))
                if (value instanceof CompoundTag setting && setting.getStringOr("name", "").equals("home-replaced-events")) explicit = true;
        }
        boolean changed = false;
        for (Tag entry : groups) if (entry instanceof CompoundTag group) {
            ListTag values = group.getListOrEmpty("settings");
            for (int i = 0; i < values.size(); i++) {
                if (!(values.get(i) instanceof CompoundTag setting)) continue;
                String name = setting.getStringOr("name", "");
                if (name.equals("status-interval-minutes")) { values.remove(i--); changed = true; }
                if (!name.equals("periodic-status")) continue;
                if (explicit) values.remove(i--);
                else { setting.putString("name", "home-replaced-events"); explicit = true; }
                changed = true;
            }
            group.put("settings", values);
        }
        return changed;
    }

    /** Explicit current values win even when they are still saved in an old group. */
    private static boolean renameDamageLimit(ListTag groups) {
        boolean hasMinimumHealth = false;
        for (Tag entry : groups) {
            if (!(entry instanceof CompoundTag group)) continue;
            for (Tag value : group.getListOrEmpty("settings")) {
                if (value instanceof CompoundTag setting
                    && setting.getStringOr("name", "").equals("minimum-health")) hasMinimumHealth = true;
            }
        }

        boolean changed = false;
        for (Tag entry : groups) {
            if (!(entry instanceof CompoundTag group)) continue;
            ListTag values = group.getListOrEmpty("settings");
            for (int i = 0; i < values.size(); i++) {
                if (!(values.get(i) instanceof CompoundTag setting)
                    || !setting.getStringOr("name", "").equals("damage-limit")) continue;
                if (hasMinimumHealth) values.remove(i--);
                else {
                    double minimumHealth = setting.getDoubleOr("value", 8);
                    if (!Double.isFinite(minimumHealth) || minimumHealth < 0 || minimumHealth >= 20) minimumHealth = 8;
                    setting.putString("name", "minimum-health");
                    setting.putDouble("value", minimumHealth);
                }
                group.put("settings", values);
                changed = true;
            }
        }
        return changed;
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

    /** Upgrades both historical layouts to independent helpers before Meteor resolves module names. */
    public static void migrateIndependent(CompoundTag root, boolean recoveryPending) {
        ListTag modules = root.getListOrEmpty("modules");
        Map<String, CompoundTag> originals = new LinkedHashMap<>();
        for (Tag entry : modules) if (entry instanceof CompoundTag module) {
            String name = module.getStringOr("name", "");
            if (Set.of("auto-set-home", "anti-portal", "auto-lobby").contains(name)) originals.put(name, module.copy());
        }
        migrate(root, recoveryPending);
        modules = root.getListOrEmpty("modules");
        CompoundTag highway = findModule(modules, "highway-fly");
        CompoundTag dashboard = findModule(modules, "highway-dashboard");
        if (highway == null && dashboard != null) {
            highway = new CompoundTag();
            highway.putString("name", "highway-fly");
            highway.putBoolean("active", false);
            modules.add(highway);
        }
        if (highway == null) return;
        CompoundTag settings = highway.getCompoundOrEmpty("settings");
        ListTag groups = settings.getListOrEmpty("groups");
        splitHelper(modules, groups, originals, "auto-set-home", Set.of("home", "timing", "interval-seconds", "cooldown-buffer-seconds", "retry-delay-seconds", "override", "dialog-match", "button-match", "timeout-seconds"));
        splitHelper(modules, groups, originals, "anti-portal", Set.of("nether-portals", "end-portals", "notify"));
        splitHelper(modules, groups, originals, "auto-lobby", Set.of("auto-reconnect", "reconnect-delay", "resume-flight", "cracked-account", "login-password", "login-timeout", "enter-portal", "use-baritone", "search-radius", "search-height", "join-delay", "rescan-delay", "lobby-debug"));
        if (dashboard != null) {
            ListTag values = new ListTag();
            values.add(toggle("dashboard-enabled", enabled(dashboard)));
            for (Tag entry : dashboard.getCompoundOrEmpty("settings").getListOrEmpty("groups")) {
                if (!(entry instanceof CompoundTag group)) continue;
                for (Tag value : group.getListOrEmpty("settings")) if (value instanceof CompoundTag setting) {
                    CompoundTag moved = setting.copy();
                    moved.putString("name", "dashboard-" + setting.getStringOr("name", ""));
                    values.add(moved);
                }
            }
            mergeSettings(groups, "Dashboard", values);
            modules.remove(dashboard);
        }
        settings.put("groups", groups);
        highway.put("settings", settings);
        root.put("modules", modules);
    }

    private static CompoundTag findModule(ListTag modules, String name) {
        for (Tag entry : modules) if (entry instanceof CompoundTag module
            && module.getStringOr("name", "").equals(name)) return module;
        return null;
    }

    private static void splitHelper(ListTag modules, ListTag sourceGroups,
                                    Map<String, CompoundTag> originals, String name, Set<String> names) {
        CompoundTag helper = findModule(modules, name);
        if (helper == null) {
            helper = originals.getOrDefault(name, new CompoundTag()).copy();
            helper.putString("name", name);
            if (!helper.contains("active")) helper.putBoolean("active", false);
            modules.add(helper);
        }
        CompoundTag settings = helper.getCompoundOrEmpty("settings");
        ListTag targetGroups = new ListTag();
        ListTag explicit = new ListTag();
        for (Tag entry : settings.getListOrEmpty("groups")) if (entry instanceof CompoundTag group) {
            for (Tag value : group.getListOrEmpty("settings")) if (value instanceof CompoundTag setting) {
                CompoundTag copy = setting.copy();
                if (name.equals("auto-lobby") && copy.getStringOr("name", "").equals("debug")) copy.putString("name", "lobby-debug");
                explicit.add(copy);
            }
        }
        mergeSettings(targetGroups, "General", explicit);
        ListTag moved = new ListTag();
        for (Tag entry : sourceGroups) if (entry instanceof CompoundTag group) {
            ListTag values = group.getListOrEmpty("settings");
            for (int i = 0; i < values.size(); i++) if (values.get(i) instanceof CompoundTag setting
                && names.contains(setting.getStringOr("name", ""))) {
                moved.add(setting.copy());
                values.remove(i--);
            }
            group.put("settings", values);
        }
        mergeSettings(targetGroups, "General", moved);
        settings.put("groups", targetGroups);
        helper.put("settings", settings);
    }

    /** Destination values win, independent of the old group order. */
    private static void mergeSettings(ListTag groups, String targetName, ListTag incoming) {
        Set<String> present = new HashSet<>();
        CompoundTag target = null;
        for (Tag entry : groups) if (entry instanceof CompoundTag group) {
            if (group.getStringOr("name", "").equals(targetName)) target = group;
            for (Tag value : group.getListOrEmpty("settings")) if (value instanceof CompoundTag setting)
                present.add(setting.getStringOr("name", ""));
        }
        if (target == null) { target = group(targetName, new ListTag()); groups.add(target); }
        ListTag values = target.getListOrEmpty("settings");
        for (Tag entry : incoming) if (entry instanceof CompoundTag setting
            && present.add(setting.getStringOr("name", ""))) values.add(setting.copy());
        target.put("settings", values);
    }
}
