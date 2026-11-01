/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Guards the Phase 2d exit gate without starting a client: the module is registered in the combat
 * section, its lifecycle delegates to the controller in the mandated order, it starts inactive with the
 * main-menu flag set, and neither module nor controller can construct or send a packet.
 */
final class CrystalCombatRegistrationTest {
    private static final String CONTROLLER = "dev/tntcoating/core/combat/mc/CrystalCombatController";
    private static final String GATE = "dev/tntcoating/core/combat/CrystalCombatGate";
    private static final String RUNTIME = "dev/tntcoating/core/combat/mc/CombatRuntime";
    private static final String OBSERVATION_HOST =
        "dev/tntcoating/core/combat/mc/CrystalCombatObservationHost";
    private static final String OBSERVER = OBSERVATION_HOST + "$Observer";

    @Test
    void moduleRegistersInTheCombatSectionAfterTheRecorder() throws Exception {
        MethodNode initialize = method(node("/dev/tntcoating/TntCoatingAddon.class"), "onInitialize");
        int recorder = indexOfNew(initialize, "dev/tntcoating/modules/CombatRecorderModule");
        int crystal = indexOfNew(initialize, "dev/tntcoating/modules/CrystalCombat");
        assertTrue(recorder >= 0, "the combat recorder stays registered");
        assertTrue(crystal > recorder, "CrystalCombat must register after the recorder in the combat list");
    }

    @Test
    void moduleDelegatesItsLifecycleToTheController() throws Exception {
        ClassNode module = node("/dev/tntcoating/modules/CrystalCombat.class");
        assertTrue(calls(module, "onActivate", CONTROLLER, "enable"));
        assertTrue(calls(module, "onDeactivate", CONTROLLER, "disable"));
        assertTrue(calls(module, "getInfoString", CONTROLLER, "infoString"));
        assertTrue(calls(module, "unavailableReason", "dev/tntcoating/compat/CombatCapabilities", "inspect"));
        assertTrue(calls(module, "onTick", "dev/tntcoating/modules/CrystalCombat", "advance"));
        assertTrue(calls(module, "advance", CONTROLLER, "tick"));
        // Join/left reconcile through syncSession so a stale session from a previous world is closed first.
        assertTrue(calls(module, "onGameJoined", "dev/tntcoating/modules/CrystalCombat", "syncSession"));
        assertTrue(calls(module, "syncSession", CONTROLLER, "onJoined"));
        assertTrue(calls(module, "onGameLeft", CONTROLLER, "onLeft"));
    }

    @Test
    void antiSuicideCannotBeTurnedOffWhileTheModuleCouldRun() throws Exception {
        ClassNode module = node("/dev/tntcoating/modules/CrystalCombat.class");
        assertTrue(readsField(method(module, "configChanged"), "antiSuicide"),
            "the config hook must see the anti-suicide lock");
        assertTrue(callsOwnOrModule(method(module, "configChanged"), "disable"),
            "turning anti-suicide off must disable the module");
        assertTrue(readsField(method(module, "onTick"), "antiSuicide"),
            "the tick guard must re-check the anti-suicide lock");
        assertTrue(callsOwnOrModule(method(module, "onTick"), "disable"),
            "an active module with anti-suicide off must stop in the tick guard");
    }

    @Test
    void moduleStartsInactiveWithTheMainMenuFlagSet() throws Exception {
        MethodNode constructor = method(node("/dev/tntcoating/modules/CrystalCombat.class"), "<init>");
        boolean runInMainMenu = false;
        for (int i = 0; i < constructor.instructions.size(); i++) {
            AbstractInsnNode instruction = constructor.instructions.get(i);
            if (instruction instanceof FieldInsnNode field && field.name.equals("runInMainMenu")) {
                AbstractInsnNode previous = constructor.instructions.get(i - 1);
                runInMainMenu |= previous != null && previous.getOpcode() == Opcodes.ICONST_1;
            }
            if (instruction instanceof MethodInsnNode call) {
                assertNotEquals("enable", call.name, "the module must not enable itself in its constructor");
                assertNotEquals("toggle", call.name, "the module must not toggle itself in its constructor");
            }
        }
        assertTrue(runInMainMenu, "the module must stay subscribed so it sees the first world join");
    }

