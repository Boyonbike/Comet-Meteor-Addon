package dev.tntcoating;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the Phase 0.3 exit gate without starting a client: the recorder is registered, starts inactive, and
 * its compiled classes contain no call that could put a combat packet on the wire.
 */
class CombatRecorderRegistrationTest {
    private static ClassNode node(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (var stream = CombatRecorderRegistrationTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource + " must be packaged");
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    @Test
    void recorderModuleAndCommandAreRegistered() throws Exception {
        ClassNode addon = node("/dev/tntcoating/TntCoatingAddon.class");
        MethodNode initialize = addon.methods.stream()
            .filter(method -> method.name.equals("onInitialize"))
            .findFirst().orElseThrow();
        boolean module = false;
        boolean command = false;
        for (AbstractInsnNode instruction : initialize.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("<init>")) {
                module |= call.owner.equals("dev/tntcoating/modules/CombatRecorderModule");
                command |= call.owner.equals("dev/tntcoating/commands/CombatRecorderCommand");
            }
        }
        assertTrue(module, "CombatRecorderModule must be registered in the addon");
        assertTrue(command, "the label command must be registered in the addon");
    }

    @Test
    void recorderModuleStartsInactive() throws Exception {
        ClassNode module = node("/dev/tntcoating/modules/CombatRecorderModule.class");
        MethodNode constructor = module.methods.stream()
            .filter(method -> method.name.equals("<init>"))
            .findFirst().orElseThrow();
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertNotEquals("enable", call.name, "the recorder must be off by default");
                assertNotEquals("toggle", call.name, "the recorder must be off by default");
            }
        }
    }

    @Test
    void recorderClassesContainNoSendPath() throws Exception {
        String[] classes = {
            "/dev/tntcoating/core/combat/CombatRecorder.class",
            "/dev/tntcoating/core/combat/CombatRecorderBuffer.class",
            "/dev/tntcoating/core/combat/CombatTraceLog.class",
            "/dev/tntcoating/core/combat/mc/CombatRecorderHost.class",
            "/dev/tntcoating/core/combat/mc/ObservationHooks.class",
        };
        for (String resource : classes) {
            ClassNode node = node(resource);
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        assertFalse(forbiddenCall(call), resource + " calls " + call.owner + "." + call.name);
                    }
                    if (instruction instanceof TypeInsnNode type && type.getOpcode() == org.objectweb.asm.Opcodes.NEW) {
                        assertFalse(forbiddenPacket(type.desc), resource + " constructs " + type.desc);
                    }
                }
            }
        }
    }

    private static boolean forbiddenCall(MethodInsnNode call) {
        if (call.owner.equals("net/minecraft/network/Connection")
            && (call.name.equals("send") || call.name.equals("sendPacket"))) return true;
        if (call.owner.startsWith("net/minecraft/client/multiplayer/MultiPlayerGameMode")) return true;
        if (call.owner.startsWith("net/minecraft/server/network/")) return true;
        if (call.owner.equals("net/minecraft/client/player/LocalPlayer")
            && (call.name.equals("swing") || call.name.equals("attack"))) return true;
        if (call.owner.startsWith("meteordevelopment/meteorclient/utils/player/Rotations")) return true;
        if (call.owner.equals("dev/tntcoating/core/InventorySlotLoan")
            || call.owner.equals("dev/tntcoating/core/InventoryWeaponAttack")
            || call.owner.equals("dev/tntcoating/modules/Speedmine")) return true;
        return false;
    }

    private static boolean forbiddenPacket(String internalName) {
        return internalName.startsWith("net/minecraft/network/protocol/game/Serverbound")
            || internalName.startsWith("net/minecraft/network/protocol/common/Serverbound");
    }
}
