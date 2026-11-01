/*
 * Original Comet implementation by Boyonabike.
 * Independent: written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Stage 5's persisted-settings migration for CrystalCombat.
 *
 * <p>Meteor saves settings per group. Stage 5 moved keys out of the old {@code General} and
 * {@code Ranges} groups into the Phase 8 groups; without this remap those values would be silently
 * dropped when an existing config is loaded. The migration is pure, never mutates the caller's tag,
 * ignores unknown groups/settings (they are preserved so a downgrade can still read them), and is
 * idempotent: a tag already in the new layout is returned untouched.
 *
 * <p>Explicit values already present in a destination group win over a legacy value, so re-saving a
 * config that was partially converted never regresses to the old group's value.
 */
public final class CrystalCombatSettingsMigration {

    private static final String SETTINGS = "settings";
    private static final String GROUPS = "groups";
    private static final String NAME = "name";
    private static final String RANGES = "Ranges";
    private static final String GENERAL = "General";
    private static final String PISTON = "Piston";
    private static final String LAYOUT_PREFIX = "piston-layout-";

    /** Old General keys that moved to a different Phase 8 group. */
    private static final Map<String, String> GENERAL_MOVES = Map.ofEntries(
        Map.entry("anti-suicide", "Safety"),
        Map.entry("piston-trap", PISTON),
        Map.entry("auto-cleanup", PISTON),
        Map.entry("own-only", "Direct"),
        Map.entry("motion-prediction", "Timing"),
        Map.entry("hurt-sync", "Timing"),
        Map.entry("support", "Direct"),
        Map.entry("surround-gap", "Direct"),
        Map.entry("dangerous-crystal-break", "Defence"),
        Map.entry("anti-piston", "Defence"),
        Map.entry("footprint-concurrency", "Experimental"));

    /** Old Ranges keys that moved into General or Direct. */
    private static final Map<String, String> RANGE_MOVES = Map.of(
        "target-range", GENERAL,
        "place-range", "Direct",
        "attack-range", "Direct",
        "walls-range", "Direct");

    private CrystalCombatSettingsMigration() {
    }

    /**
     * Returns the module tag with the legacy group layout rewritten into the Phase 8 layout. The same
     * instance is returned when there is nothing to migrate.
     */
    public static CompoundTag migrate(CompoundTag moduleTag) {
        Objects.requireNonNull(moduleTag, "moduleTag");
        CompoundTag settings = moduleTag.getCompoundOrEmpty(SETTINGS);
        ListTag groups = settings.getListOrEmpty(GROUPS);
        if (!hasLegacyEntries(groups)) return moduleTag;

        CompoundTag migrated = moduleTag.copy();
        CompoundTag migratedSettings = migrated.getCompoundOrEmpty(SETTINGS);
        ListTag sourceGroups = migratedSettings.getListOrEmpty(GROUPS);

        Map<String, Set<String>> explicit = explicitSettingNames(sourceGroups);
        CompoundTag generalSource = findGroup(sourceGroups, GENERAL);
        Set<String> generalNames = generalSource == null ? Set.of() : settingNames(generalSource);

        LinkedHashMap<String, CompoundTag> out = new LinkedHashMap<>(Math.max(4, sourceGroups.size() + 4));
        if (generalSource != null) {
            moveGeneral(generalSource, out, explicit);
        }
        for (Tag tag : sourceGroups) {
            if (!(tag instanceof CompoundTag group)) continue;
            String name = group.getStringOr(NAME, "");
            if (GENERAL.equals(name)) continue;
            if (RANGES.equals(name)) {
                moveRanges(group, out, explicit, generalNames);
            } else {
                mergeGroup(out, group);
            }
        }

        ListTag result = new ListTag();
        for (CompoundTag group : out.values()) result.add(group);
        migratedSettings.put(GROUPS, result);
        return migrated;
    }

    // ------------------------------------------------------------------ movement

    private static void moveGeneral(CompoundTag source, Map<String, CompoundTag> out,
                                    Map<String, Set<String>> explicit) {
        CompoundTag general = emptyGroup(GENERAL, source);
        out.put(GENERAL, general);
        for (Tag tag : source.getListOrEmpty("settings")) {
            if (!(tag instanceof CompoundTag setting)) continue;
            String name = setting.getStringOr(NAME, "");
            String target = targetGroup(name);
            if (GENERAL.equals(target)) {
                append(general, setting);
            } else if (!explicit.getOrDefault(target, Set.of()).contains(name)) {
                append(ensureGroup(out, target, source), setting);
            }
        }
    }

