/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.1.2 artifact and Meteor build 42 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.service.MixinService;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies, from bytecode and the mixin config, that every capability CrystalCombat cannot run without is
 * actually present on this build. Phase 2c does not activate anything here; Phase 2d calls {@link #inspect()}
 * before enabling the module and refuses activation with {@link Status#summary()} when it is not ready.
 *
 * <p>The check deliberately does not trust {@link MeteorUpgrades#applied(String)}: that method defaults to
 * true, so a mixin that was never registered, or a class that is simply absent, would look applied. Instead a
 * capability is present only when the mixin is registered as a client mixin in {@code comet.mixins.json}, is
 * not listed in {@link MeteorUpgrades#disabled()}, its class is on the classpath, its target class is the one
 * it must modify, every expected guard injection (by annotation, method descriptor, injection point and, for
 * redirects, redirect target) is declared, its {@code @Accessor} names the expected field, and
 * {@link MeteorUpgradeCheck#problems(ClassNode, MeteorUpgradeCheck.ClassNodes)} reports nothing.</p>
 *
 * <p>When a critical capability is missing nothing suppresses Meteor: {@code CrystalAuraMixin}'s guards only
 * cancel while {@link dev.tntcoating.core.combat.CrystalCombatGate#suppressMeteor()} is true, and the gate is
 * activated only after a ready {@link Status}. Meteor's CrystalAura therefore keeps working normally after a
 * refusal.</p>
 */
public final class CombatCapabilities {
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String MIXIN_PACKAGE = "dev/tntcoating/mixin/";
    private static final String CONFIG = "/comet.mixins.json";

    private static final String ROTATE =
        "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DDILjava/lang/Runnable;)V";
    private static final String CHANNEL_READ =
        "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V";

    /** What CrystalCombat cannot run without (audit §3.8.3, §3.10.6, §3.10.7). */
    private static final List<MixinSpec> CRITICAL = List.of(
        new MixinSpec("CrystalAuraMixin",
            "meteordevelopment/meteorclient/systems/modules/combat/CrystalAura",
            List.of(
                inject("onPreTick(Lmeteordevelopment/meteorclient/events/world/TickEvent$Pre;)V", "HEAD"),
                inject("onPreTickLast(Lmeteordevelopment/meteorclient/events/world/TickEvent$Pre;)V", "HEAD"),
                inject("doBreak()V", "HEAD"),
                inject("doBreak(Lnet/minecraft/world/entity/Entity;)V", "HEAD"),
                inject("attackCrystal(Lnet/minecraft/world/entity/Entity;)V", "HEAD"),
                inject("doPlace()V", "HEAD"),
                inject("placeCrystal(Lnet/minecraft/world/phys/BlockHitResult;DLnet/minecraft/core/BlockPos;)V", "HEAD"),
                inject("doYawSteps(DD)Z", "HEAD"),
                redirect("doBreak(Lnet/minecraft/world/entity/Entity;)V"),
                redirect("lambda$doPlace$1(Lcom/google/common/util/concurrent/AtomicDouble;"
                    + "Ljava/util/concurrent/atomic/AtomicReference;Ljava/util/concurrent/atomic/AtomicBoolean;)V")),
            null),
        new MixinSpec("CombatPacketHooksMixin",
            "net/minecraft/client/multiplayer/ClientPacketListener",
            List.of(
                inject("handleSetHealth(Lnet/minecraft/network/protocol/game/ClientboundSetHealthPacket;)V", "RETURN"),
                inject("handleSetEntityData(Lnet/minecraft/network/protocol/game/ClientboundSetEntityDataPacket;)V", "RETURN"),
                inject("handleUpdateMobEffect(Lnet/minecraft/network/protocol/game/ClientboundUpdateMobEffectPacket;)V", "RETURN"),
                inject("handleRemoveMobEffect(Lnet/minecraft/network/protocol/game/ClientboundRemoveMobEffectPacket;)V", "RETURN"),
                inject("handleSetEquipment(Lnet/minecraft/network/protocol/game/ClientboundSetEquipmentPacket;)V", "RETURN"),
                inject("handleDamageEvent(Lnet/minecraft/network/protocol/game/ClientboundDamageEventPacket;)V", "RETURN"),
                inject("handleExplosion(Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;)V", "RETURN"),
                inject("handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V", "RETURN"),
                inject("handleRespawn(Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;)V", "RETURN"),
                inject("handleBlockChangedAck(Lnet/minecraft/network/protocol/game/ClientboundBlockChangedAckPacket;)V", "RETURN"),
                inject("handleBlockEvent(Lnet/minecraft/network/protocol/game/ClientboundBlockEventPacket;)V", "RETURN"),
                inject("handleEntityEvent(Lnet/minecraft/network/protocol/game/ClientboundEntityEventPacket;)V", "RETURN"),
                inject("handleMoveEntity(Lnet/minecraft/network/protocol/game/ClientboundMoveEntityPacket;)V", "RETURN"),
                inject("handleTeleportEntity(Lnet/minecraft/network/protocol/game/ClientboundTeleportEntityPacket;)V", "RETURN"),
                inject("handleEntityPositionSync(Lnet/minecraft/network/protocol/game/ClientboundEntityPositionSyncPacket;)V", "RETURN")),
            null),
        new MixinSpec("CombatConnectionHooksMixin",
            "net/minecraft/network/Connection",
            List.of(inject(CHANNEL_READ, "HEAD"), inject(CHANNEL_READ, "RETURN")),
            null),
        new MixinSpec("CombatLivingEntityDataAccessor",
            "net/minecraft/world/entity/LivingEntity",
            List.of(),
            "DATA_HEALTH_ID"),
        new MixinSpec("CombatPlayerDataAccessor",
            "net/minecraft/world/entity/player/Player",
            List.of(),
            "DATA_PLAYER_ABSORPTION_ID"));

    private CombatCapabilities() {}

    /** One reason CrystalCombat cannot start, attributed to the capability it weakens. */
    public record Problem(String capability, String detail) {
        public String message() { return capability + ": " + detail; }
    }

    /** Typed result of a capability check; {@link #ready()} is true only when no problem was found. */
    public record Status(List<Problem> problems) {
        public Status {
            problems = List.copyOf(problems);
        }

        public boolean ready() {
            return problems.isEmpty();
        }

        /** Visible reason list for a refusal message or a module info string. */
        public String summary() {
            if (problems.isEmpty()) return "CrystalCombat capabilities verified";
            StringBuilder text = new StringBuilder("CrystalCombat unavailable");
            for (Problem problem : problems) text.append("; ").append(problem.message());
            return text.toString();
        }
    }

    /**
     * Checks the running client: the mixin config, the mixins actually skipped by the plugin, and class bytes
     * read through Mixin's own bytecode provider (the same source {@link CometMixinPlugin} used).
     */
    public static Status inspect() {
        List<Problem> problems = new ArrayList<>();
        Set<String> registered = registeredClientMixins(problems);
        MeteorUpgradeCheck.ClassNodes classes = runtimeClasses(problems);
        if (classes != null) problems.addAll(check(classes, registered, MeteorUpgrades.disabled()));
        return new Status(problems);
    }

    /** Testable form: class bytes, the configured client mixin names and the skipped mixins and reasons. */
    public static Status inspect(MeteorUpgradeCheck.ClassNodes classes,
                                 Set<String> registeredClientMixins,
                                 Map<String, String> disabledMixins) {
        return new Status(check(classes, registeredClientMixins, disabledMixins));
    }

    private static List<Problem> check(MeteorUpgradeCheck.ClassNodes classes,
                                       Set<String> registered,
                                       Map<String, String> disabled) {
        List<Problem> problems = new ArrayList<>();
        for (MixinSpec spec : CRITICAL) {
            String name = spec.name();
            boolean blocked = false;
            if (!registered.contains(name)) {
                problems.add(new Problem(name, "not registered in comet.mixins.json"));
                blocked = true;
            }
            if (disabled.containsKey(name)) {
                problems.add(new Problem(name, "skipped for this build: " + disabled.get(name)));
                blocked = true;
            }
            ClassNode mixin = null;
            try {
                mixin = classes.get(MIXIN_PACKAGE + name);
            } catch (Exception error) {
                // Reported below as a missing class.
            }
            if (mixin == null) {
                problems.add(new Problem(name, "mixin class " + MIXIN_PACKAGE + name + " is missing"));
                continue;
            }
            if (blocked) continue; // The reason is already visible; do not bury it under bytecode noise.
            List<String> targets = MeteorUpgradeCheck.targets(mixin);
            if (!targets.contains(spec.target())) {
                problems.add(new Problem(name, "must target " + spec.target().replace('/', '.')
                    + " but targets " + targets));
            }
            for (String problem : MeteorUpgradeCheck.problems(mixin, classes)) problems.add(new Problem(name, problem));
            for (Hook hook : spec.hooks()) {
                if (!declares(mixin, hook)) problems.add(new Problem(name, "missing " + describe(hook)));
            }
            if (spec.accessor() != null && !declaresAccessor(mixin, spec.accessor())) {
                problems.add(new Problem(name, "missing @Accessor(\"" + spec.accessor() + "\")"));
            }
        }
        return problems;
    }

    /** True when the mixin method carries the expected annotation, selector, injection point and target. */
    private static boolean declares(ClassNode mixin, Hook hook) {
        for (MethodNode method : mixin.methods) {
            for (AnnotationNode annotation : all(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (!hook.annotation().equals(annotation.desc)) continue;
                if (!selectors(annotation).contains(hook.selector())) continue;
                for (Object point : list(value(annotation, "at"))) {
                    if (!(point instanceof AnnotationNode at)) continue;
                    String kind = value(at, "value") instanceof String text ? text : "HEAD";
                    if (!hook.at().equals(kind)) continue;
                    if (hook.target() == null || hook.target().equals(value(at, "target"))) return true;
                }
            }
        }
        return false;
    }

    private static boolean declaresAccessor(ClassNode mixin, String field) {
        for (MethodNode method : mixin.methods) {
            for (AnnotationNode annotation : all(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (ACCESSOR.equals(annotation.desc) && field.equals(value(annotation, "value"))) return true;
            }
        }
        return false;
    }

    private static List<String> selectors(AnnotationNode annotation) {
        List<String> selectors = new ArrayList<>();
        for (Object selector : list(value(annotation, "method"))) {
            if (selector instanceof String text) selectors.add(text);
        }
        return selectors;
    }

    private static Set<String> registeredClientMixins(List<Problem> problems) {
        try (InputStream stream = CombatCapabilities.class.getResourceAsStream(CONFIG)) {
            if (stream == null) {
                problems.add(new Problem("comet.mixins.json", "is not on the classpath"));
                return Set.of();
            }
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Set<String> names = new LinkedHashSet<>();
            JsonArray client = config.getAsJsonArray("client");
            if (client != null) for (var element : client) names.add(element.getAsString());
            return names;
        } catch (Exception error) {
            problems.add(new Problem("comet.mixins.json", "cannot be read: " + error));
            return Set.of();
        }
    }

    private static MeteorUpgradeCheck.ClassNodes runtimeClasses(List<Problem> problems) {
        try {
            var provider = MixinService.getService().getBytecodeProvider();
            return name -> provider.getClassNode(name.replace('/', '.'));
        } catch (RuntimeException error) {
            problems.add(new Problem("mixin service", "cannot read class bytecode: " + error));
            return null;
        }
    }

    private static Hook inject(String selector, String at) {
        return new Hook(INJECT, selector, at, null);
    }

    private static Hook redirect(String selector) {
        return new Hook(REDIRECT, selector, "INVOKE", ROTATE);
    }

    private static String describe(Hook hook) {
        String kind = INJECT.equals(hook.annotation()) ? "@Inject" : "@Redirect";
        StringBuilder text = new StringBuilder(kind).append('(').append(hook.at());
        if (hook.target() != null) text.append(' ').append(hook.target());
        return text.append(") ").append(hook.selector()).toString();
    }

    private static List<AnnotationNode> all(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return null;
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

    private record Hook(String annotation, String selector, String at, String target) {}

    private record MixinSpec(String name, String target, List<Hook> hooks, String accessor) {}
}
