package dev.tntcoating;

import dev.tntcoating.core.combat.CrystalCombatGate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bytecode proof that the CrystalAura upgrade is wired exactly at the paths the audit lists (audit §3.8.3,
 * §3.9.8) and that it can only suppress through {@link CrystalCombatGate}. No client is launched: the compiled
 * mixin class and the pinned Meteor class are read as resources.
 */
class CrystalAuraSuppressionMixinTest {
    private static final String MIXIN_RESOURCE = "/dev/tntcoating/mixin/CrystalAuraMixin.class";
    private static final String TARGET_RESOURCE = "/meteordevelopment/meteorclient/systems/modules/combat/CrystalAura.class";

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String GATE = "dev/tntcoating/core/combat/CrystalCombatGate";
    private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CALLBACK_RETURNABLE = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String ROTATIONS = "meteordevelopment/meteorclient/utils/player/Rotations";
    private static final String ROTATE_DESC = "(DDILjava/lang/Runnable;)V";
    private static final String ROTATE_TARGET = "L" + ROTATIONS + ";rotate" + ROTATE_DESC;

    private static final String TICK_PRE = "onPreTick(Lmeteordevelopment/meteorclient/events/world/TickEvent$Pre;)V";
    private static final String TICK_PRE_LAST = "onPreTickLast(Lmeteordevelopment/meteorclient/events/world/TickEvent$Pre;)V";
    private static final String ENTITY_ADDED =
        "onEntityAdded(Lmeteordevelopment/meteorclient/events/entity/EntityAddedEvent;)V";
    private static final String BREAK_ENTITY = "doBreak(Lnet/minecraft/world/entity/Entity;)V";
    private static final String ATTACK_ENTITY = "attackCrystal(Lnet/minecraft/world/entity/Entity;)V";
    private static final String PLACE_CRYSTAL =
        "placeCrystal(Lnet/minecraft/world/phys/BlockHitResult;DLnet/minecraft/core/BlockPos;)V";
    /** {@code doPlace()} hands its delayed rotation to {@code BlockIterator.after} through this synthetic method. */
    private static final String DO_PLACE_LAMBDA =
        "lambda$doPlace$19(Lcom/google/common/util/concurrent/AtomicDouble;"
            + "Ljava/util/concurrent/atomic/AtomicReference;Ljava/util/concurrent/atomic/AtomicBoolean;)V";

    /**
     * The exact audited send paths that must head-cancel when CrystalCombat is active. {@code onEntityAdded}
     * is deliberately absent: it also performs Meteor's placing/placedCrystals bookkeeping, and its fast-break
     * send is stopped by the {@code doBreak(Entity)} guard.
     */
    private static final List<String> HEAD_GUARDED = List.of(
        TICK_PRE, TICK_PRE_LAST,
        "doBreak()V", BREAK_ENTITY, ATTACK_ENTITY,
        "doPlace()V", PLACE_CRYSTAL, "doYawSteps(DD)Z");

    /** The delayed rotation callbacks that later attack or place. */
    private static final List<String> REDIRECTED = List.of(BREAK_ENTITY, DO_PLACE_LAMBDA);

    @Test
    void everyAuditedSendPathCarriesTheSuppressionGuard() throws Exception {
        ClassNode mixin = node(MIXIN_RESOURCE);
        for (String selector : HEAD_GUARDED) {
            MethodNode handler = handler(mixin, INJECT, selector, "HEAD", null);
            assertNotNull(handler, "no HEAD @Inject for audited send path " + selector);
            assertTrue(hasCall(handler, GATE, "suppressMeteor", "()Z"),
                "the guard for " + selector + " must consult CrystalCombatGate.suppressMeteor()");
        }
        assertEquals(new TreeSet<>(HEAD_GUARDED), new TreeSet<>(declaredSelectors(mixin, INJECT)),
            "the mixin must inject exactly the audited send paths");
    }

