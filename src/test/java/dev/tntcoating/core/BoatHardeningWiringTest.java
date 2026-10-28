package dev.tntcoating.core;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

/** Critical protections must remain ahead of the controller's world-wait early returns. */
class BoatHardeningWiringTest {
    private ClassNode type(String name) throws Exception {
        var type = new ClassNode();
        try (var input = getClass().getResourceAsStream("/dev/tntcoating/" + name + ".class")) {
            assertNotNull(input); new ClassReader(input).accept(type, 0);
        }
        return type;
    }
    @Test void immediateProtectionRunsBeforeWorldGuardsAndTravelSafety() throws Exception {
        var calls = new ArrayList<String>();
        for (var method : type("modules/AutoBoatFly").methods) if (method.name.equals("boatTripTick"))
            for (var insn : method.instructions) if (insn instanceof MethodInsnNode call
                && (call.name.equals("immediateProtection") || call.name.equals("sameTripWorld") || call.name.equals("evaluate"))) calls.add(call.name);
        assertEquals("immediateProtection", calls.getFirst());
    }
    @Test void terminalLogoutDisablesReconnectAfterReleasingTheTripAndBeforeDisconnecting() throws Exception {
        var calls = new ArrayList<String>();
        for (var method : type("modules/AutoBoatFly").methods) if (method.name.equals("logOut"))
            for (var insn : method.instructions) if (insn instanceof MethodInsnNode call)
                if (java.util.Set.of("cancelRecovery", "holdRestorationForDisconnect", "disable", "releaseReconnect", "handleDisconnect").contains(call.name))
                    calls.add(call.name);
        assertEquals(java.util.List.of("cancelRecovery", "holdRestorationForDisconnect", "disable", "releaseReconnect", "disable", "handleDisconnect"), calls);
    }
    @Test void managedEatingCallersSupplyTheInventorySafetyGuard() throws Exception {
        var controller = type("modules/AutoBoatFly");
        for (String name : java.util.List.of("syncHelpers", "boatTripTick")) {
            var calls = new ArrayList<String>();
            for (var method : controller.methods) if (method.name.equals(name))
                for (var insn : method.instructions) if (insn instanceof MethodInsnNode call
                    && (call.name.equals("inventoryAccessSafe") || call.name.equals("eating"))) calls.add(call.name);
            assertEquals(java.util.List.of("inventoryAccessSafe", "eating"), calls);
        }
        var calls = new ArrayList<String>();
        for (var method : type("modules/BoatAutomationLease").methods) if (method.name.equals("eating"))
            for (var insn : method.instructions) if (insn instanceof MethodInsnNode call
                && (call.name.equals("enable") || call.name.equals("disable"))) calls.add(call.name);
        assertEquals(java.util.List.of("enable", "disable"), calls, "Unsafe eating must pause the leased module");
    }
}
