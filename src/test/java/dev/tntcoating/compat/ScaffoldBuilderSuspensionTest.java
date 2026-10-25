package dev.tntcoating.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production integration checks: maintenance precedes suspension, queued callbacks have a live ownership gate. */
class ScaffoldBuilderSuspensionTest {
    private static final String COORDINATOR = "dev/tntcoating/core/scaffold/ScaffoldCoordinator";
    private static ClassNode read(String name) throws Exception {
        try (var stream = ScaffoldBuilderSuspensionTest.class.getClassLoader().getResourceAsStream("dev/tntcoating/modules/" + name + ".class")) {
            assertNotNull(stream); var node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }
    private static int call(MethodNode node, String ownerSuffix, String name) {
        int index = 0;
        for (var instruction : node.instructions) {
            if (instruction instanceof MethodInsnNode invocation && invocation.owner.endsWith(ownerSuffix) && invocation.name.equals(name)) return index;
            index++;
        }
        return -1;
    }
    @Test void blockCoatSettlesSentWorkAndAccountsPacketsBeforePauseGate() throws Exception {
        var node = read("BlockCoat"); var tick = method(node, "onTick");
        int pause = call(tick, COORDINATOR, "buildersPaused");
        assertTrue(pause > call(tick, "PacketBudget", "record"));
        assertTrue(pause > call(tick, "BlockCoat", "drainUpdates"));
        assertTrue(pause > call(tick, "BlockCoat", "expirePending"));
        var suspension = method(node, "scaffoldSuspension");
        assertTrue(call(suspension, "PlacementTracker", "discardUnsent") >= 0);
        assertTrue(call(suspension, "BlockCoat", "releaseSticky") >= 0);
        assertTrue(call(suspension, "BlockCoat", "clearPreview") >= 0);
        assertEquals(-1, call(suspension, "PlacementTracker", "clear"));
        assertTrue(node.methods.stream().filter(method -> method.name.startsWith("lambda$attempt$"))
            .anyMatch(callback -> call(callback, COORDINATOR, "buildersPaused") >= 0));
    }
    @Test void spongeKeepsOwnershipAndRetriesWhilePlacementAndMiningCallbacksHaveLiveGate() throws Exception {
        var node = read("SmartSponge"); var tick = method(node, "tick");
        int pause = call(tick, COORDINATOR, "buildersPaused");
        assertTrue(pause > call(tick, "PacketBudget", "record"));
        assertTrue(pause > call(tick, "Queue", "poll"));
        var suspension = method(node, "scaffoldSuspension");
        assertTrue(call(suspension, "SmartSponge", "stopMining") >= 0);
        assertEquals(-1, call(suspension, "Set", "clear"));
        assertEquals(-1, call(suspension, "Map", "clear"));
        for (String prefix : new String[]{"lambda$place$", "lambda$collectWet$"})
            assertTrue(node.methods.stream().filter(method -> method.name.startsWith(prefix))
                .anyMatch(callback -> call(callback, COORDINATOR, "buildersPaused") >= 0), prefix);
        assertTrue(call(method(node, "blocksCoating"), COORDINATOR, "buildersPaused") >= 0);
    }

    @Test void possibleBuilderPredictionsAreRetainedIndependentlyOfTheirModuleTimeouts() throws Exception {
        for (String[] entry : new String[][]{{"BlockCoat", "lambda$attempt$"}, {"SmartSponge", "lambda$place$"}}) {
            var node = read(entry[0]);
            assertTrue(node.methods.stream().filter(m -> m.name.startsWith(entry[1]))
                .anyMatch(m -> call(m, COORDINATOR, "prediction") >= 0),
                entry[0] + " must quarantine actual dispatch before its own tracker can expire/reset");
        }
    }
}
