/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * Stage 5 acceptance: moving CrystalCombat settings into the Phase 8 groups must not lose a saved value.
 * The migration is pure, defensive and idempotent; the module calls it from {@code fromTag} before
 * {@code super.fromTag}.
 */
final class CrystalCombatSettingsMigrationTest {

    @Test
    void legacyGeneralAndRangesValuesMoveIntoThePlanGroups() {
        CompoundTag tag = module(
            group("General", true,
                text("mode", "HYBRID"),
                bool("anti-suicide", false),
                bool("piston-trap", true),
                bool("auto-cleanup", true),
                bool("piston-layout-east-feet-torch", false),
                bool("own-only", true),
                bool("motion-prediction", true),
                bool("hurt-sync", true),
                bool("support", true),
                bool("surround-gap", true),
                bool("dangerous-crystal-break", true),
                bool("anti-piston", true),
                bool("footprint-concurrency", true)),
            group("Ranges", false,
                dbl("target-range", 9.0),
                dbl("place-range", 3.5),
                dbl("attack-range", 2.5),
                dbl("walls-range", 1.5)),
            group("Safety", false, dbl("min-target-damage", 5.0)),
            group("Pauses", false, dbl("health-floor", 8.0)),
            group("Diagnostics", false, bool("debug-log", true)));

        CompoundTag migrated = CrystalCombatSettingsMigration.migrate(tag);

        assertEquals(List.of("mode", "target-range"), names(group(migrated, "General")),
            "General keeps the plan's General keys and receives the old Ranges target radius");
        assertEquals(9.0, number(group(migrated, "General"), "target-range"), 0.0);
        assertFalse(boolValue(group(migrated, "Safety"), "anti-suicide"));
        assertEquals(List.of("anti-suicide", "min-target-damage"), names(group(migrated, "Safety")),
            "anti-suicide joins the old Safety group without losing its value");
        assertEquals(List.of("piston-trap", "auto-cleanup", "piston-layout-east-feet-torch"),
            names(group(migrated, "Piston")));
        assertFalse(boolValue(group(migrated, "Piston"), "piston-layout-east-feet-torch"));
        assertEquals(List.of("own-only", "support", "surround-gap", "place-range", "attack-range",
            "walls-range"), names(group(migrated, "Direct")));
        assertEquals(3.5, number(group(migrated, "Direct"), "place-range"), 0.0);
        assertEquals(List.of("motion-prediction", "hurt-sync"), names(group(migrated, "Timing")));
        assertEquals(List.of("dangerous-crystal-break", "anti-piston"), names(group(migrated, "Defence")));
        assertEquals(List.of("footprint-concurrency"), names(group(migrated, "Experimental")));
        assertEquals(List.of("health-floor"), names(group(migrated, "Pauses")));
        assertEquals(8.0, number(group(migrated, "Pauses"), "health-floor"), 0.0);
        assertEquals(List.of("debug-log"), names(group(migrated, "Diagnostics")));
        assertNull(group(migrated, "Ranges"), "the old Ranges group is gone after its keys moved");
    }

    @Test
    void oldGeneralKeysThatStayInGeneralKeepTheirPosition() {
        CompoundTag tag = module(group("General", true,
            text("mode", "PISTON_ONLY"),
            text("profile", "CUSTOM"),
            bool("anti-suicide", true),
            dbl("target-range", 7.0)));

        CompoundTag migrated = CrystalCombatSettingsMigration.migrate(tag);

        assertEquals(List.of("mode", "profile", "target-range"), names(group(migrated, "General")));
        assertEquals("PISTON_ONLY", textValue(group(migrated, "General"), "mode"));
        assertEquals("CUSTOM", textValue(group(migrated, "General"), "profile"));
        assertEquals(7.0, number(group(migrated, "General"), "target-range"), 0.0);
    }

    @Test
    void anExplicitNewGroupValueWinsOverTheLegacyValue() {
        CompoundTag tag = module(
            group("General", true,
                bool("auto-cleanup", true),
                dbl("target-range", 7.0)),
            group("Piston", false, bool("auto-cleanup", false)),
            group("Ranges", false, dbl("target-range", 8.0)));

        CompoundTag migrated = CrystalCombatSettingsMigration.migrate(tag);

        assertEquals(1, occurrences(group(migrated, "Piston"), "auto-cleanup"));
        assertFalse(boolValue(group(migrated, "Piston"), "auto-cleanup"),
            "an already-persisted new-layout value wins over the legacy General value");
        assertEquals(7.0, number(group(migrated, "General"), "target-range"), 0.0,
            "a target-radius already in General wins over the old Ranges value");
        assertNull(group(migrated, "Ranges"));
    }

