package dev.tntcoating;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class HighwayFlyRegistrationTest {
    @Test void registersOneHighwayModule() throws Exception {
        ClassNode addon = new ClassNode();
        try (var stream = getClass().getResourceAsStream("/dev/tntcoating/TntCoatingAddon.class")) {
            new ClassReader(stream).accept(addon, 0);
        }
        var registered = new HashSet<String>();
        var initialize = addon.methods.stream().filter(m -> m.name.equals("onInitialize")).findFirst().orElseThrow();
        for (var instruction : initialize.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("<init>")) registered.add(call.owner);
        }
        assertTrue(registered.contains("dev/tntcoating/modules/HighwayFly"));
        for (String old : new String[] {"SmartAutoFly", "AutoLobby", "AutoSetHome", "AntiPortal"}) {
            assertFalse(registered.contains("dev/tntcoating/modules/" + old), old + " must be part of Highway Fly");
        }
    }
}
