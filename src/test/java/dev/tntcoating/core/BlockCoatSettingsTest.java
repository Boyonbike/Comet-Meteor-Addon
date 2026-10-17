package dev.tntcoating.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockCoatSettingsTest {
    @Test void legacyEnabledValueMovesIntoExpandedTntSavingGroup() {
        CompoundTag old = module(group("General", setting("conservative-tnt", true)));

        CompoundTag migrated = BlockCoatSettings.migrate(old);

        CompoundTag saving = findGroup(migrated, "TNT Saving");
        assertTrue(saving.getBooleanOr("sectionExpanded", false));
        assertTrue(findSetting(saving, "conservative-tnt").getBooleanOr("value", false));
    }

    @Test void configurationsWithoutLegacySettingStayUnchanged() {
        CompoundTag empty = new CompoundTag();
        CompoundTag configured = module(group("General", setting("rotate", true)));

        assertSame(empty, BlockCoatSettings.migrate(empty));
        assertSame(configured, BlockCoatSettings.migrate(configured));
        assertNull(findGroup(configured, "TNT Saving"));
    }

    @Test void explicitNewFalseWinsOverLegacyTrueRegardlessOfGroupOrder() {
        for (boolean newFirst : new boolean[] {false, true}) {
            CompoundTag general = group("General", setting("conservative-tnt", true));
            CompoundTag saving = group("TNT Saving", setting("conservative-tnt", false));
            CompoundTag configured = newFirst ? module(saving, general) : module(general, saving);

            assertSame(configured, BlockCoatSettings.migrate(configured));
            assertFalse(findSetting(findGroup(configured, "TNT Saving"), "conservative-tnt").getBooleanOr("value", true));
        }
    }

    @Test void migrationPreservesOtherSettingsAndModuleMetadata() {
        CompoundTag general = group("General", setting("conservative-tnt", true), setting("rotate", false));
        general.putBoolean("sectionExpanded", false);
        CompoundTag saving = group("TNT Saving", setting("other-option", true));
        saving.putString("custom-metadata", "keep");
        CompoundTag server = group("Server Pacing", setting("container-supports", false));
        CompoundTag configured = module(general, saving, server);
        configured.putString("name", "block-coat");
        configured.putBoolean("active", true);
        CompoundTag keybind = new CompoundTag();
        keybind.putInt("value", 65);
        configured.put("keybind", keybind);

        CompoundTag migrated = BlockCoatSettings.migrate(configured);

        assertEquals(general, findGroup(migrated, "General"));
        assertEquals(server, findGroup(migrated, "Server Pacing"));
        assertEquals(keybind, migrated.getCompoundOrEmpty("keybind"));
        assertEquals("block-coat", migrated.getStringOr("name", ""));
        assertTrue(migrated.getBooleanOr("active", false));
        CompoundTag migratedSaving = findGroup(migrated, "TNT Saving");
        assertEquals("keep", migratedSaving.getStringOr("custom-metadata", ""));
        assertEquals(findSetting(saving, "other-option"), findSetting(migratedSaving, "other-option"));
        assertEquals(2, migratedSaving.getListOrEmpty("settings").size());
        assertEquals(3, migrated.getCompoundOrEmpty("settings").getListOrEmpty("groups").size());
    }

    @Test void migrationDoesNotMutateInputAndIsIdempotent() {
        CompoundTag old = module(group("General", setting("conservative-tnt", true)));
        CompoundTag snapshot = old.copy();

        CompoundTag migrated = BlockCoatSettings.migrate(old);

        assertNotSame(old, migrated);
        assertEquals(snapshot, old);
        assertSame(migrated, BlockCoatSettings.migrate(migrated));
        findSetting(findGroup(migrated, "General"), "conservative-tnt").putBoolean("value", false);
        assertEquals(snapshot, old);
        assertTrue(findSetting(findGroup(migrated, "TNT Saving"), "conservative-tnt").getBooleanOr("value", false));
    }

    private static CompoundTag setting(String name, boolean value) {
        CompoundTag setting = new CompoundTag();
        setting.putString("name", name);
        setting.putBoolean("value", value);
        return setting;
    }

    private static CompoundTag group(String name, CompoundTag... settings) {
        CompoundTag group = new CompoundTag();
        group.putString("name", name);
        ListTag entries = new ListTag();
        for (CompoundTag setting : settings) entries.add(setting);
        group.put("settings", entries);
        return group;
    }

    private static CompoundTag module(CompoundTag... groups) {
        CompoundTag module = new CompoundTag();
        CompoundTag settings = new CompoundTag();
        ListTag entries = new ListTag();
        for (CompoundTag group : groups) entries.add(group);
        settings.put("groups", entries);
        module.put("settings", settings);
        return module;
    }

    private static CompoundTag findGroup(CompoundTag module, String name) {
        return find(module.getCompoundOrEmpty("settings").getListOrEmpty("groups"), name);
    }

    private static CompoundTag findSetting(CompoundTag group, String name) {
        return find(group.getListOrEmpty("settings"), name);
    }

    private static CompoundTag find(ListTag entries, String name) {
        for (Tag entry : entries) {
            if (entry instanceof CompoundTag compound && compound.getStringOr("name", "").equals(name)) return compound;
        }
        return null;
    }
}
