package dev.tntcoating.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HighwayFlyConfigMigrationTest {
    private static CompoundTag boolSetting(String name, boolean value) {
        var tag = new CompoundTag(); tag.putString("name", name); tag.putBoolean("value", value); return tag;
    }

    @Test void independentMigrationRetainsLegacyHelperPreferencesAndFlightDestination() {
        var root = root(module("smart-auto-fly", false, "General", setting("destination", "legacy")),
            module("auto-set-home", true, "General", setting("home", "travel")),
            module("auto-lobby", true, "General", setting("debug", "yes")),
            module("anti-portal", true, "General", boolSetting("end-portals", true)));
        HighwayFlyConfigMigration.migrateIndependent(root, false);
        assertFalse(find(root, "highway-fly").getBooleanOr("active", true));
        assertEquals("legacy", value(find(root, "highway-fly"), "General", "destination").getStringOr("value", ""));
        for (String name : new String[] {"auto-set-home", "auto-lobby", "anti-portal"})
            assertTrue(find(root, name).getBooleanOr("active", false));
        assertEquals("yes", value(find(root, "auto-lobby"), "General", "lobby-debug").getStringOr("value", ""));
        var once = root.copy();
        HighwayFlyConfigMigration.migrateIndependent(root, false);
        assertEquals(once, root);
    }

    @Test void independentMigrationMovesProtectionAndReconnectWithoutMovingFlightPortalMode() {
        var root = root(module("highway-fly", true, "Portals", setting("portal-mode", "Avoid"),
            boolSetting("nether-portals", false), boolSetting("anti-portal", true)));
        var highway = find(root, "highway-fly");
        var settings = highway.getCompoundOrEmpty("settings");
        var groups = settings.getListOrEmpty("groups");
        groups.add(group(module("unused", false, "Lobby Recovery", boolSetting("auto-reconnect", false), setting("reconnect-delay", "8.5")), "Lobby Recovery"));
        HighwayFlyConfigMigration.migrateIndependent(root, false);
        assertEquals("Avoid", value(highway, "Portals", "portal-mode").getStringOr("value", ""));
        assertTrue(value(highway, "Portals", "anti-portal").getBooleanOr("value", false));
        assertFalse(value(find(root, "anti-portal"), "General", "nether-portals").getBooleanOr("value", true));
        assertFalse(value(find(root, "auto-lobby"), "General", "auto-reconnect").getBooleanOr("value", true));
        assertEquals("8.5", value(find(root, "auto-lobby"), "General", "reconnect-delay").getStringOr("value", ""));
    }

    @Test void independentDashboardMigrationDoesNotOverwriteNewPreferences() {
        var root = root(module("highway-fly", false, "Dashboard", boolSetting("dashboard-enabled", false),
            setting("dashboard-port", "8734")), module("highway-dashboard", true, "Server", setting("port", "9876")));
        HighwayFlyConfigMigration.migrateIndependent(root, false);
        assertFalse(value(find(root, "highway-fly"), "Dashboard", "dashboard-enabled").getBooleanOr("value", true));
        assertEquals("8734", value(find(root, "highway-fly"), "Dashboard", "dashboard-port").getStringOr("value", ""));
        var once = root.copy();
        HighwayFlyConfigMigration.migrateIndependent(root, false);
        assertEquals(once, root);
    }

    @Test void independentMigrationSplitsCombinedSettingsAndPreservesIntegrationSwitches() {
        var root = root(module("highway-fly", true, "Auto Set Home",
            setting("home", "travel"), boolSetting("auto-set-home", true)));
        HighwayFlyConfigMigration.migrateIndependent(root, false);
        assertEquals("travel", value(find(root, "auto-set-home"), "General", "home").getStringOr("value", ""));
        assertFalse(find(root, "auto-set-home").getBooleanOr("active", true));
        assertTrue(value(find(root, "highway-fly"), "Auto Set Home", "auto-set-home").getBooleanOr("value", false));
        var once = root.copy();
        HighwayFlyConfigMigration.migrateIndependent(root, false);
        assertEquals(once, root);
    }

    @Test void independentMigrationPreservesStandaloneSettingsAndActivation() {
        var root = root(module("highway-fly", false, "Auto Set Home", setting("home", "embedded")),
            module("auto-set-home", true, "General", setting("home", "explicit")));
        HighwayFlyConfigMigration.migrateIndependent(root, false);
        assertEquals("explicit", value(find(root, "auto-set-home"), "General", "home").getStringOr("value", ""));
        assertTrue(find(root, "auto-set-home").getBooleanOr("active", false));
    }

    @Test void independentMigrationMovesDashboardActivationAndSettings() {
        var root = root(module("highway-fly", false, "Route", setting("destination", "existing")),
            module("highway-dashboard", true, "Server", setting("port", "9876")));
        HighwayFlyConfigMigration.migrateIndependent(root, false);
        var highway = find(root, "highway-fly");
        assertTrue(value(highway, "Dashboard", "dashboard-enabled").getBooleanOr("value", false));
        assertEquals("9876", value(highway, "Dashboard", "dashboard-port").getStringOr("value", ""));
        assertFalse(highway.getBooleanOr("active", true));
    }

    @Test void migratesPeriodicWebhookPreferenceAndExplicitNewValueWins() {
        CompoundTag root = root(module("highway-fly", false, "Webhook Events", boolSetting("periodic-status", false)));
        HighwayFlyConfigMigration.regroupHighwaySettings(root, Map.of("home-replaced-events", "Webhook Events"));
        assertFalse(value(find(root, "highway-fly"), "Webhook Events", "home-replaced-events").getBooleanOr("value", true));
        CompoundTag both = root(module("highway-fly", false, "Webhook Events",
            boolSetting("periodic-status", false), boolSetting("home-replaced-events", true)));
        HighwayFlyConfigMigration.regroupHighwaySettings(both, Map.of("home-replaced-events", "Webhook Events"));
        assertTrue(value(find(both, "highway-fly"), "Webhook Events", "home-replaced-events").getBooleanOr("value", false));
        var once = both.copy();
        HighwayFlyConfigMigration.regroupHighwaySettings(both, Map.of("home-replaced-events", "Webhook Events"));
        assertEquals(once, both);
    }

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

    @Test void legacyDamageLimitBecomesMinimumHealthBeforeRegrouping() {
        CompoundTag legacy = setting("damage-limit", "unused");
        legacy.putDouble("value", 12.5);
        legacy.putString("future-metadata", "keep");
        CompoundTag highway = module("highway-fly", false, "Old Safety", legacy);
        CompoundTag root = root(highway);

        HighwayFlyConfigMigration.regroupHighwaySettings(root, Map.of("minimum-health", "Safety & Lava"));

        CompoundTag migrated = value(highway, "Safety & Lava", "minimum-health");
        assertEquals(12.5, migrated.getDoubleOr("value", -1));
        assertEquals("keep", migrated.getStringOr("future-metadata", ""));
        assertTrue(group(highway, "Old Safety").getListOrEmpty("settings").isEmpty());
        assertEquals(1, group(highway, "Safety & Lava").getListOrEmpty("settings").size());
        CompoundTag once = root.copy();
        HighwayFlyConfigMigration.regroupHighwaySettings(root, Map.of("minimum-health", "Safety & Lava"));
        assertEquals(once, root);
    }

    @Test void disabledLegacyDamageLimitKeepsMinimumHealthDisabledInItsCurrentGroup() {
        CompoundTag legacy = setting("damage-limit", "unused");
        legacy.putDouble("value", 0);
        CompoundTag highway = module("highway-fly", false, "Safety & Lava", legacy);

        HighwayFlyConfigMigration.regroupHighwaySettings(root(highway), Map.of("minimum-health", "Safety & Lava"));

        assertEquals(0, value(highway, "Safety & Lava", "minimum-health").getDoubleOr("value", -1));
        assertEquals(1, group(highway, "Safety & Lava").getListOrEmpty("settings").size());
    }

    @Test void explicitMinimumHealthInAnotherGroupOverridesALegacyValueAlreadyInTheDestination() {
        CompoundTag legacy = setting("damage-limit", "unused");
        legacy.putDouble("value", 18);
        CompoundTag highway = module("highway-fly", false, "Safety & Lava", legacy);
        CompoundTag current = setting("minimum-health", "unused");
        current.putDouble("value", 6);
        highway.getCompoundOrEmpty("settings").getListOrEmpty("groups")
            .add(group(module("unused", false, "Old Safety", current), "Old Safety"));
        CompoundTag root = root(highway);

        HighwayFlyConfigMigration.regroupHighwaySettings(root, Map.of("minimum-health", "Safety & Lava"));

        assertEquals(6, value(highway, "Safety & Lava", "minimum-health").getDoubleOr("value", -1));
        assertEquals(1, group(highway, "Safety & Lava").getListOrEmpty("settings").size());
        assertTrue(group(highway, "Old Safety").getListOrEmpty("settings").isEmpty());
        CompoundTag once = root.copy();
        HighwayFlyConfigMigration.regroupHighwaySettings(root, Map.of("minimum-health", "Safety & Lava"));
        assertEquals(once, root);
    }

    @Test void explicitDisabledMinimumHealthInTheDestinationIsNotReplacedByALegacyValue() {
        CompoundTag legacy = setting("damage-limit", "unused");
        legacy.putDouble("value", 8);
        CompoundTag highway = module("highway-fly", false, "Old Safety", legacy);
        CompoundTag current = setting("minimum-health", "unused");
        current.putDouble("value", 0);
        highway.getCompoundOrEmpty("settings").getListOrEmpty("groups")
            .add(group(module("unused", false, "Safety & Lava", current), "Safety & Lava"));

        HighwayFlyConfigMigration.regroupHighwaySettings(root(highway), Map.of("minimum-health", "Safety & Lava"));

        assertEquals(0, value(highway, "Safety & Lava", "minimum-health").getDoubleOr("value", -1));
        assertTrue(group(highway, "Old Safety").getListOrEmpty("settings").isEmpty());
        assertEquals(1, group(highway, "Safety & Lava").getListOrEmpty("settings").size());
    }

    @Test void legacyValuesThatWouldMakeTheHealthGuardUnsafeUseItsDefault() {
        for (double saved : new double[] {20, 100, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            CompoundTag legacy = setting("damage-limit", "unused");
            legacy.putDouble("value", saved);
            CompoundTag highway = module("highway-fly", false, "Safety & Lava", legacy);

            HighwayFlyConfigMigration.regroupHighwaySettings(root(highway), Map.of("minimum-health", "Safety & Lava"));

            assertEquals(8, value(highway, "Safety & Lava", "minimum-health").getDoubleOr("value", -1),
                "Legacy damage limit " + saved + " must not become an unsafe health threshold");
        }
    }

    @Test void explicitlyChosenFullHealthThresholdIsNotNormalizedAsALegacyDamageLimit() {
        CompoundTag legacy = setting("damage-limit", "unused");
        legacy.putDouble("value", 100);
        CompoundTag highway = module("highway-fly", false, "Old Safety", legacy);
        CompoundTag current = setting("minimum-health", "unused");
        current.putDouble("value", 20);
        highway.getCompoundOrEmpty("settings").getListOrEmpty("groups")
            .add(group(module("unused", false, "Safety & Lava", current), "Safety & Lava"));

        HighwayFlyConfigMigration.regroupHighwaySettings(root(highway), Map.of("minimum-health", "Safety & Lava"));

        assertEquals(20, value(highway, "Safety & Lava", "minimum-health").getDoubleOr("value", -1));
        assertTrue(group(highway, "Old Safety").getListOrEmpty("settings").isEmpty());
    }

    private static CompoundTag number(String name, double value) {
        var tag = new CompoundTag(); tag.putString("name", name); tag.putDouble("value", value); return tag;
    }

    @Test void oldBoatFlyRecordBecomesAutoBoatFlyAndManualKeepsTheMovementSettings() {
        var old = module("boat-fly", true, "Movement & Phasing", number("horizontal-speed", 2), number("horizontal-limit", 2.5),
            number("phase-speed", 1.5), number("phase-horizontal-speed", .2));
        var groups = old.getCompoundOrEmpty("settings").getListOrEmpty("groups");
        var general = new CompoundTag(); general.putString("name", "General");
        var values = new ListTag(); values.add(number("heading", 90)); general.put("settings", values);
        groups.add(general);
        var root = root(old);
        HighwayFlyConfigMigration.splitBoatFly(root);
        var manual = find(root, "boat-fly");
        var automatic = find(root, "auto-boat-fly");
        assertFalse(manual.getBooleanOr("active", true), "The new manual module must not start a trip on load");
        assertTrue(automatic.getBooleanOr("active", false));
        assertEquals(90, value(automatic, "General", "heading").getDoubleOr("value", 0));
        for (var module : new CompoundTag[] {manual, automatic}) {
            assertEquals(2, value(module, "Movement & Phasing", "limited-speed").getDoubleOr("value", 0), "Travel used the smaller cap");
            assertEquals(1.5, value(module, "Movement & Phasing", "adaptive-phase-speed").getDoubleOr("value", 0));
            assertEquals(.2, value(module, "Movement & Phasing", "in-block-speed").getDoubleOr("value", 0), 1e-9);
        }
        var once = root.copy();
        HighwayFlyConfigMigration.splitBoatFly(root);
        assertEquals(once, root, "A config that already has Auto Boat Fly is never split again");
    }

    @Test void savedHorizontalSpeedAloneIsCappedByTheOldDefaultLimit() {
        var root = root(module("boat-fly", false, "Movement & Phasing", number("horizontal-speed", 2.8)));
        HighwayFlyConfigMigration.splitBoatFly(root);
        assertEquals(.99, value(find(root, "auto-boat-fly"), "Speed", "limited-speed").getDoubleOr("value", 0), 1e-9);
    }

    @Test void boatSettingsFollowTheirNewSections() {
        var root = root(module("auto-boat-fly", false, "General", number("heading", -90), number("limited-speed", 1.2)));
        HighwayFlyConfigMigration.regroupSettings(root, "auto-boat-fly", Map.of("heading", "Trip", "limited-speed", "Speed"));
        var automatic = find(root, "auto-boat-fly");
        assertEquals(-90, value(automatic, "Trip", "heading").getDoubleOr("value", 0));
        assertEquals(1.2, value(automatic, "Speed", "limited-speed").getDoubleOr("value", 0), 1e-9);
    }

    @Test void bothOldDashboardsMoveToFlightDashboardWithHighwaySettingsWinning() {
        var highway = module("highway-fly", false, "Dashboard", boolSetting("dashboard-enabled", false), number("dashboard-port", 9000));
        var boat = module("boat-fly", false, "Dashboard", boolSetting("dashboard-enabled", true), number("dashboard-port", 8735),
            setting("dashboard-access-token", "secret"));
        var root = root(highway, boat);
        HighwayFlyConfigMigration.migrateFlightDashboard(root);
        var dashboard = find(root, "flight-dashboard");
        assertTrue(dashboard.getBooleanOr("active", false), "Either old dashboard being on keeps the page served");
        assertEquals(9000, value(dashboard, "General", "port").getDoubleOr("value", 0));
        assertEquals("secret", value(dashboard, "General", "access-token").getStringOr("value", ""));
        assertTrue(group(find(root, "highway-fly"), "Dashboard").getListOrEmpty("settings").isEmpty());
        assertTrue(group(find(root, "boat-fly"), "Dashboard").getListOrEmpty("settings").isEmpty());
        var once = root.copy();
        HighwayFlyConfigMigration.migrateFlightDashboard(root);
        assertEquals(once, root);
    }

    @Test void configsWithoutAnOldDashboardGetNoFlightDashboardRecord() {
        var root = root(module("highway-fly", true, "Route", setting("destination", "x")));
        HighwayFlyConfigMigration.migrateFlightDashboard(root);
        assertEquals(1, root.getListOrEmpty("modules").size());
    }

    private static CompoundTag setting(String name, String text) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        tag.putString("value", text);
        return tag;
    }

    private static CompoundTag module(String name, boolean active, String group, CompoundTag... value) {
        CompoundTag module = new CompoundTag();
        module.putString("name", name);
        module.putBoolean("active", active);
        ListTag values = new ListTag(); for (CompoundTag entry : value) values.add(entry);
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
