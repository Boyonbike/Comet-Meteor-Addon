package dev.tntcoating.compat;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks, from bytecode alone, that a Comet mixin still fits the Meteor classes it targets: every target class,
 * shadowed member, invoked member, injector method, INVOKE/FIELD injection point and modified constant must exist.
 * It is deliberately at least as strict as Mixin, so an upgrade that passes cannot fail to apply.
 */
public final class MeteorUpgradeCheck {
    /** Returns the class node for a binary or internal class name, or null when the class does not exist. */
    @FunctionalInterface
    public interface ClassNodes {
        ClassNode get(String name) throws Exception;
    }

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final Set<String> INJECTORS = Set.of(
        "Lorg/spongepowered/asm/mixin/injection/Inject;",
        "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
        "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
        "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;");

    private MeteorUpgradeCheck() {}

    /** Internal names of the classes this mixin targets. */
    public static List<String> targets(ClassNode mixin) {
        List<String> targets = new ArrayList<>();
        AnnotationNode annotation = annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, MIXIN);
        if (annotation == null) return targets;
        for (Object value : list(value(annotation, "value"))) if (value instanceof Type type) targets.add(type.getInternalName());
        for (Object value : list(value(annotation, "targets"))) if (value instanceof String name) targets.add(name.replace('.', '/'));
        return targets;
    }

    /** Only Meteor's own classes change between the snapshot builds Comet may meet; Minecraft is pinned. */
    public static boolean targetsMeteor(ClassNode mixin) {
        for (String target : targets(mixin)) if (target.startsWith("meteordevelopment/")) return true;
        return false;
    }

    /** Everything that would stop this mixin applying; empty when it fits. */
    public static List<String> problems(ClassNode mixin, ClassNodes classes) {
        List<String> problems = new ArrayList<>();
        List<ClassNode> targets = new ArrayList<>();
        for (String name : targets(mixin)) {
            ClassNode target = read(classes, name);
            if (target == null) problems.add("target class " + name.replace('/', '.') + " is missing");
            else targets.add(target);
        }
        if (targets.isEmpty()) {
            if (problems.isEmpty()) problems.add("no target classes");
            return problems;
        }
        for (ClassNode target : targets) {
            String owner = target.name.substring(target.name.lastIndexOf('/') + 1);
            if (mixin.fields != null) for (FieldNode field : mixin.fields) {
                if (annotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW) != null && !hasField(target, field.name, field.desc))
                    problems.add(owner + " has no field " + field.name);
            }
            for (MethodNode method : mixin.methods) checkMethod(method, target, owner, problems);
        }
        return problems;
    }

    private static void checkMethod(MethodNode method, ClassNode target, String owner, List<String> problems) {
        if (annotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW) != null
            && !hasMethod(target, method.name, method.desc)) {
            problems.add(owner + " has no method " + method.name + method.desc);
        }
        AnnotationNode invoker = annotation(method.visibleAnnotations, method.invisibleAnnotations, INVOKER);
        if (invoker != null && value(invoker, "value") instanceof String name && !hasMethod(target, name, method.desc))
            problems.add(owner + " has no method " + name + method.desc + " to invoke");
        AnnotationNode accessor = annotation(method.visibleAnnotations, method.invisibleAnnotations, ACCESSOR);
        if (accessor != null && value(accessor, "value") instanceof String name && findField(target, name) == null)
            problems.add(owner + " has no field " + name + " to access");
        for (AnnotationNode annotation : all(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (INJECTORS.contains(annotation.desc)) checkInjector(annotation, method.name, target, owner, problems);
        }
    }

    private static void checkInjector(AnnotationNode injector, String handler, ClassNode target, String owner, List<String> problems) {
        List<MethodNode> methods = new ArrayList<>();
        for (Object selector : list(value(injector, "method"))) {
            List<MethodNode> matched = select(target, (String) selector);
            if (matched.isEmpty()) problems.add(owner + " has no method " + selector + " for " + handler);
            methods.addAll(matched);
        }
        if (methods.isEmpty()) return;
        int required = value(injector, "require") instanceof Integer require ? require : 1;
        if (required <= 0) return;
        List<Object> points = new ArrayList<>(list(value(injector, "at")));
        int found = 0;
        if (!points.isEmpty()) {
            for (Object point : points) for (MethodNode method : methods) found += matches(method, (AnnotationNode) point);
        } else {
            // ModifyConstant names constants instead of injection points.
            List<Object> constants = list(value(injector, "constant"));
            if (constants.isEmpty()) return;
            for (Object constant : constants) for (MethodNode method : methods) found += constants(method, (AnnotationNode) constant);
        }
        if (found < required) problems.add(owner + " no longer has the injection point " + describe(injector) + " used by " + handler);
    }

    /** Matching instructions for one @At; points that always exist count as one. */
    private static int matches(MethodNode method, AnnotationNode at) {
        String kind = value(at, "value") instanceof String value ? value : "HEAD";
        String target = value(at, "target") instanceof String value ? value : null;
        int ordinal = value(at, "ordinal") instanceof Integer value ? value : -1;
        int count = 0;
        switch (kind) {
            case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> {
                if (target == null) return 1;
                MemberRef ref = MemberRef.parse(target, true);
                if (ref == null) return 1;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && ref.matches(call.owner, call.name, call.desc)) count++;
                }
            }
            case "FIELD" -> {
                if (target == null) return 1;
                MemberRef ref = MemberRef.parse(target, false);
                if (ref == null) return 1;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof FieldInsnNode field && ref.matches(field.owner, field.name, field.desc)) count++;
                }
            }
            // HEAD, RETURN, TAIL and other points exist in every method body.
            default -> { return 1; }
        }
        return ordinal < 0 ? count : count > ordinal ? 1 : 0;
    }

    private static int constants(MethodNode method, AnnotationNode constant) {
        if (!(value(constant, "intValue") instanceof Integer wanted)) return 1;
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            Integer loaded = intConstant(insn);
            if (loaded != null && loaded.intValue() == wanted) count++;
        }
        return count;
    }

    private static Integer intConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if ((opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) && insn instanceof IntInsnNode push) return push.operand;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        return null;
    }

    /** Selectors are "name" (any overload) or "name(descriptor)". */
    private static List<MethodNode> select(ClassNode target, String selector) {
        int paren = selector.indexOf('(');
        String name = paren < 0 ? selector : selector.substring(0, paren);
        String desc = paren < 0 ? null : selector.substring(paren);
        List<MethodNode> matched = new ArrayList<>();
        for (MethodNode method : target.methods) {
            if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) matched.add(method);
        }
        return matched;
    }

    private static boolean hasMethod(ClassNode target, String name, String desc) {
        for (MethodNode method : target.methods) if (method.name.equals(name) && method.desc.equals(desc)) return true;
        return false;
    }

    private static boolean hasField(ClassNode target, String name, String desc) {
        FieldNode field = findField(target, name);
        return field != null && field.desc.equals(desc);
    }

    private static FieldNode findField(ClassNode target, String name) {
        if (target.fields != null) for (FieldNode field : target.fields) if (field.name.equals(name)) return field;
        return null;
    }

    private static ClassNode read(ClassNodes classes, String name) {
        try {
            return classes.get(name);
        } catch (Exception missing) {
            return null;
        }
    }

    private static String describe(AnnotationNode injector) {
        StringBuilder text = new StringBuilder(injector.desc.substring(injector.desc.lastIndexOf('/') + 1, injector.desc.length() - 1));
        for (Object point : list(value(injector, "at"))) {
            if (point instanceof AnnotationNode at && value(at, "target") instanceof String target) text.append(' ').append(target);
        }
        for (Object constant : list(value(injector, "constant"))) {
            if (constant instanceof AnnotationNode node && value(node, "intValue") instanceof Integer number) text.append(" constant ").append(number);
        }
        return text.toString();
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
        for (AnnotationNode annotation : all(visible, invisible)) if (annotation.desc.equals(desc)) return annotation;
        return null;
    }

    private static List<AnnotationNode> all(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    /** Annotation arrays arrive as lists; single values are wrapped. */
    private static List<Object> list(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> values) return new ArrayList<>(values);
        return List.of(value);
    }

    /** "Lowner;name(desc)" for methods or "Lowner;name:desc" for fields; owner and descriptor are optional. */
    private record MemberRef(String owner, String name, String desc) {
        static MemberRef parse(String target, boolean method) {
            String text = target;
            String owner = null;
            if (text.startsWith("L") && text.indexOf(';') > 0) {
                owner = text.substring(1, text.indexOf(';'));
                text = text.substring(text.indexOf(';') + 1);
            }
            int split = method ? text.indexOf('(') : text.indexOf(':');
            String name = split < 0 ? text : text.substring(0, split);
            String desc = split < 0 ? null : text.substring(method ? split : split + 1);
            return name.isEmpty() ? null : new MemberRef(owner, name, desc);
        }

        boolean matches(String owner, String name, String desc) {
            return (this.owner == null || this.owner.equals(owner)) && this.name.equals(name)
                && (this.desc == null || this.desc.equals(desc));
        }
    }
}