    @Test
    void enableChecksReadinessBeforeStartingOrActivating() throws Exception {
        ClassNode controller = node("/dev/tntcoating/core/combat/mc/CrystalCombatController.class");
        MethodNode enable = method(controller, "enable");
        int readiness = indexOfCall(enable, CONTROLLER + "$Readiness", "unavailableReason");
        int factory = indexOfCall(enable, CONTROLLER + "$StackFactory", "create");
        int protector = indexOfCall(enable, GATE, "setProtector");
        int activate = indexOfCall(enable, GATE, "activate");
        assertTrue(readiness >= 0 && factory > readiness,
            "readiness must be checked before the runtime stack is built");
        assertTrue(protector > factory && activate > protector,
            "the ledger protector is bound before the gate activates");
    }

    @Test
    void disableDeactivatesTheGateBeforeStoppingTheRuntime() throws Exception {
        ClassNode controller = node("/dev/tntcoating/core/combat/mc/CrystalCombatController.class");
        MethodNode disable = method(controller, "disable");
        int deactivate = indexOfCall(disable, GATE, "deactivate");
        int stopped = indexOfCall(disable, RUNTIME, "onDisabled");
        assertTrue(deactivate >= 0, "disable must deactivate the gate");
        assertTrue(stopped > deactivate, "the gate goes down before the runtime invalidates action work");
    }

    @Test
    void observationHostIsSubscribedOnceAndFeedsAfterApplicationEvents() throws Exception {
        ClassNode module = node("/dev/tntcoating/modules/CrystalCombat.class");
        MethodNode constructor = method(module, "<init>");
        assertTrue(indexOfNew(constructor, OBSERVATION_HOST) >= 0,
            "the always-on observation host is wired in the constructor");
        assertEquals(1, countCalls(constructor, "subscribe"),
            "the host is subscribed exactly once, independent of module activation");
        assertFalse(calls(module, "onActivate", OBSERVER, "spawned"),
            "activation must not be the source of the subscription");
        assertEquals(0, countCalls(method(module, "onActivate"), "subscribe"),
            "subscribing on activation would stop passive observation after disable");

        ClassNode host = node("/dev/tntcoating/core/combat/mc/CrystalCombatObservationHost.class");
        MethodNode added = method(host, "onEntityAdded");
        MethodNode removed = method(host, "onEntityRemoved");
        assertTrue(added.desc.contains("EntityAddedEvent"),
            "spawns come from Meteor's after-application entity-add event");
        assertTrue(removed.desc.contains("EntityRemovedEvent"),
            "removals come from Meteor's after-application entity-remove event");
        assertTrue(indexOfCall(added, OBSERVER, "spawned") >= 0,
            "the add handler must hand a resolved spawn to the controller");
        assertTrue(indexOfCall(removed, OBSERVER, "removed") >= 0,
            "the remove handler must hand a resolved removal to the controller");
    }

