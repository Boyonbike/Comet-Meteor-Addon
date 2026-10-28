package dev.tntcoating.core;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BorrowedModuleConfigTest {
    private static CompoundTag module(boolean active, String mode, String unrelated) {
        CompoundTag module = new CompoundTag();
        module.putString("name", "auto-walk"); module.putBoolean("active", active);
        ListTag values = new ListTag();
        for (String[] value : new String[][]{{"mode", mode}, {"unrelated", unrelated}}) {
            CompoundTag setting = new CompoundTag(); setting.putString("name", value[0]);
            setting.putString("value", value[1]); values.add(setting);
        }
        CompoundTag group = new CompoundTag(); group.putString("name", "General"); group.put("settings", values);
        ListTag groups = new ListTag(); groups.add(group);
        CompoundTag settings = new CompoundTag(); settings.put("groups", groups); module.put("settings", settings);
        return module;
    }
    @Test void savesOriginalBorrowedPreferenceWithoutRevertingUnrelatedChanges() {
        CompoundTag original = module(false, "Smart", "before");
        CompoundTag running = module(true, "Simple", "changed by user");
        ListTag modules = new ListTag(); modules.add(running);
        CompoundTag config = new CompoundTag(); config.put("modules", modules);
        BorrowedModuleConfig.restore(config, "auto-walk", original, Set.of("mode"));
        assertFalse(running.getBooleanOr("active", true));
        var values = running.getCompoundOrEmpty("settings").getListOrEmpty("groups").getCompoundOrEmpty(0).getListOrEmpty("settings");
        assertEquals("Smart", values.getCompoundOrEmpty(0).getStringOr("value", ""));
        assertEquals("changed by user", values.getCompoundOrEmpty(1).getStringOr("value", ""));
    }
    @Test void snapshotIsCopiedRatherThanAliased() {
        CompoundTag original = module(true, "Smart", "before");
        CompoundTag running = module(false, "Simple", "after");
        ListTag modules = new ListTag(); modules.add(running);
        CompoundTag config = new CompoundTag(); config.put("modules", modules);
        BorrowedModuleConfig.restore(config, "auto-walk", original, Set.of("mode"));
        assertTrue(running.getBooleanOr("active", false));
        running.getCompoundOrEmpty("settings").getListOrEmpty("groups").getCompoundOrEmpty(0)
            .getListOrEmpty("settings").getCompoundOrEmpty(0).putString("value", "later");
        assertEquals("Smart", original.getCompoundOrEmpty("settings").getListOrEmpty("groups").getCompoundOrEmpty(0)
            .getListOrEmpty("settings").getCompoundOrEmpty(0).getStringOr("value", ""));
    }
    @Test void temporaryConflictsRoundTripAsEnabledWithoutChangingOtherModules() {
        CompoundTag flight = new CompoundTag(); flight.putString("name", "flight"); flight.putBoolean("active", false);
        CompoundTag speed = new CompoundTag(); speed.putString("name", "speed"); speed.putBoolean("active", false);
        ListTag modules = new ListTag(); modules.add(flight); modules.add(speed);
        CompoundTag saved = new CompoundTag(); saved.put("modules", modules);
        BorrowedModuleConfig.restorePaused(saved, java.util.Set.of("flight"));
        var reloaded = saved.copy().getListOrEmpty("modules");
        assertTrue(reloaded.getCompoundOrEmpty(0).getBooleanOr("active", false));
        assertFalse(reloaded.getCompoundOrEmpty(1).getBooleanOr("active", true));
    }

}