    private static void moveRanges(CompoundTag source, Map<String, CompoundTag> out,
                                   Map<String, Set<String>> explicit, Set<String> generalNames) {
        CompoundTag ranges = null;
        for (Tag tag : source.getListOrEmpty("settings")) {
            if (!(tag instanceof CompoundTag setting)) continue;
            String name = setting.getStringOr(NAME, "");
            String target = RANGE_MOVES.get(name);
            if (target == null) {
                if (ranges == null) ranges = emptyGroup(RANGES, source);
                append(ranges, setting);
                continue;
            }
            if (GENERAL.equals(target)) {
                // A target radius already in General (including one already migrated) wins.
                if (generalNames.contains(name)) continue;
                append(ensureGroup(out, GENERAL, source), setting);
            } else if (!explicit.getOrDefault(target, Set.of()).contains(name)) {
                append(ensureGroup(out, target, source), setting);
            }
        }
        if (ranges != null) out.put(RANGES, ranges);
    }

    private static String targetGroup(String settingName) {
        String target = GENERAL_MOVES.get(settingName);
        if (target != null) return target;
        if (settingName.startsWith(LAYOUT_PREFIX)) return PISTON;
        return GENERAL;
    }

    // ------------------------------------------------------------------ NBT helpers

    private static boolean hasLegacyEntries(ListTag groups) {
        for (Tag tag : groups) {
            if (!(tag instanceof CompoundTag group)) continue;
            String name = group.getStringOr(NAME, "");
            if (GENERAL.equals(name)) {
                for (String setting : settingNames(group)) {
                    if (GENERAL_MOVES.containsKey(setting) || setting.startsWith(LAYOUT_PREFIX)) return true;
                }
            } else if (RANGES.equals(name)) {
                for (String setting : settingNames(group)) {
                    if (RANGE_MOVES.containsKey(setting)) return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Set<String>> explicitSettingNames(ListTag groups) {
        Map<String, Set<String>> explicit = new HashMap<>();
        for (Tag tag : groups) {
            if (!(tag instanceof CompoundTag group)) continue;
            String name = group.getStringOr(NAME, "");
            if (GENERAL.equals(name) || RANGES.equals(name)) continue;
            explicit.computeIfAbsent(name, key -> new HashSet<>()).addAll(settingNames(group));
        }
        return explicit;
    }

    private static Set<String> settingNames(CompoundTag group) {
        Set<String> names = new HashSet<>();
        for (Tag tag : group.getListOrEmpty("settings")) {
            if (tag instanceof CompoundTag setting) names.add(setting.getStringOr(NAME, ""));
        }
        return names;
    }

    private static CompoundTag findGroup(ListTag groups, String name) {
        for (Tag tag : groups) {
            if (tag instanceof CompoundTag group && name.equals(group.getStringOr(NAME, ""))) return group;
        }
        return null;
    }

    private static CompoundTag ensureGroup(Map<String, CompoundTag> out, String name, CompoundTag metadata) {
        CompoundTag group = out.get(name);
        if (group == null) {
            group = emptyGroup(name, metadata);
            out.put(name, group);
        }
        return group;
    }

    private static CompoundTag emptyGroup(String name, CompoundTag metadata) {
        CompoundTag group = metadata.copy();
        group.putString(NAME, name);
        group.put("settings", new ListTag());
        return group;
    }

    private static void append(CompoundTag group, CompoundTag setting) {
        ListTag list = (ListTag) group.get("settings");
        if (list == null) {
            list = new ListTag();
            group.put("settings", list);
        }
        list.add(setting.copy());
    }

    private static void mergeGroup(Map<String, CompoundTag> out, CompoundTag source) {
        String name = source.getStringOr(NAME, "");
        CompoundTag existing = out.get(name);
        if (existing == null) {
            out.put(name, source.copy());
            return;
        }
        Set<String> present = settingNames(existing);
        for (Tag tag : source.getListOrEmpty("settings")) {
            if (tag instanceof CompoundTag setting
                && present.add(setting.getStringOr(NAME, ""))) {
                append(existing, setting);
            }
        }
    }
}
