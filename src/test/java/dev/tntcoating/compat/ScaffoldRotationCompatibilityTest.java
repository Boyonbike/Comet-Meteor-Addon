package dev.tntcoating.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldRotationCompatibilityTest {
    private static ClassNode read(String name) throws Exception {
        try (var stream = ScaffoldRotationCompatibilityTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) return null;
            var node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }
    @Test void exactOrdinaryHooksAndQueueAccessorFitPinnedBuild() throws Exception {
        assertEquals(List.of(), ScaffoldRotationCompatibility.problems(ScaffoldRotationCompatibilityTest::read));
        for (String mixin : List.of("ScaffoldMovementMixin", "ScaffoldRotationsAccessor", "ScaffoldLegacyOwnershipMixin", "ScaffoldLegacyDispatchMixin"))
            assertEquals(List.of(), MeteorUpgradeCheck.problems(read("dev/tntcoating/mixin/" + mixin), ScaffoldRotationCompatibilityTest::read), mixin);
    }
    @Test void disabledLegacyUpgradeDoesNotDisableIndependentOwnershipGuard() throws Exception {
        String name = "meteordevelopment/meteorclient/systems/modules/movement/Scaffold";
        var changed = read(name); changed.methods.removeIf(method -> method.name.equals("onTick"));
        MeteorUpgradeCheck.ClassNodes classes = n -> n.equals(name) ? changed : read(n);
        assertFalse(MeteorUpgradeCheck.problems(read("dev/tntcoating/mixin/ScaffoldMixin"), classes).isEmpty());
        assertEquals(List.of(), MeteorUpgradeCheck.problems(read("dev/tntcoating/mixin/ScaffoldLegacyOwnershipMixin"), classes));
        changed.methods.removeIf(method -> method.name.equals("place"));
        assertFalse(MeteorUpgradeCheck.problems(read("dev/tntcoating/mixin/ScaffoldLegacyOwnershipMixin"), classes).isEmpty());
    }
    @Test void missingMeteorPreHookFailsClosed() throws Exception {
        String name = "meteordevelopment/meteorclient/mixin/LocalPlayerMixin";
        var changed = read(name); changed.methods.removeIf(method -> method.name.equals("onSendPositionHead"));
        var problems = ScaffoldRotationCompatibility.problems(n -> n.equals(name) ? changed : read(n));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("Pre HEAD")), problems.toString());
    }
    @Test void removedHoldStateFailsClosedAndMissingClassesFailClosed() throws Exception {
        String name = "meteordevelopment/meteorclient/utils/player/Rotations";
        var changed = read(name); changed.fields.removeIf(field -> field.name.equals("lastRotation"));
        assertFalse(ScaffoldRotationCompatibility.problems(n -> n.equals(name) ? changed : read(n)).isEmpty());
        assertFalse(ScaffoldRotationCompatibility.problems(n -> null).isEmpty());
    }
    @Test void rotationReadHookMustPrecedeMovementConstruction() throws Exception {
        String name = "net/minecraft/client/player/LocalPlayer";
        var changed = read(name);
        for (var method : changed.methods) if (method.name.equals("sendPosition"))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("getYRot")) call.name = "changedYaw";
        assertFalse(ScaffoldRotationCompatibility.problems(n -> n.equals(name) ? changed : read(n)).isEmpty());
    }
    @Test void productionAdapterRestoresBeforeCallbacksAndCatchesExceptionalMovementExit() throws Exception {
        var bridge = read("dev/tntcoating/core/scaffold/ScaffoldRotationBridge");
        var finish = bridge.methods.stream().filter(method -> method.name.equals("finish")).findFirst().orElseThrow();
        int restored = -1, finished = -1, callback = -1, index = 0;
        for (var instruction : finish.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("restore")) restored = index;
                if (call.owner.endsWith("OrdinaryRotationSlot") && call.name.equals("finish")) finished = index;
                if (call.owner.equals("java/lang/Runnable") && call.name.equals("run")) callback = index;
            }
            index++;
        }
        assertTrue(restored >= 0 && finished > restored && callback > finished);
        var movement = read("dev/tntcoating/mixin/ScaffoldMovementMixin");
        var wrap = movement.methods.stream().filter(method -> method.name.equals("comet$restoreOrdinaryRotation")).findFirst().orElseThrow();
        assertTrue(wrap.tryCatchBlocks.stream().anyMatch(handler -> handler.type == null), "finally must catch every exceptional exit");
        for (var method : bridge.methods) for (var instruction : method.instructions)
            if (instruction instanceof FieldInsnNode field) assertFalse(field.name.equals("serverYaw") || field.name.equals("serverPitch"), "planned angles cannot be send evidence");
    }
    @Test void bridgeCreatesNoPacketsAndNeverSubmitsOrRemovesMeteorRotations() throws Exception {
        var bridge = read("dev/tntcoating/core/scaffold/ScaffoldRotationBridge");
        for (var method : bridge.methods) for (var instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type) assertFalse(type.desc.contains("ServerboundMovePlayerPacket$"));
            if (instruction instanceof MethodInsnNode call && call.owner.equals("meteordevelopment/meteorclient/utils/player/Rotations") && !call.name.equals("setCamRotation"))
                fail("bridge calls Meteor rotation mutation: " + call.name);
        }
    }
}