    @Test
    void moduleAndControllerConstructOrSendNoPackets() throws Exception {
        String[] classes = {
            "/dev/tntcoating/modules/CrystalCombat.class",
            "/dev/tntcoating/core/combat/mc/CrystalCombatController.class",
        };
        for (String resource : classes) {
            for (MethodNode method : node(resource).methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        assertFalse(call.owner.startsWith("net/minecraft/network/protocol"),
                            resource + " references packet traffic: " + call.owner + "." + call.name);
                        assertNotEquals("sendPacket", call.name,
                            resource + " must never send packets directly");
                    }
                    if (instruction instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW) {
                        assertFalse(type.desc.contains("Serverbound") || type.desc.contains("Clientbound"),
                            resource + " constructs a packet: " + type.desc);
                    }
                }
            }
        }
    }

    @Test
    void enableInstallsTheRespawnReconcilerAfterTheStackExists() throws Exception {
        MethodNode enable = method(node("/dev/tntcoating/core/combat/mc/CrystalCombatController.class"),
            "enable");
        int factory = indexOfCall(enable, CONTROLLER + "$StackFactory", "create");
        int hook = indexOfCall(enable, RUNTIME, "setRespawnReconciler");
        assertTrue(factory >= 0, "enable still builds the stack through the factory");
        assertTrue(hook > factory, "the respawn hook is installed only once the runtime exists");
    }

    @Test
    void respawnReconciliationRunsAfterTheBudgetObservedRespawn() throws Exception {
        ClassNode runtime = node("/" + RUNTIME + ".class");
        for (String methodName : new String[] {"applyObservation", "onRespawn"}) {
            MethodNode method = method(runtime, methodName);
            int observed = indexOfCall(method, "dev/tntcoating/core/combat/CombatRiskBudget", "observe");
            int reconcile = indexOfField(method, "respawnReconciler");
            assertTrue(observed >= 0, methodName + " must observe the respawn in the budget first");
            assertTrue(reconcile > observed,
                methodName + " must reconcile only after the budget observed RESPAWN");
        }
    }

    @Test
    void controllerTickAdvancesTheRuntimeBeforeTheEnabledOnlyStatusGate() throws Exception {
        MethodNode tick = method(node("/dev/tntcoating/core/combat/mc/CrystalCombatController.class"),
            "tick");
        int runtimeTick = indexOfCall(tick, RUNTIME, "tick");
        int enabledRead = indexOfField(tick, "enabled");
        assertTrue(runtimeTick >= 0, "controller ticks drive the runtime");
        assertTrue(enabledRead > runtimeTick,
            "passive ticks must advance the runtime before the enabled-only status gate");
    }

    @Test
    void observationHostForwardsTicksAndTheModuleDrivesPassiveTicks() throws Exception {
        ClassNode host = node("/" + OBSERVATION_HOST + ".class");
        MethodNode hostTick = method(host, "onTick");
        assertTrue(hostTick.desc.contains("TickEvent$Pre"),
            "the host tick must be the client pre-tick");
        assertTrue(indexOfCall(hostTick, OBSERVER, "tick") >= 0,
            "the always-subscribed host is the passive tick source while the module is disabled");

        ClassNode module = node("/dev/tntcoating/modules/CrystalCombat.class");
        assertTrue(calls(module, "passiveTick", "dev/tntcoating/modules/CrystalCombat", "advance"),
            "the passive path goes through the shared advance");
        assertTrue(calls(module, "advance", CONTROLLER, "tick"),
            "the shared path still drives the same controller tick");
        assertTrue(indexOfCall(method(module, "passiveTick"), CONTROLLER, "hasStack") >= 0,
            "no stack means nothing to drive");

        // The host's anonymous observer forwards each tick into the module's passive path.
        ClassNode observer = node("/dev/tntcoating/modules/CrystalCombat$1.class");
        assertTrue(callsOwnOrModule(method(observer, "tick"), "passiveTick"),
            "the host observer must reach the module's passive tick");
    }

    private static int indexOfField(MethodNode method, String name) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof FieldInsnNode field && field.name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static ClassNode node(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (var stream = CrystalCombatRegistrationTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource + " must be packaged");
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
            .filter(method -> method.name.equals(name))
            .findFirst().orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    private static int indexOfNew(MethodNode method, String owner) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof TypeInsnNode type
                && type.getOpcode() == Opcodes.NEW && type.desc.equals(owner)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfCall(MethodNode method, String owner, String name) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static int countCalls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) count++;
        }
        return count;
    }

    private static boolean calls(ClassNode node, String from, String owner, String name) {
        return indexOfCall(method(node, from), owner, name) >= 0;
    }

    private static boolean readsField(MethodNode method, String field) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode read && read.name.equals(field)) return true;
        }
        return false;
    }

    /** A call to {@code name} on the module class itself, whichever of the two legal owners javac emits. */
    private static boolean callsOwnOrModule(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)
                && (call.owner.equals("dev/tntcoating/modules/CrystalCombat")
                || call.owner.equals("meteordevelopment/meteorclient/systems/modules/Module"))) {
                return true;
            }
        }
        return false;
    }
}
