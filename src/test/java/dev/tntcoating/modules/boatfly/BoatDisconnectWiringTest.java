package dev.tntcoating.modules.boatfly;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class BoatDisconnectWiringTest {
    @Test void closesRestorationGateBeforeDisconnectDetachesTheBoat() throws Exception {
        var type = new ClassNode();
        try (var input = getClass().getResourceAsStream("/dev/tntcoating/modules/boatfly/BoatFlyEngine.class")) {
            assertNotNull(input); new ClassReader(input).accept(type, 0);
        }
        var calls = new ArrayList<String>();
        for (var method : type.methods) if (method.name.equals("onGameLeft"))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call)
                if (call.name.equals("worldLeft") || call.name.equals("detach")) calls.add(call.name);
        assertEquals(java.util.List.of("worldLeft", "detach"), calls);
    }
    @Test void detachNeverDirectlyEnablesPausedModules() throws Exception {
        var type = new ClassNode();
        try (var input = getClass().getResourceAsStream("/dev/tntcoating/modules/boatfly/BoatFlyEngine.class")) {
            assertNotNull(input); new ClassReader(input).accept(type, 0);
        }
        for (var method : type.methods) if (method.name.equals("detach"))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call)
                assertFalse(call.name.equals("toggle") || call.name.equals("enable"), "Restoration must pass the world lifecycle gate");
    }
}