    @Test
    void theDelayedRotationCallbacksAreRedirectedThroughTheGuard() throws Exception {
        ClassNode mixin = node(MIXIN_RESOURCE);
        for (String selector : REDIRECTED) {
            MethodNode handler = handler(mixin, REDIRECT, selector, "INVOKE", ROTATE_TARGET);
            assertNotNull(handler, "no @Redirect of Rotations.rotate in " + selector);
            assertTrue(hasCall(handler, GATE, "guard", "(Ljava/lang/Runnable;)Ljava/lang/Runnable;"),
                "the redirect in " + selector + " must wrap the callback in CrystalCombatGate.guard()");
            assertTrue(hasCall(handler, GATE, "suppressMeteor", "()Z"),
                "the redirect in " + selector + " must skip the rotation entirely while suppressed");
            assertTrue(hasCall(handler, ROTATIONS, "rotate", ROTATE_DESC),
                "the redirect in " + selector + " must forward to Rotations.rotate");
        }
        assertEquals(new TreeSet<>(REDIRECTED), new TreeSet<>(declaredSelectors(mixin, REDIRECT)),
            "only the two delayed send callbacks need a redirect; the head guards cover the rest");
    }

    @Test
    void theLedgerProtectionGuardCoversBothAttackPaths() throws Exception {
        ClassNode mixin = node(MIXIN_RESOURCE);
        for (String selector : List.of(BREAK_ENTITY, ATTACK_ENTITY)) {
            MethodNode handler = handler(mixin, INJECT, selector, "HEAD", null);
            assertNotNull(handler);
            assertTrue(hasCall(handler, GATE, "protects", "(Ljava/util/UUID;)Z"),
                selector + " must refuse a ledger-protected crystal");
            assertTrue(hasCall(handler, ENTITY, "getUUID", "()Ljava/util/UUID;"),
                selector + " must read the crystal UUID for the protector");
        }
        assertEquals(Set.of(BREAK_ENTITY, ATTACK_ENTITY), declaringSelectors(mixin, INJECT, GATE, "protects"),
            "the protector check belongs only on the two attack paths");
    }

    @Test
    void theSpawnBookkeepingPathIsNotHeadCancelled() throws Exception {
        ClassNode mixin = node(MIXIN_RESOURCE);
        assertNull(handler(mixin, INJECT, ENTITY_ADDED, "HEAD", null),
            "onEntityAdded also updates Meteor bookkeeping and must be stopped at doBreak(Entity), not here");
    }

    @Test
    void theNullWorldGuardsArePreservedOnBothTickHooks() throws Exception {
        ClassNode mixin = node(MIXIN_RESOURCE);
        for (String selector : List.of(TICK_PRE, TICK_PRE_LAST)) {
            MethodNode handler = handler(mixin, INJECT, selector, "HEAD", null);
            assertNotNull(handler);
            assertTrue(hasCall(handler, MINECRAFT, "getInstance", "()Lnet/minecraft/client/Minecraft;"),
                selector + " must keep the null-world guard");
            assertTrue(hasField(handler, MINECRAFT, "player"), selector + " must check mc.player");
            assertTrue(hasField(handler, MINECRAFT, "level"), selector + " must check mc.level");
            assertTrue(hasCall(handler, CALLBACK_INFO, "cancel", "()V"), selector + " must still cancel");
        }
    }

    @Test
    void noCancellationRunsWithoutTheGate() throws Exception {
        ClassNode mixin = node(MIXIN_RESOURCE);
        int cancellations = 0;
        for (MethodNode method : mixin.methods) {
            boolean cancels = hasCall(method, CALLBACK_INFO, "cancel", "()V");
            boolean returns = hasCall(method, CALLBACK_RETURNABLE, "setReturnValue", "(Ljava/lang/Object;)V");
            if (!cancels && !returns) continue;
            cancellations++;
            assertTrue(hasCall(method, GATE, "suppressMeteor", "()Z"),
                "unconditional cancellation in " + method.name + "; every cancel must be gate-checked");
        }
        assertTrue(cancellations >= 8, "expected a cancellation at every audited path, found " + cancellations);
    }

