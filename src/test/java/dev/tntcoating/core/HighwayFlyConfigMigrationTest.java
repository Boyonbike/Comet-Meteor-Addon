package dev.tntcoating.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HighwayFlyConfigMigrationTest {
    @Test void retainsDestinationHomeKeybindAndHelperSwitchesWithoutChangingScaffold() {
        CompoundTag destination = new CompoundTag();
        destination.putString("name", "destination");
        destination.putIntArray("value", new int[] {1200, 120, 3400});
        CompoundTag flight = module("smart-auto-fly", true, "General", destination);
        CompoundTag keybind = new CompoundTag();
        keybind.putInt("value", 72);
        flight.put("keybind", keybind);
        CompoundTag home = module("auto-set-home", false, "General", setting("home", "travel"));
        CompoundTag lobby = module("auto-lobby", true, "General", setting("debug", "yes"));
        CompoundTag portals = module("anti-portal", true, "General", setting("end-portals", "yes"));
        CompoundTag scaffold = module("comet-scaffold", true, "General", setting("preset", "Wide"));
        CompoundTag beforeScaffold = scaffold.copy();
        CompoundTag root = root(flight, home, lobby, portals, scaffold);

        HighwayFlyConfigMigration.migrate(root);

        CompoundTag merged = find(root, "highway-fly");
        assertTrue(merged.getBooleanOr("active", false));
        assertEquals(keybind, merged.getCompoundOrEmpty("keybind"));
        assertArrayEquals(new int[] {1200, 120, 3400}, value(merged, "General", "destination").getIntArray("value").orElseThrow());
        assertEquals("travel", value(merged, "Auto Set Home", "home").getStringOr("value", ""));
        assertFalse(value(merged, "Helpers", "auto-set-home").getBooleanOr("value", true));
        assertTrue(value(merged, "Helpers", "lobby-recovery").getBooleanOr("value", false));
        assertTrue(value(merged, "Helpers", "anti-portal").getBooleanOr("value", false));
        assertEquals("yes", value(merged, "Lobby Recovery", "lobby-debug").getStringOr("value", ""));
        assertEquals("yes", value(merged, "Portal Protection", "end-portals").getStringOr("value", ""));
        assertEquals(beforeScaffold, find(root, "comet-scaffold"));
        CompoundTag once = root.copy();
        HighwayFlyConfigMigration.migrate(root);
        assertEquals(once, root);
    }

    @Test void existingHighwaySettingsTakePrecedenceOverLegacyProfiles() {
        CompoundTag highway = module("highway-fly", false, "General", setting("destination", "existing"));
        CompoundTag root = root(module("smart-auto-fly", true, "General", setting("destination", "old")), highway);
        CompoundTag before = root.copy();
        HighwayFlyConfigMigration.migrate(root);
        assertEquals(before, root);
    }

    @Test void enabledHomeAloneDoesNotStartFlyingAndMissingHelpersStayDisabled() {
        CompoundTag root = root(module("auto-set-home", true, "General", setting("home", "travel")));
        HighwayFlyConfigMigration.migrate(root);
        CompoundTag merged = find(root, "highway-fly");
        assertFalse(merged.getBooleanOr("active", true));
        assertTrue(value(merged, "Helpers", "auto-set-home").getBooleanOr("value", false));
        assertFalse(value(merged, "Helpers", "anti-portal").getBooleanOr("value", true));
        assertFalse(value(merged, "Helpers", "lobby-recovery").getBooleanOr("value", true));
    }

    @Test void unrelatedProfilesAreUnchanged() {
        CompoundTag root = root(module("scaffold", true, "General", setting("blocks", "stone")));
        CompoundTag before = root.copy();
        HighwayFlyConfigMigration.migrate(root);
        assertEquals(before, root);
    }

    @Test void interruptedLegacyLobbyTripResumesOnlyWithAnEnabledRecoveryAndValidMarker() {
        CompoundTag root = root(module("smart-auto-fly", false, "General", setting("destination", "old")),
            module("auto-lobby", true, "General", setting("enter-portal", "yes")));
        HighwayFlyConfigMigration.migrate(root, true);
        assertTrue(find(root, "highway-fly").getBooleanOr("active", false));

        CompoundTag withoutMarker = root(module("smart-auto-fly", false, "General", setting("destination", "old")),
            module("auto-lobby", true, "General", setting("enter-portal", "yes")));
        HighwayFlyConfigMigration.migrate(withoutMarker, false);
        assertFalse(find(withoutMarker, "highway-fly").getBooleanOr("active", true));

        CompoundTag disabledRecovery = root(module("smart-auto-fly", false, "General", setting("destination", "old")),
            module("auto-lobby", false, "General", setting("enter-portal", "yes")));
        HighwayFlyConfigMigration.migrate(disabledRecovery, true);
        assertFalse(find(disabledRecovery, "highway-fly").getBooleanOr("active", true));
    }

    @Test void savingWhileRetryingKeepsTheUsersOriginalReconnectPreference() {
        CompoundTag delay = new CompoundTag();
        delay.putString("name", "delay"); delay.putDouble("value", 0.5);
        CompoundTag reconnect = module("auto-reconnect", true, "General", delay);
        CompoundTag scaffold = module("comet-scaffold", true, "General", setting("preset", "Wide"));
        CompoundTag originalScaffold = scaffold.copy();
        CompoundTag root = root(reconnect, scaffold);
        HighwayFlyConfigMigration.restoreReconnectPreference(root, false, 7.25);
        CompoundTag saved = find(root, "auto-reconnect");
        assertFalse(saved.getBooleanOr("active", true));
        assertEquals(7.25, value(saved, "General", "delay").getDoubleOr("value", -1));
        assertEquals(originalScaffold, find(root, "comet-scaffold"));
    }

    @Test void savingOriginalDefaultDelayAddsAnOmittedSettingWithoutChangingItsEnabledState() {
        CompoundTag root = root(module("auto-reconnect", true, "General", setting("hide-buttons", "keep")));
        HighwayFlyConfigMigration.restoreReconnectPreference(root, true, 3.5);
        CompoundTag saved = find(root, "auto-reconnect");
        assertTrue(saved.getBooleanOr("active", false));
        assertEquals(3.5, value(saved, "General", "delay").getDoubleOr("value", -1));
        assertEquals("keep", value(saved, "General", "hide-buttons").getStringOr("value", ""));
    }

    @Test void regroupingKeepsSettingMetadataUnknownValuesAndUnrelatedModules() {
        CompoundTag destination = setting("destination", "saved-route");
        destination.putString("future-metadata", "keep");
        CompoundTag highway = module("highway-fly", true, "General", destination);
        highway.putString("future-module-metadata", "keep");
        CompoundTag oldGroup = group(highway, "General");
        oldGroup.putBoolean("sectionExpanded", true);
        oldGroup.putString("future-group-metadata", "keep");
        CompoundTag unknownSetting = setting("future-setting", "keep");
        oldGroup.getListOrEmpty("settings").add(unknownSetting);
        CompoundTag unknownGroup = new CompoundTag();
        unknownGroup.putString("name", "Future Group");
        unknownGroup.putString("future-group-data", "keep");
        CompoundTag unknownGroupBefore = unknownGroup.copy();
        highway.getCompoundOrEmpty("settings").getListOrEmpty("groups").add(unknownGroup);
        CompoundTag unrelated = module("other-module", true, "General", setting("destination", "unrelated"));
        CompoundTag unrelatedBefore = unrelated.copy();
        CompoundTag root = root(highway, unrelated);

        HighwayFlyConfigMigration.regroupHighwaySettings(root, Map.of("destination", "Route"));

        assertEquals(destination, value(highway, "Route", "destination"));
        assertEquals(1, oldGroup.getListOrEmpty("settings").size());
        assertEquals(unknownSetting, oldGroup.getListOrEmpty("settings").get(0));
        assertEquals("keep", oldGroup.getStringOr("future-group-metadata", ""));
        assertTrue(oldGroup.getBooleanOr("sectionExpanded", false));
        assertTrue(group(highway, "Route").getBooleanOr("sectionExpanded", false));
        assertEquals(unknownGroupBefore, group(highway, "Future Group"));
        assertEquals("keep", highway.getStringOr("future-module-metadata", ""));
        assertEquals(unrelatedBefore, find(root, "other-module"));
    }

    @Test void regroupingPrefersAnExplicitDestinationValueOverOldGroupDuplicates() {
        CompoundTag highway = module("highway-fly", false, "Old", setting("speed-bps", "old"));
        ListTag groups = highway.getCompoundOrEmpty("settings").getListOrEmpty("groups");
        CompoundTag currentGroup = group(module("unused", false, "Flight", setting("speed-bps", "current")), "Flight");
        currentGroup.putBoolean("sectionExpanded", false);
        currentGroup.putString("future-metadata", "keep");
        groups.add(currentGroup);
        groups.add(group(module("unused", false, "Another Old", setting("speed-bps", "also old")), "Another Old"));
        CompoundTag root = root(highway);

        HighwayFlyConfigMigration.regroupHighwaySettings(root, Map.of("speed-bps", "Flight"));

        assertEquals("current", value(highway, "Flight", "speed-bps").getStringOr("value", ""));
        assertEquals(1, currentGroup.getListOrEmpty("settings").size());
        assertTrue(group(highway, "Old").getListOrEmpty("settings").isEmpty());
        assertTrue(group(highway, "Another Old").getListOrEmpty("settings").isEmpty());
        assertFalse(currentGroup.getBooleanOr("sectionExpanded", true));
        assertEquals("keep", currentGroup.getStringOr("future-metadata", ""));
        CompoundTag once = root.copy();
        HighwayFlyConfigMigration.regroupHighwaySettings(root, Map.of("speed-bps", "Flight"));
        assertEquals(once, root);
    }

    @Test void regroupingCanMoveConvertedLegacySettingsAndDoesNotCreateEmptyGroups() {
        CompoundTag root = root(module("smart-auto-fly", false, "General", setting("destination", "legacy")));
        HighwayFlyConfigMigration.migrate(root);

        HighwayFlyConfigMigration.regroupHighwaySettings(root,
            Map.of("destination", "Route", "webhook-url", "Webhook"));

        CompoundTag highway = find(root, "highway-fly");
        assertEquals("legacy", value(highway, "Route", "destination").getStringOr("value", ""));
        assertTrue(highway.getCompoundOrEmpty("settings").getListOrEmpty("groups").stream()
            .noneMatch(tag -> tag instanceof CompoundTag group && "Webhook".equals(group.getStringOr("name", ""))));
        CompoundTag once = root.copy();
        HighwayFlyConfigMigration.regroupHighwaySettings(root, Map.of("destination", "Route"));
        assertEquals(once, root);
    }

    @Test void regroupingLeavesProfilesWithoutHighwayFlyUnchanged() {
        CompoundTag root = root(module("other-module", false, "General", setting("destination", "keep")));
        CompoundTag before = root.copy();
        HighwayFlyConfigMigration.regroupHighwaySettings(root, Map.of("destination", "Route"));
        assertEquals(before, root);
    }

    private static CompoundTag setting(String name, String text) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putString("value", text);
        return tag;
    }

    private static CompoundTag module(String name, boolean active, String group, CompoundTag value) {
        CompoundTag module = new CompoundTag();
        module.putString("name", name);
        module.putBoolean("active", active);
        ListTag values = new ListTag(); values.add(value);
        CompoundTag groupTag = new CompoundTag();
        groupTag.putString("name", group); groupTag.put("settings", values);
        ListTag groups = new ListTag(); groups.add(groupTag);
        CompoundTag settings = new CompoundTag(); settings.put("groups", groups);
        module.put("settings", settings);
        return module;
    }

    private static CompoundTag root(CompoundTag... modules) {
        ListTag list = new ListTag();
        for (CompoundTag module : modules) list.add(module);
        CompoundTag root = new CompoundTag(); root.put("modules", list);
        return root;
    }

    private static CompoundTag find(CompoundTag root, String name) {
        for (var tag : root.getListOrEmpty("modules")) {
            if (tag instanceof CompoundTag module && name.equals(module.getStringOr("name", ""))) return module;
        }
        fail("Missing module " + name);
        return null;
    }

    private static CompoundTag value(CompoundTag module, String groupName, String name) {
        for (var tag : module.getCompoundOrEmpty("settings").getListOrEmpty("groups")) {
            if (!(tag instanceof CompoundTag group) || !groupName.equals(group.getStringOr("name", ""))) continue;
            for (var value : group.getListOrEmpty("settings")) {
                if (value instanceof CompoundTag setting && name.equals(setting.getStringOr("name", ""))) return setting;
            }
        }
        fail("Missing setting " + groupName + "/" + name);
        return null;
    }

    private static CompoundTag group(CompoundTag module, String name) {
        for (var tag : module.getCompoundOrEmpty("settings").getListOrEmpty("groups")) {
            if (tag instanceof CompoundTag group && name.equals(group.getStringOr("name", ""))) return group;
        }
        fail("Missing group " + name);
        return null;
    }
}
