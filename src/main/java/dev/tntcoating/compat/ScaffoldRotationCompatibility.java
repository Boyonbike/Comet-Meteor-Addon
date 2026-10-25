package dev.tntcoating.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;
import java.util.List;

/** Bytecode-only fail-closed contract for the movement slot, including Meteor's injected Pre/Post lifecycle. */
public final class ScaffoldRotationCompatibility {
    private static final String ROTATIONS = "meteordevelopment/meteorclient/utils/player/Rotations";
    private ScaffoldRotationCompatibility() {}
    public static List<String> problems(MeteorUpgradeCheck.ClassNodes classes) {
        try {
            return problems(classes, FabricLoader.getInstance().getMappingResolver());
        } catch (Exception exception) {
            return List.of("cannot resolve ordinary movement mappings: " + exception.getClass().getSimpleName());
        }
    }
    static List<String> problems(MeteorUpgradeCheck.ClassNodes classes, MappingResolver mappings) {
        List<String> problems = new ArrayList<>();
        try {
            // Intermediary identifiers resolve in both development and the distributed Minecraft client.
            // Class literals would load the player before its mixins have been applied.
            String playerName = mappings.mapClassName("intermediary", "net.minecraft.class_746").replace('.', '/');
            String packetName = mappings.mapClassName("intermediary", "net.minecraft.class_2828").replace('.', '/');
            String sendName = mappings.mapMethodName("intermediary", "net.minecraft.class_746", "method_3136", "()V");
            String yawName = mappings.mapMethodName("intermediary", "net.minecraft.class_129", "method_36454", "()F");
            String pitchName = mappings.mapMethodName("intermediary", "net.minecraft.class_129", "method_36455", "()F");
            ClassNode meteor = optional(classes, "meteordevelopment/meteorclient/mixin/LocalPlayerMixin");
            ClassNode rotations = classes.get(ROTATIONS);
            ClassNode player = classes.get(playerName);
            if (meteor == null) meteor = optional(classes, "meteordevelopment/meteorclient/mixin/ClientPlayerEntityMixin");
            if (meteor == null || rotations == null || player == null) return List.of("ordinary movement hook classes missing");
            String head = method(meteor, "onSendPositionHead", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V") != null
                ? "onSendPositionHead" : "onSendMovementPacketsHead";
            String tail = method(meteor, "onSendPositionTail", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V") != null
                ? "onSendPositionTail" : "onSendMovementPacketsTail";
            if (!eventHook(meteor, head, "HEAD", "Pre", sendName)) problems.add("Meteor sendPosition Pre HEAD hook changed");
            if (!eventHook(meteor, tail, "TAIL", "Post", sendName)) problems.add("Meteor sendPosition Post TAIL hook changed");
            MethodNode send = method(player, sendName, "()V");
            if (send == null) problems.add("vanilla sendPosition missing");
            else {
                int yaw = -1, pitch = -1, movement = -1, index = 0;
                for (AbstractInsnNode instruction : send.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(playerName)) {
                        if (yaw < 0 && call.name.equals(yawName) && call.desc.equals("()F")) yaw = index;
                        if (pitch < 0 && call.name.equals(pitchName) && call.desc.equals("()F")) pitch = index;
                    }
                    if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                        && type.desc.startsWith(packetName + "$") && movement < 0) movement = index;
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
    private static ClassNode optional(MeteorUpgradeCheck.ClassNodes classes, String name) throws Exception {
        try { return classes.get(name); }
        catch (ClassNotFoundException missing) { return null; }
    }
    private static boolean eventHook(ClassNode node, String name, String point, String event, String selector) {
        MethodNode method = method(node, name, "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V");
        if (method == null) return false;
        boolean inject = false, factory = false, busPost = false;
        for (AnnotationNode annotation : annotations(method)) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
            Object methods = value(annotation, "method");
            if (!(methods instanceof List<?> selectors)
                || (!selectors.contains(selector) && !selectors.contains(selector + "()V"))) continue;
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
