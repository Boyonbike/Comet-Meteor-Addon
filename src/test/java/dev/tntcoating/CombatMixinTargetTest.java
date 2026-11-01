package dev.tntcoating;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every hook injection must name a method that exists in the pinned 26.2 artifact. The mixin config uses
 * {@code defaultRequire: 1}, so a stale descriptor would crash startup; this catches it without launching.
 */
class CombatMixinTargetTest {
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    private static ClassNode node(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (var stream = CombatMixinTargetTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource + " must be on the test classpath");
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static List<String> injectionMethods(ClassNode mixin) {
        List<String> methods = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            List<AnnotationNode> annotations = new ArrayList<>();
            if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
            if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
            for (AnnotationNode annotation : annotations) {
                if (!INJECT.equals(annotation.desc)) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (!"method".equals(annotation.values.get(i))) continue;
                    Object value = annotation.values.get(i + 1);
                    if (value instanceof String single) {
                        methods.add(single);
                    } else if (value instanceof List<?> list) {
                        for (Object entry : list) methods.add((String) entry);
                    }
                }
            }
        }
        return methods;
    }

    private static void assertTargetsExist(String targetResource, String mixinResource) throws Exception {
        ClassNode target = node(targetResource);
        ClassNode mixin = node(mixinResource);
        List<String> targets = injectionMethods(mixin);
        assertFalse(targets.isEmpty(), mixinResource + " must declare injections");
        for (String descriptor : targets) {
            int parenthesis = descriptor.indexOf('(');
            assertTrue(parenthesis > 0, "descriptor must carry a signature: " + descriptor);
            String name = descriptor.substring(0, parenthesis);
            String signature = descriptor.substring(parenthesis);
            boolean found = target.methods.stream()
                .anyMatch(method -> method.name.equals(name) && method.desc.equals(signature));
            assertTrue(found, mixinResource + " targets missing " + target.name + "." + descriptor);
        }
    }

    @Test
    void clientPacketHooksTargetRealHandlerMethods() throws Exception {
        assertTargetsExist("/net/minecraft/client/multiplayer/ClientPacketListener.class",
            "/dev/tntcoating/mixin/CombatPacketHooksMixin.class");
    }

    @Test
    void connectionHooksTargetChannelRead0() throws Exception {
        assertTargetsExist("/net/minecraft/network/Connection.class",
            "/dev/tntcoating/mixin/CombatConnectionHooksMixin.class");
    }

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";

    private static List<String> annotationValues(AnnotationNode annotation, String key) {
        List<String> values = new ArrayList<>();
        if (annotation == null || annotation.values == null) return values;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (!key.equals(annotation.values.get(i))) continue;
            Object value = annotation.values.get(i + 1);
            if (value instanceof String single) {
                values.add(single);
            } else if (value instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof org.objectweb.asm.Type type) {
                        values.add(type.getInternalName());
                    } else {
                        values.add(String.valueOf(entry));
                    }
                }
            } else if (value instanceof org.objectweb.asm.Type type) {
                values.add(type.getInternalName());
            }
        }
        return values;
    }

    private static AnnotationNode annotation(ClassNode node, String descriptor) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
        for (AnnotationNode candidate : annotations) {
            if (descriptor.equals(candidate.desc)) return candidate;
        }
        return null;
    }

    private static void assertAccessorTargetsRealField(String mixinResource, String targetResource)
        throws Exception {
        ClassNode mixin = node(mixinResource);
        ClassNode target = node(targetResource);
        AnnotationNode mixinAnnotation = annotation(mixin, MIXIN);
        assertNotNull(mixinAnnotation, mixinResource + " must declare @Mixin");
        List<String> targets = annotationValues(mixinAnnotation, "targets");
        targets.addAll(annotationValues(mixinAnnotation, "value"));
        assertTrue(targets.stream().anyMatch(name -> name.equals(target.name)),
            mixinResource + " must target " + target.name);
        AnnotationNode accessor = null;
        for (MethodNode method : mixin.methods) {
            List<AnnotationNode> annotations = new ArrayList<>();
            if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
            if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
            for (AnnotationNode candidate : annotations) {
                if (ACCESSOR.equals(candidate.desc)) accessor = candidate;
            }
        }
        assertNotNull(accessor, mixinResource + " must declare a method @Accessor");
        List<String> fields = annotationValues(accessor, "value");
        assertEquals(1, fields.size(), "an accessor mixin here must name exactly one field");
        String field = fields.get(0);
        assertTrue(target.fields.stream().anyMatch(candidate -> candidate.name.equals(field)),
            mixinResource + " accesses missing field " + target.name + "." + field);
    }

    @Test
    void healthAndAbsorptionAccessorsTargetRealPrivateFields() throws Exception {
        assertAccessorTargetsRealField("/dev/tntcoating/mixin/CombatLivingEntityDataAccessor.class",
            "/net/minecraft/world/entity/LivingEntity.class");
        assertAccessorTargetsRealField("/dev/tntcoating/mixin/CombatPlayerDataAccessor.class",
            "/net/minecraft/world/entity/player/Player.class");
    }

    @Test
    void mixinConfigRegistersCombatAccessors() throws Exception {
        String config;
        try (var stream = CombatMixinTargetTest.class.getResourceAsStream("/comet.mixins.json")) {
            assertNotNull(stream, "comet.mixins.json must be on the test classpath");
            config = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(config.contains("CombatLivingEntityDataAccessor"), "mixin config must register the health accessor");
        assertTrue(config.contains("CombatPlayerDataAccessor"), "mixin config must register the absorption accessor");
    }
}
