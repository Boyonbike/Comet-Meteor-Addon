package dev.tntcoating.compat;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;
import java.util.List;

/** Bytecode-only fail-closed contract for the movement slot, including Meteor's injected Pre/Post lifecycle. */
public final class ScaffoldRotationCompatibility {
    private static final String PLAYER = "net/minecraft/client/player/LocalPlayer";
    private static final String ROTATIONS = "meteordevelopment/meteorclient/utils/player/Rotations";
    private ScaffoldRotationCompatibility() {}
    public static List<String> problems(MeteorUpgradeCheck.ClassNodes classes) {
        List<String> problems = new ArrayList<>();
        try {
            ClassNode meteor = classes.get("meteordevelopment/meteorclient/mixin/LocalPlayerMixin");
            ClassNode rotations = classes.get(ROTATIONS);
            ClassNode player = classes.get(PLAYER);
            if (meteor == null || rotations == null || player == null) return List.of("ordinary movement hook classes missing");
            if (!eventHook(meteor, "onSendPositionHead", "HEAD", "Pre")) problems.add("Meteor sendPosition Pre HEAD hook changed");
            if (!eventHook(meteor, "onSendPositionTail", "TAIL", "Post")) problems.add("Meteor sendPosition Post TAIL hook changed");
            MethodNode send = method(player, "sendPosition", "()V");
            if (send == null) problems.add("vanilla sendPosition missing");
            else {
                int yaw = -1, pitch = -1, movement = -1, index = 0;
                for (AbstractInsnNode instruction : send.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(PLAYER)) {
                        if (yaw < 0 && call.name.equals("getYRot") && call.desc.equals("()F")) yaw = index;
                        if (pitch < 0 && call.name.equals("getXRot") && call.desc.equals("()F")) pitch = index;
                    }
                    if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                        && type.desc.startsWith("net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$") && movement < 0) movement = index;
                    index++;
                }
                if (yaw < 0 || pitch <= yaw || movement <= pitch) problems.add("vanilla ordinary rotation reads changed");
            }
            if (!field(rotations, "rotations", "Ljava/util/List;") || !field(rotations, "rotating", "Z")
                || !field(rotations, "lastRotation", "L" + ROTATIONS + "$Rotation;") || !field(rotations, "i", "I"))
                problems.add("Meteor rotation queue/hold contract changed");
            MethodNode pre = method(rotations, "onSendMovementPacketsPre", "(Lmeteordevelopment/meteorclient/events/entity/player/SendMovementPacketsEvent$Pre;)V");
            MethodNode post = method(rotations, "onSendMovementPacketsPost", "(Lmeteordevelopment/meteorclient/events/entity/player/SendMovementPacketsEvent$Post;)V");
            if (pre == null || post == null) problems.add("Meteor movement rotation handlers changed");
            else {
                if (!references(pre, "lastRotation") || !references(pre, "rotating") || !references(pre, "rotations"))
                    problems.add("Meteor rotation hold detection changed");
                if (!references(post, "i") || !references(post, "rotations")) problems.add("Meteor rotation queue lifecycle changed");
            }
        } catch (Exception exception) { problems.add("cannot verify ordinary movement hooks: " + exception.getClass().getSimpleName()); }
        return List.copyOf(problems);
    }
    private static boolean eventHook(ClassNode node, String name, String point, String event) {
        MethodNode method = method(node, name, "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        if (method == null) return false;
        boolean inject = false, factory = false, busPost = false;
        for (AnnotationNode annotation : annotations(method)) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
            Object methods = value(annotation, "method");
            if (!(methods instanceof List<?> selectors) || !selectors.contains("sendPosition")) continue;
            if (value(annotation, "at") instanceof List<?> ats) for (Object at : ats) {
                if (at instanceof AnnotationNode a && point.equals(value(a, "value"))) inject = true;
            }
        }
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.owner.equals("meteordevelopment/meteorclient/events/entity/player/SendMovementPacketsEvent$" + event) && call.name.equals("get")) factory = true;
            if (call.owner.equals("meteordevelopment/orbit/IEventBus") && call.name.equals("post")) busPost = true;
        }
        return inject && factory && busPost;
    }
    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) result.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) result.addAll(method.invisibleAnnotations);
        return result;
    }
    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        return null;
    }
    private static boolean field(ClassNode node, String name, String desc) {
        return node.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(desc));
    }
    private static boolean references(MethodNode node, String field) {
        for (AbstractInsnNode instruction : node.instructions)
            if (instruction instanceof FieldInsnNode access && access.owner.equals(ROTATIONS) && access.name.equals(field)) return true;
        return false;
    }
    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(desc)).findFirst().orElse(null);
    }
}
