package dev.tntcoating;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class HighwayFlyRegistrationTest {
    @Test void profileLoadRestoresManagedRequestsBeforeCheckingEffectiveRecovery() throws Exception {
        ClassNode highway = new ClassNode();
        try (var stream = getClass().getResourceAsStream("/dev/tntcoating/modules/HighwayFly.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(highway, 0);
        }
        var load = highway.methods.stream().filter(m -> m.name.equals("endProfileLoad")).findFirst().orElseThrow();
        boolean requestsRestored = false;
        for (var instruction : load.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.name.equals("syncHelpers")) requestsRestored = true;
            if (call.name.equals("lobbyEnabled")) assertTrue(requestsRestored,
                "Managed-only Auto Lobby is saved inactive: restore its request before validating a recovery profile");
        }
    }

    @Test void standaloneLobbyHandoffUsesResumedFlightSemantics() throws Exception {
        ClassNode lobby = new ClassNode();
        try (var stream = getClass().getResourceAsStream("/dev/tntcoating/modules/AutoLobby.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(lobby, 0);
        }
        var resume = lobby.methods.stream().filter(m -> m.name.equals("tickResuming")).findFirst().orElseThrow();
        boolean resumesFlight = false;
        for (var instruction : resume.instructions) if (instruction instanceof MethodInsnNode call
            && call.owner.equals("dev/tntcoating/modules/HighwayFly")) {
            assertNotEquals("enable", call.name, "Ordinary activation loses the saved route's resumed-flight semantics");
            if (call.name.equals("resumeRecoveredFlight")) resumesFlight = true;
        }
        assertTrue(resumesFlight);
    }

    @Test void lobbyCancellationReleasesSharedSessionEvenWhenFlightIsOff() throws Exception {
        ClassNode lobby = new ClassNode();
        try (var stream = getClass().getResourceAsStream("/dev/tntcoating/modules/AutoLobby.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(lobby, 0);
        }
        var cancel = lobby.methods.stream().filter(m -> m.name.equals("cancelRecovery")).findFirst().orElseThrow();
        boolean stopsSession = false;
        for (var instruction : cancel.instructions) if (instruction instanceof MethodInsnNode call
            && call.owner.equals("dev/tntcoating/core/HighwayFlySession") && call.name.equals("stop")) stopsSession = true;
        assertTrue(stopsSession, "Standalone completion must release recovery so homes and portal protection can run again");
    }

    @Test void manualFlightStopCancelsRecoveryRegardlessOfIndependentLobbyActivation() throws Exception {
        ClassNode highway = new ClassNode();
        try (var stream = getClass().getResourceAsStream("/dev/tntcoating/modules/HighwayFly.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(highway, 0);
        }
        var deactivate = highway.methods.stream().filter(m -> m.name.equals("onDeactivate")).findFirst().orElseThrow();
        boolean cancelsRecovery = false;
        for (var instruction : deactivate.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.name.equals("cancelRecovery")) cancelsRecovery = true;
            assertNotEquals("independentlyEnabled", call.name,
                "An independent Auto Lobby must not preserve and restart a trip the user explicitly stopped");
        }
        assertTrue(cancelsRecovery);
    }

    @Test void highwayFlightPausesBothScaffoldControllers() throws Exception {
        // Check the compiled module integration without starting a Minecraft client.
        ClassNode highway = new ClassNode();
        try (var stream = getClass().getResourceAsStream("/dev/tntcoating/modules/HighwayFly.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(highway, 0);
        }
        var conflicts = new HashSet<String>();
        var incompatible = highway.methods.stream()
            .filter(m -> m.name.equals("incompatibleModules")).findFirst().orElseThrow();
        for (var instruction : incompatible.instructions) {
            if (instruction instanceof LdcInsnNode load && load.cst instanceof Type type) {
                conflicts.add(type.getInternalName());
            }
        }
        assertTrue(conflicts.contains("dev/tntcoating/modules/CometScaffold"),
            "Highway Fly must pause Comet Scaffold so its edge guard cannot interrupt travel");
        assertTrue(conflicts.contains("meteordevelopment/meteorclient/systems/modules/movement/Scaffold"),
            "Highway Fly must also pause Meteor Scaffold");
    }

    @Test void registersHighwayAndIndependentHelpersAndPreservesScaffold() throws Exception {
        ClassNode addon = new ClassNode();
        try (var stream = getClass().getResourceAsStream("/dev/tntcoating/TntCoatingAddon.class")) {
            new ClassReader(stream).accept(addon, 0);
        }
        var registered = new HashSet<String>();
        var initialize = addon.methods.stream().filter(m -> m.name.equals("onInitialize")).findFirst().orElseThrow();
        for (var instruction : initialize.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("<init>")) registered.add(call.owner);
        }
        assertTrue(registered.contains("dev/tntcoating/modules/HighwayFly"));
        assertTrue(registered.contains("dev/tntcoating/modules/CometScaffold"));
        for (String helper : new String[] {"AutoLobby", "AutoSetHome", "AntiPortal"}) {
            assertTrue(registered.contains("dev/tntcoating/modules/" + helper), helper + " must be independently registered");
        }
        assertFalse(registered.contains("dev/tntcoating/modules/SmartAutoFly"));
        assertFalse(registered.contains("dev/tntcoating/modules/HighwayDashboard"), "Dashboard belongs to Highway Fly settings");
    }
}