    @Test
    void unknownGroupsAndSettingsArePreserved() {
        CompoundTag tag = module(
            group("General", true, bool("anti-suicide", false), text("future-key", "keep-me")),
            group("Future", false, bool("some-key", true)),
            group("Ranges", false, dbl("target-range", 9.0), text("future-range", "keep-me-too")));

        CompoundTag migrated = CrystalCombatSettingsMigration.migrate(tag);

        assertEquals(List.of("future-key", "target-range"), names(group(migrated, "General")));
        assertEquals(9.0, number(group(migrated, "General"), "target-range"), 0.0);
        assertEquals("keep-me", textValue(group(migrated, "General"), "future-key"));
        assertEquals(List.of("some-key"), names(group(migrated, "Future")),
            "unknown groups are not dropped");
        assertEquals(List.of("future-range"), names(group(migrated, "Ranges")),
            "unknown Ranges settings keep their group rather than being invented elsewhere");
        assertEquals("keep-me-too", textValue(group(migrated, "Ranges"), "future-range"));
    }

    @Test
    void theInputTagIsNeverMutated() {
        CompoundTag original = module(group("General", true, bool("anti-suicide", false)));
        CompoundTag copy = original.copy();

        CrystalCombatSettingsMigration.migrate(original);

        assertEquals(copy, original, "the caller's tag stays untouched");
        assertFalse(boolValue(group(original, "General"), "anti-suicide"),
            "the legacy value is still in the source tag");
    }

    @Test
    void migrationIsIdempotent() {
        CompoundTag migrated = CrystalCombatSettingsMigration.migrate(module(
            group("General", true, bool("anti-suicide", false)),
            group("Ranges", false, dbl("place-range", 3.5))));

        assertSame(migrated, CrystalCombatSettingsMigration.migrate(migrated),
            "a second load of the new layout must not copy or rename anything");
    }

    @Test
    void returnsTheSameInstanceWhenThereIsNothingToMigrate() {
        CompoundTag tag = module(
            group("General", true, text("mode", "DIRECT_ONLY")),
            group("Direct", false, dbl("place-range", 3.5)));

        assertSame(tag, CrystalCombatSettingsMigration.migrate(tag));
    }

    @Test
    void missingSettingsTagIsTolerated() {
        CompoundTag tag = new CompoundTag();
        assertSame(tag, CrystalCombatSettingsMigration.migrate(tag));
    }

    @Test
    void createdGroupsCarryTheLegacySectionExpandedFlag() {
        CompoundTag tag = module(group("General", true,
            bool("motion-prediction", true),
            bool("piston-trap", true)));

        CompoundTag migrated = CrystalCombatSettingsMigration.migrate(tag);

        assertTrue(group(migrated, "Timing").getBooleanOr("sectionExpanded", false));
        assertTrue(group(migrated, "Piston").getBooleanOr("sectionExpanded", false));
    }

    // ------------------------------------------------------------------ NBT helpers (hand-built old layout)

    private static CompoundTag bool(String name, boolean value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putBoolean("value", value);
        return tag;
    }

    private static CompoundTag dbl(String name, double value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putDouble("value", value);
        return tag;
    }

    private static CompoundTag text(String name, String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putString("value", value);
        return tag;
    }

    private static CompoundTag group(String name, boolean expanded, CompoundTag... settings) {
        CompoundTag group = new CompoundTag();
        group.putString("name", name);
        group.putBoolean("sectionExpanded", expanded);
        ListTag list = new ListTag();
        for (CompoundTag setting : settings) list.add(setting);
        group.put("settings", list);
        return group;
    }

    private static CompoundTag module(CompoundTag... groups) {
        CompoundTag settings = new CompoundTag();
        ListTag list = new ListTag();
        for (CompoundTag group : groups) list.add(group);
        settings.put("groups", list);
        CompoundTag module = new CompoundTag();
        module.put("settings", settings);
        return module;
    }

    private static CompoundTag group(CompoundTag module, String name) {
        for (Tag tag : module.getCompoundOrEmpty("settings").getListOrEmpty("groups")) {
            if (tag instanceof CompoundTag group && name.equals(group.getStringOr("name", ""))) return group;
        }
        return null;
    }

    private static List<String> names(CompoundTag group) {
        assertNotNull(group);
        List<String> names = new ArrayList<>();
        for (Tag tag : group.getListOrEmpty("settings")) {
            if (tag instanceof CompoundTag setting) names.add(setting.getStringOr("name", ""));
        }
        return names;
    }

    private static CompoundTag setting(CompoundTag group, String name) {
        for (Tag tag : group.getListOrEmpty("settings")) {
            if (tag instanceof CompoundTag setting && name.equals(setting.getStringOr("name", ""))) {
                return setting;
            }
        }
        return null;
    }

    private static boolean boolValue(CompoundTag group, String name) {
        CompoundTag setting = setting(group, name);
        assertNotNull(setting, name);
        return setting.getBooleanOr("value", false);
    }

    private static double number(CompoundTag group, String name) {
        CompoundTag setting = setting(group, name);
        assertNotNull(setting, name);
        return setting.getDoubleOr("value", Double.NaN);
    }

    private static String textValue(CompoundTag group, String name) {
        CompoundTag setting = setting(group, name);
        assertNotNull(setting, name);
        return setting.getStringOr("value", "");
    }

    private static int occurrences(CompoundTag group, String name) {
        return (int) names(group).stream().filter(name::equals).count();
    }
}
