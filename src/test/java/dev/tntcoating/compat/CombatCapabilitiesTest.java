package dev.tntcoating.compat;

import com.google.gson.JsonParser;
import dev.tntcoating.core.combat.CrystalCombatGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capability check must be at least as strict as the mixins themselves: a configured, applied-looking
 * mixin is not proof. These tests fake the class bytes and the config, so they need no client.
 */
class CombatCapabilitiesTest {
    private static final String CRYSTAL_AURA = "meteordevelopment/meteorclient/systems/modules/combat/CrystalAura";
    private static final String ROTATIONS = "meteordevelopment/meteorclient/utils/player/Rotations";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String CRYSTAL_AURA_MIXIN = "dev/tntcoating/mixin/CrystalAuraMixin";
    private static final String CONNECTION_MIXIN = "dev/tntcoating/mixin/CombatConnectionHooksMixin";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    @AfterEach
    void resetGate() {
        CrystalCombatGate.deactivate();
        CrystalCombatGate.setProtector(null);
    }

    @Test
    void thePinnedBuildSatisfiesEveryCriticalCapability() throws Exception {
        var status = CombatCapabilities.inspect(CombatCapabilitiesTest::read, registeredClientMixins(), Map.of());

        assertEquals(List.of(), status.problems(), status.summary());
        assertTrue(status.ready());
        assertEquals("CrystalCombat capabilities verified", status.summary());
    }

    @Test
    void aRemovedTargetMethodFailsTheCapabilityCheck() throws Exception {
        ClassNode aura = read(CRYSTAL_AURA);
        assertTrue(aura.methods.removeIf(method -> method.name.equals("attackCrystal")));

        var status = CombatCapabilities.inspect(replacing(CRYSTAL_AURA, aura), registeredClientMixins(), Map.of());

        assertFalse(status.ready());
        assertTrue(messages(status).stream().anyMatch(problem -> problem.contains("CrystalAuraMixin")
            && problem.contains("attackCrystal")), status.summary());
    }

    @Test
    void aRemovedTargetFieldFailsTheCapabilityCheck() throws Exception {
        ClassNode living = read(LIVING_ENTITY);
        assertTrue(living.fields.removeIf(field -> field.name.equals("DATA_HEALTH_ID")));

        var status = CombatCapabilities.inspect(replacing(LIVING_ENTITY, living), registeredClientMixins(), Map.of());

        assertFalse(status.ready());
        assertTrue(messages(status).stream().anyMatch(problem -> problem.contains("CombatLivingEntityDataAccessor")
            && problem.contains("DATA_HEALTH_ID")), status.summary());
    }

    @Test
    void aRemovedInjectionPointFailsTheCapabilityCheck() throws Exception {
        ClassNode aura = read(CRYSTAL_AURA);
        MethodNode doBreak = findMethod(aura, "doBreak", "(Lnet/minecraft/world/entity/Entity;)V");
        assertNotNull(doBreak);
        int renamed = 0;
        for (AbstractInsnNode instruction : doBreak.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(ROTATIONS) && call.name.equals("rotate")) {
                call.name = "rotateNow"; // As if Meteor stopped routing the delayed break through Rotations.
                renamed++;
            }
        }
        assertEquals(1, renamed);

        var status = CombatCapabilities.inspect(replacing(CRYSTAL_AURA, aura), registeredClientMixins(), Map.of());

