package dev.tntcoating.compat;

import org.junit.jupiter.api.Test;
import net.fabricmc.loader.api.MappingResolver;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.ArrayList;
import org.objectweb.asm.tree.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldRotationCompatibilityTest {
    @Test void missingModernMixinFallsBackWhenTheProductionReaderThrows() {
        assertEquals(List.of(), problems(name -> {
            ClassNode node = read(name);
            if (node == null) throw new ClassNotFoundException(name);
            return node;
        }));
    }

    private static List<String> problems(MeteorUpgradeCheck.ClassNodes classes) {
        return ScaffoldRotationCompatibility.problems(classes, mappings(false));
    }

    @Test void productionIntermediaryNamesFitTheActualPinnedHooks() throws Exception {
        var runtime = new HashMap<String, ClassNode>();
        for (String name : List.of("net/minecraft/client/player/LocalPlayer",
                "meteordevelopment/meteorclient/mixin/ClientPlayerEntityMixin",
                "meteordevelopment/meteorclient/utils/player/Rotations")) {
            ClassNode mapped = new ClassNode();
            read(name).accept(new ClassRemapper(mapped, new Remapper(org.objectweb.asm.Opcodes.ASM9) {
                @Override public String map(String internalName) {
                    return switch (internalName) {
                        case "net/minecraft/client/player/LocalPlayer" -> "net/minecraft/class_746";
                        case "net/minecraft/world/entity/Entity" -> "net/minecraft/class_129";
                        case "net/minecraft/network/protocol/game/ServerboundMovePlayerPacket" -> "net/minecraft/class_2828";
                        case "net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$Pos" -> "net/minecraft/class_2828$class_2829";
                        case "net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$PosRot" -> "net/minecraft/class_2828$class_2830";
                        case "net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$Rot" -> "net/minecraft/class_2828$class_2831";
                        case "net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$StatusOnly" -> "net/minecraft/class_2828$class_5911";
                        default -> internalName;
                    };
                }
                @Override public String mapMethodName(String owner, String name, String descriptor) {
                    return switch (name) {
                        case "sendPosition" -> "method_3136";
                        case "getYRot" -> "method_36454";
                        case "getXRot" -> "method_36455";
                        default -> name;
                    };
                }
            }));
            for (var method : mapped.methods) {
                var annotations = new ArrayList<AnnotationNode>();
                if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
                if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
                for (var annotation : annotations) if (annotation.values != null) {
                    for (int i = 0; i < annotation.values.size(); i += 2) {
                        if (annotation.values.get(i).equals("method")
                                && annotation.values.get(i + 1) instanceof List<?> selectors) {
                            annotation.values.set(i + 1, selectors.stream().map(selector ->
                                selector.equals("sendPosition") ? "method_3136" : selector).toList());
                        }
                    }
                }
            }
            runtime.put(mapped.name, mapped);
        }
        assertEquals(List.of(), ScaffoldRotationCompatibility.problems(name -> {
            ClassNode node = runtime.get(name);
            if (node == null) throw new ClassNotFoundException(name);
            return node;
        }, mappings(true)));

        runtime.get("net/minecraft/class_746").methods.removeIf(method -> method.name.equals("method_3136"));
        assertFalse(ScaffoldRotationCompatibility.problems(runtime::get, mappings(true)).isEmpty(),
            "A genuinely missing production movement method must still fail closed");
    }

    private static MappingResolver mappings(boolean intermediary) {
        return new MappingResolver() {
            public Collection<String> getNamespaces() { return List.of("named", "intermediary"); }
            public String getCurrentRuntimeNamespace() { return intermediary ? "intermediary" : "named"; }
            public String mapClassName(String namespace, String name) {
                if (!namespace.equals("intermediary")) throw new IllegalArgumentException(namespace);
                if (intermediary) return name;
                return switch (name) {
                    case "net.minecraft.class_746" -> "net.minecraft.client.player.LocalPlayer";
                    case "net.minecraft.class_2828" -> "net.minecraft.network.protocol.game.ServerboundMovePlayerPacket";
                    default -> name;
                };
            }
            public String mapMethodName(String namespace, String owner, String name, String descriptor) {
                if (!namespace.equals("intermediary")) throw new IllegalArgumentException(namespace);
                if (intermediary) return name;
                return switch (owner + "/" + name + descriptor) {
                    case "net.minecraft.class_746/method_3136()V" -> "sendPosition";
                    case "net.minecraft.class_129/method_36454()F" -> "getYRot";
                    case "net.minecraft.class_129/method_36455()F" -> "getXRot";
                    default -> name;
                };
            }
            public String unmapClassName(String namespace, String name) { throw new UnsupportedOperationException(); }
            public String mapFieldName(String namespace, String owner, String name, String descriptor) { throw new UnsupportedOperationException(); }
        };
    }

    private static ClassNode read(String name) throws Exception {
        try (var stream = ScaffoldRotationCompatibilityTest.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) return null;
            var node = new ClassNode(); new ClassReader(stream).accept(node, 0); return node;
        }
    }
    private static String meteorMixin() throws Exception {
        return read("meteordevelopment/meteorclient/mixin/LocalPlayerMixin") != null
            ? "meteordevelopment/meteorclient/mixin/LocalPlayerMixin"
            : "meteordevelopment/meteorclient/mixin/ClientPlayerEntityMixin";
    }
    private static String meteorPreMethod() throws Exception {
        return meteorMixin().endsWith("LocalPlayerMixin") ? "onSendPositionHead" : "onSendMovementPacketsHead";
    }
    @Test void exactOrdinaryHooksAndQueueAccessorFitPinnedBuild() throws Exception {
        assertEquals(List.of(), problems(ScaffoldRotationCompatibilityTest::read));
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
        String name = meteorMixin();
        String preMethod = meteorPreMethod();
        var changed = read(name); changed.methods.removeIf(method -> method.name.equals(preMethod));
        var problems = problems(n -> n.equals(name) ? changed : read(n));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("Pre HEAD")), problems.toString());
    }
    @Test void removedHoldStateFailsClosedAndMissingClassesFailClosed() throws Exception {
        String name = "meteordevelopment/meteorclient/utils/player/Rotations";
        var changed = read(name); changed.fields.removeIf(field -> field.name.equals("lastRotation"));
        assertFalse(problems(n -> n.equals(name) ? changed : read(n)).isEmpty());
        assertFalse(problems(n -> null).isEmpty());
    }
    @Test void rotationReadHookMustPrecedeMovementConstruction() throws Exception {
        String name = "net/minecraft/client/player/LocalPlayer";
        var changed = read(name);
        for (var method : changed.methods) if (method.name.equals("sendPosition"))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("getYRot")) call.name = "changedYaw";
        assertFalse(problems(n -> n.equals(name) ? changed : read(n)).isEmpty());
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