    @Test
    void theMixinNeverChangesCrystalCombatOrMeteorState() throws Exception {
        ClassNode mixin = node(MIXIN_RESOURCE);
        for (MethodNode method : mixin.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals(GATE)) {
                    assertFalse(Set.of("activate", "deactivate", "setProtector").contains(call.name),
                        "the mixin must not change CrystalCombat state from " + method.name);
                }
                assertFalse(call.owner.startsWith("meteordevelopment/meteorclient/settings/"),
                    "the mixin must not touch Meteor settings from " + method.name);
                assertFalse(call.owner.startsWith("meteordevelopment/meteorclient/systems/modules/"),
                    "the mixin must not call Meteor module code from " + method.name);
            }
        }
    }

    @Test
    void theMixinTargetsCrystalAuraWithoutRemapping() throws Exception {
        ClassNode mixin = node(MIXIN_RESOURCE);
        AnnotationNode annotation = annotation(mixin, MIXIN);
        assertNotNull(annotation, "CrystalAuraMixin must stay an @Mixin");
        List<String> targets = new ArrayList<>();
        for (Object value : list(value(annotation, "value"))) {
            if (value instanceof Type type) targets.add(type.getInternalName());
        }
        assertEquals(List.of("meteordevelopment/meteorclient/systems/modules/combat/CrystalAura"), targets);
        assertEquals(Boolean.FALSE, value(annotation, "remap"), "Meteor targets stay in their own namespace");
    }

    @Test
    void observationOnlyPacketPathsAreNotInjected() throws Exception {
        ClassNode mixin = node(MIXIN_RESOURCE);
        for (MethodNode method : mixin.methods) {
            for (AnnotationNode annotation : annotations(method)) {
                if (!INJECT.equals(annotation.desc) && !REDIRECT.equals(annotation.desc)) continue;
                for (String selector : selectorValues(annotation)) {
                    assertFalse(selector.startsWith("onPacketSend"),
                        "onPacketSend is observation only and must not be injected");
                    assertFalse(selector.startsWith("onPacketSent"),
                        "onPacketSent is observation only and must not be injected");
                }
            }
        }
    }

    @Test
    void theAuditedSelectorsExistInThePinnedCrystalAura() throws Exception {
        ClassNode target = node(TARGET_RESOURCE);
        List<String> selectors = new ArrayList<>(HEAD_GUARDED);
        selectors.addAll(REDIRECTED);
        for (String selector : selectors) {
            int parenthesis = selector.indexOf('(');
            assertTrue(parenthesis > 0, selector);
            String name = selector.substring(0, parenthesis);
            String descriptor = selector.substring(parenthesis);
            assertTrue(target.methods.stream()
                    .anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor)),
                "pinned CrystalAura no longer has " + selector);
        }
    }

    @Test
    void noGuardSuppressesWhileCrystalCombatIsInactive() throws Exception {
        try {
            assertFalse(CrystalCombatGate.suppressMeteor(), "the gate starts inactive");
            // Every cancellation in the mixin is conditioned on suppressMeteor(), which is false here, so none
            // of the audited paths can cancel while CrystalCombat is inactive. The delayed path also runs.
            ClassNode mixin = node(MIXIN_RESOURCE);
            for (MethodNode method : mixin.methods) {
                boolean cancels = hasCall(method, CALLBACK_INFO, "cancel", "()V");
                boolean returns = hasCall(method, CALLBACK_RETURNABLE, "setReturnValue", "(Ljava/lang/Object;)V");
                if (!cancels && !returns) continue;
                assertTrue(hasCall(method, GATE, "suppressMeteor", "()Z"),
                    method.name + " would cancel even while CrystalCombat is inactive");
            }
            AtomicInteger runs = new AtomicInteger();
            CrystalCombatGate.guard(runs::incrementAndGet).run();
            assertEquals(1, runs.get(), "inactive-era Meteor callbacks must still run");
        } finally {
            CrystalCombatGate.deactivate();
        }
    }

    @Test
    void crystalCombatInactiveLeavesMeteorUnsuppressedAndProtectionIndependent() {
        try {
            assertFalse(CrystalCombatGate.suppressMeteor());
            AtomicInteger sends = new AtomicInteger();
            Runnable guarded = CrystalCombatGate.guard(sends::incrementAndGet);
            guarded.run();
            assertEquals(1, sends.get(), "a gate that was never activated must not suppress Meteor");

            UUID protectedCrystal = UUID.randomUUID();
            CrystalCombatGate.setProtector(crystal -> crystal.equals(protectedCrystal));
            assertFalse(CrystalCombatGate.suppressMeteor());
            assertTrue(CrystalCombatGate.protects(protectedCrystal),
                "the protector stays active for Meteor while CrystalCombat is disabled");
            assertFalse(CrystalCombatGate.protects(UUID.randomUUID()));
        } finally {
            CrystalCombatGate.deactivate();
            CrystalCombatGate.setProtector(null);
        }
    }

    @Test
    void nullCallbacksFromRedirectsStayNull() {
        assertNull(CrystalCombatGate.guard(null));
    }

    private static java.util.Set<String> declaringSelectors(ClassNode mixin, String annotationDesc,
                                                            String owner, String method) {
        java.util.Set<String> selectors = new TreeSet<>();
        for (MethodNode candidate : mixin.methods) {
            if (!hasCall(candidate, owner, method, null)) continue;
            for (AnnotationNode annotation : annotations(candidate)) {
                if (annotationDesc.equals(annotation.desc)) selectors.addAll(selectorValues(annotation));
            }
        }
        return selectors;
    }

    private static List<String> declaredSelectors(ClassNode mixin, String annotationDesc) {
        List<String> selectors = new ArrayList<>();
        for (MethodNode method : mixin.methods) {
            for (AnnotationNode annotation : annotations(method)) {
                if (annotationDesc.equals(annotation.desc)) selectors.addAll(selectorValues(annotation));
            }
        }
        return selectors;
    }

    private static List<String> selectorValues(AnnotationNode annotation) {
        List<String> selectors = new ArrayList<>();
        for (Object value : list(value(annotation, "method"))) {
            if (value instanceof String text) selectors.add(text);
        }
        return selectors;
    }

    private static MethodNode handler(ClassNode mixin, String annotationDesc, String selector,
                                      String atKind, String atTarget) {
        for (MethodNode method : mixin.methods) {
            for (AnnotationNode annotation : annotations(method)) {
                if (!annotationDesc.equals(annotation.desc)) continue;
                if (!selectorValues(annotation).contains(selector)) continue;
                for (Object point : list(value(annotation, "at"))) {
                    if (!(point instanceof AnnotationNode at)) continue;
                    String kind = value(at, "value") instanceof String text ? text : "HEAD";
                    String target = value(at, "target") instanceof String text ? text : null;
                    if (!atKind.equals(kind)) continue;
                    if (atTarget != null && !atTarget.equals(target)) continue;
                    return method;
                }
            }
        }
        return null;
    }

    private static boolean hasCall(MethodNode method, String owner, String name, String descriptor) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)
                && (descriptor == null || call.desc.equals(descriptor))) return true;
        }
        return false;
    }

    private static boolean hasField(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals(owner) && field.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations;
    }

    private static AnnotationNode annotation(ClassNode node, String descriptor) {
        for (AnnotationNode annotation : annotations(node)) {
            if (descriptor.equals(annotation.desc)) return annotation;
        }
        return null;
    }

    private static List<AnnotationNode> annotations(ClassNode node) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
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

    private static ClassNode node(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (var stream = CrystalAuraSuppressionMixinTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource + " must be on the test classpath");
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }
}