        assertFalse(status.ready());
        assertTrue(messages(status).stream().anyMatch(problem -> problem.contains("Rotations")
            && problem.contains("injection point")), status.summary());
    }

    @Test
    void aRemovedDelayedPlaceRotationFailsTheCapabilityCheck() throws Exception {
        // doPlace() hands its delayed rotation to BlockIterator.after through this synthetic method.
        ClassNode aura = read(CRYSTAL_AURA);
        MethodNode delayedPlace = findMethod(aura, "lambda$doPlace$1",
            "(Lcom/google/common/util/concurrent/AtomicDouble;Ljava/util/concurrent/atomic/AtomicReference;"
                + "Ljava/util/concurrent/atomic/AtomicBoolean;)V");
        assertNotNull(delayedPlace, "pinned CrystalAura must still queue doPlace's rotation in lambda$doPlace$1");
        int renamed = 0;
        for (AbstractInsnNode instruction : delayedPlace.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(ROTATIONS) && call.name.equals("rotate")) {
                call.name = "rotateNow";
                renamed++;
            }
        }
        assertEquals(1, renamed);

        var status = CombatCapabilities.inspect(replacing(CRYSTAL_AURA, aura), registeredClientMixins(), Map.of());

        assertFalse(status.ready());
        assertTrue(messages(status).stream().anyMatch(problem -> problem.contains("CrystalAuraMixin")
            && problem.contains("guardPlaceRotation")), status.summary());
    }

    @Test
    void aMissingRegisteredMixinFailsTheCapabilityCheck() throws Exception {
        Set<String> registered = new LinkedHashSet<>(registeredClientMixins());
        assertTrue(registered.remove("CrystalAuraMixin"));

        var status = CombatCapabilities.inspect(CombatCapabilitiesTest::read, registered, Map.of());

        assertFalse(status.ready());
        assertTrue(messages(status).stream().anyMatch(problem -> problem.contains("CrystalAuraMixin")
            && problem.contains("not registered")), status.summary());
    }

    @Test
    void aMissingMixinClassFailsTheCapabilityCheck() throws Exception {
        MeteorUpgradeCheck.ClassNodes classes = name ->
            name.equals(CONNECTION_MIXIN) ? null : read(name);

        var status = CombatCapabilities.inspect(classes, registeredClientMixins(), Map.of());

        assertFalse(status.ready());
        assertTrue(messages(status).stream().anyMatch(problem -> problem.contains("CombatConnectionHooksMixin")
            && problem.contains("missing")), status.summary());
    }

    @Test
    void aDisabledMixinFailsEvenThoughAppliedDefaultsTrue() throws Exception {
        // MeteorUpgrades.applied() is default-true; a skipped mixin must not look applied to this check.
        assertTrue(MeteorUpgrades.applied("CrystalAuraMixin"));

        var status = CombatCapabilities.inspect(CombatCapabilitiesTest::read, registeredClientMixins(),
            Map.of("CrystalAuraMixin", "this build moved doBreak"));

        assertFalse(status.ready());
        assertTrue(messages(status).stream().anyMatch(problem -> problem.contains("CrystalAuraMixin")
            && problem.contains("skipped for this build")
            && problem.contains("this build moved doBreak")), status.summary());
    }

    @Test
    void aRemovedGuardInjectionFailsTheCapabilityCheck() throws Exception {
        ClassNode mixin = read(CRYSTAL_AURA_MIXIN);
        assertTrue(mixin.methods.removeIf(method -> hasInject(method, "doPlace()V")),
            "the compiled mixin must carry the audited doPlace guard");

        var status = CombatCapabilities.inspect(replacing(CRYSTAL_AURA_MIXIN, mixin), registeredClientMixins(), Map.of());

        assertFalse(status.ready());
        assertTrue(messages(status).stream().anyMatch(problem -> problem.contains("CrystalAuraMixin")
            && problem.contains("doPlace()V")), status.summary());
    }

    @Test
    void aMixinThatNoLongerTargetsCrystalAuraFailsTheCapabilityCheck() throws Exception {
        ClassNode mixin = read(CRYSTAL_AURA_MIXIN);
        removeMixinAnnotation(mixin);

        var status = CombatCapabilities.inspect(replacing(CRYSTAL_AURA_MIXIN, mixin), registeredClientMixins(), Map.of());

        assertFalse(status.ready());
        assertTrue(messages(status).stream().anyMatch(problem -> problem.contains("CrystalAuraMixin")
            && problem.contains("must target")), status.summary());
    }

    @Test
    void everyProblemNamesItsCapabilityAndTheSummaryCarriesTheDetails() throws Exception {
        Set<String> registered = new LinkedHashSet<>(registeredClientMixins());
        assertTrue(registered.remove("CrystalAuraMixin"));
        MeteorUpgradeCheck.ClassNodes classes = name -> name.equals(CONNECTION_MIXIN) ? null : read(name);

        var status = CombatCapabilities.inspect(classes, registered, Map.of());

        assertFalse(status.ready());
        assertEquals(2, status.problems().size(), status.summary());
        for (var problem : status.problems()) {
            assertFalse(problem.capability().isBlank());
            assertFalse(problem.detail().isBlank());
            assertTrue(status.summary().contains(problem.message()), status.summary());
        }
    }

    @Test
    void aRefusedCapabilityCheckLeavesCrystalCombatInactiveAndMeteorWorking() throws Exception {
        Set<String> registered = new LinkedHashSet<>(registeredClientMixins());
        assertTrue(registered.remove("CrystalAuraMixin"));
        var status = CombatCapabilities.inspect(CombatCapabilitiesTest::read, registered, Map.of());
        assertFalse(status.ready());

        // Phase 2d activation protocol: the gate may only be activated after a ready status.
        if (status.ready()) CrystalCombatGate.activate();
        assertFalse(CrystalCombatGate.suppressMeteor(), "a refused capability check must not suppress Meteor");

        AtomicInteger sends = new AtomicInteger();
        CrystalCombatGate.guard(sends::incrementAndGet).run();
        assertEquals(1, sends.get(), "Meteor's own paths keep working after a refusal");

        UUID protectedCrystal = UUID.randomUUID();
        CrystalCombatGate.setProtector(crystal -> crystal.equals(protectedCrystal));
        assertTrue(CrystalCombatGate.protects(protectedCrystal),
            "ledger protection is independent of the refused activation");
        assertFalse(CrystalCombatGate.suppressMeteor());
    }

    private static void removeMixinAnnotation(ClassNode mixin) {
        if (mixin.visibleAnnotations != null) mixin.visibleAnnotations.removeIf(annotation -> MIXIN.equals(annotation.desc));
        if (mixin.invisibleAnnotations != null) mixin.invisibleAnnotations.removeIf(annotation -> MIXIN.equals(annotation.desc));
    }

    private static boolean hasInject(MethodNode method, String selector) {
        for (AnnotationNode annotation : annotations(method)) {
            if (!INJECT.equals(annotation.desc)) continue;
            for (Object value : list(value(annotation, "method"))) {
                if (selector.equals(value)) return true;
            }
        }
        return false;
    }

    private static List<String> messages(CombatCapabilities.Status status) {
        return status.problems().stream().map(CombatCapabilities.Problem::message).toList();
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        return node.methods.stream()
            .filter(method -> method.name.equals(name) && method.desc.equals(desc))
            .findFirst().orElse(null);
    }

    private static Set<String> registeredClientMixins() throws Exception {
        try (InputStream stream = CombatCapabilitiesTest.class.getResourceAsStream("/comet.mixins.json")) {
            assertNotNull(stream, "comet.mixins.json must be on the test classpath");
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> names = new LinkedHashSet<>();
            config.getAsJsonArray("client").forEach(element -> names.add(element.getAsString()));
            return names;
        }
    }

    private static MeteorUpgradeCheck.ClassNodes replacing(String internalName, ClassNode replacement) {
        return name -> name.replace('.', '/').equals(internalName) ? replacement : read(name);
    }

    private static ClassNode read(String name) throws Exception {
        try (InputStream stream = CombatCapabilitiesTest.class.getClassLoader()
            .getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static List<Object> list(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> values) return new ArrayList<>(values);
        return List.of(value);
    }
}
