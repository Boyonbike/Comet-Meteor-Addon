package dev.tntcoating.core;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** The global config boundary must guard shared helpers before a boat lease releases them. */
class BoatProfileWiringTest {
    @Test void guardsHelpersBeforeReleasingBoatOwnership() throws Exception {
        var type = new ClassNode();
        try (var input = getClass().getResourceAsStream("/dev/tntcoating/mixin/HighwayFlyConfigMixin.class")) {
            assertNotNull(input); new ClassReader(input).accept(type, 0);
        }
        var calls = new ArrayList<String>();
        for (var method : type.methods) if (method.name.equals("comet$migrateHighwayFly"))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.name.equals("beginProfileLoad")) calls.add(call.owner);
        assertEquals(java.util.List.of("dev/tntcoating/modules/HighwayFly", "dev/tntcoating/modules/boatfly/BoatFlyEngine"), calls,
            "AutoLobby must already be guarded when Auto Boat Fly releases its helper ownership");
    }
    @Test void newProfileDiscardsOldConflictRestorationAfterReleasingHelpers() throws Exception {
        var calls = new ArrayList<String>();
        for (var method : type("modules/AutoBoatFly").methods) if (method.name.equals("beginProfileLoad"))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && (call.name.equals("onDeactivate") || call.name.equals("beginProfileLoad"))) calls.add(call.name);
        assertEquals(java.util.List.of("onDeactivate", "beginProfileLoad"), calls,
            "Auto Boat Fly must release its helpers before the engine discards conflict restoration");
        assertTrue(calls("modules/boatfly/BoatFlyEngine", "beginProfileLoad").contains("discardRestorationForProfileLoad"),
            "An incoming profile must not inherit a pending request to enable old movement modules");
    }
    @Test void profileSavePreservesTheEngineConflictPreferencesAsWellAsHelperSettings() throws Exception {
        assertTrue(calls("modules/AutoBoatFly", "restoreBorrowedInSavedConfig").contains("restoreBorrowedInSavedConfig"),
            "Auto Boat Fly must keep the engine's saved-profile handling");
        assertTrue(calls("modules/boatfly/BoatFlyEngine", "restoreBorrowedInSavedConfig").contains("restorePausedInSavedConfig"),
            "Temporary movement conflicts must not become disabled in saved profiles, for either boat module");
    }

    private ClassNode type(String name) throws Exception {
        var type = new ClassNode();
        try (var input = getClass().getResourceAsStream("/dev/tntcoating/" + name + ".class")) {
            assertNotNull(input); new ClassReader(input).accept(type, 0);
        }
        return type;
    }

    private java.util.List<String> calls(String owner, String methodName) throws Exception {
        var calls = new ArrayList<String>();
        for (var method : type(owner).methods) if (method.name.equals(methodName))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) calls.add(call.name);
        return calls;
    }

    @Test void lobbyDismountUsesTheTripRetentionPolicy() throws Exception {
        var type = new ClassNode();
        try (var input = getClass().getResourceAsStream("/dev/tntcoating/modules/AutoLobby.class")) {
            assertNotNull(input); new ClassReader(input).accept(type, 0);
        }
        boolean retainsNativeTrip = false;
        for (var method : type.methods) if (method.name.equals("onTick"))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals("dev/tntcoating/core/BoatRecoveryPolicy") && call.name.equals("keepTrip")) retainsNativeTrip = true;
        assertTrue(retainsNativeTrip, "A dismount must reach the native ownership policy before recovery can be cancelled");
    }

}
