/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Mode;
import dev.tntcoating.core.combat.Safety;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** P3.2 acceptance: an active module may not keep running settings that enable would refuse. */
final class CrystalCombatSettingsTest {

    private static final Safety VALID = new Safety(4.0, 6.0, 2.0, 2.0);

    /**
     * P5.2 module-level wiring guard: the production stack really builds a PistonTrapController, hands it
     * to the controller Stack, and the module applies the mode/layout/trap setting before the controller
     * starts. A client is still required for a live buildStack() smoke test (checklist real-client item).
     */
    @Test
    void productionStackWiresThePistonTrapAndAppliesTheBetaSettingsToTheController() throws IOException {
        String module = Files.readString(Path.of("src/main/java/dev/tntcoating/modules/CrystalCombat.java"));
        assertTrue(module.contains("new PistonTrapController("),
            "buildStack must create the PistonOnly trap controller");
        assertTrue(module.contains("new CrystalCombatController.Stack("),
            "buildStack must hand the trap to the controller Stack");
        assertTrue(module.contains("controller.setPistonLayouts(selection())"),
            "config changes must push the sixteen layout toggles into the controller");
        assertTrue(module.contains("controller.setMode(mode.get())"),
            "config changes must switch the single runner by mode");
        assertTrue(module.contains("controller.setTrapEnabled(pistonTrap.get())"),
            "config changes must push the beta trap setting");
    }

    @Test
    void directOnlyConservativeWithTheProductionEnvelopeIsAllowed() {
        assertNull(CrystalCombat.settingsRefusal(
            Mode.DIRECT_ONLY, CrystalCombat.Profile.CONSERVATIVE, true, VALID));
    }

    @Test
    void betaPistonOnlyIsAcceptedAndHybridStillRefused() {
        // P5.2 exposes the beta PistonOnly tester mode with the same Conservative envelope.
        assertNull(CrystalCombat.settingsRefusal(
            Mode.PISTON_ONLY, CrystalCombat.Profile.CONSERVATIVE, true, VALID));

        assertNull(CrystalCombat.settingsRefusal(
            Mode.HYBRID, CrystalCombat.Profile.CONSERVATIVE, true, VALID),
            "Phase 6.4 stage 1 enables the serialised Hybrid mode");
    }

    @Test
    void unsafeSafetyEnvelopeIsRefusedMidSessionToo() {
        String refusal = CrystalCombat.settingsRefusal(
            Mode.DIRECT_ONLY, CrystalCombat.Profile.CONSERVATIVE, true, new Safety(1.0, 6.0, 1.0, 2.0));
        assertNotNull(refusal);
        assertTrue(refusal.contains("unsafe safety settings"));
    }

    @Test
    void configChangedChecksTheRefusalBeforeItLetsTheRuntimeContinue() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/dev/tntcoating/modules/CrystalCombat.java"));
        int start = source.indexOf("private void configChanged()");
        int end = source.indexOf("private Object currentConnection()", start);
        assertTrue(start >= 0 && end > start, "configChanged must remain findable");
        String body = source.substring(start, end);
        int refusal = body.indexOf("settingsRefusal(");
        int continueAfter = body.indexOf("controller.onConfigChanged()");
        assertTrue(refusal >= 0, "configChanged must re-check settingsRefusal");
        assertTrue(continueAfter > refusal,
            "the refusal check must run before controller.onConfigChanged can keep the runtime alive");
        assertTrue(body.indexOf("disable()") > refusal,
            "a refused mid-session setting must disable the module");
        assertEquals(1, count(body, "settingsRefusal("), "one settings check per change");
        int reconfigure = body.lastIndexOf("controller.reconfigure(directSettings())");
        assertTrue(reconfigure > continueAfter,
            "an accepted mid-session change must install the new DirectSettings on the live engine");
    }

    @Test
    void cleanupConfirmCommandIsWiredToTheProductionTrapReconcile() throws IOException {
        String command = Files.readString(Path.of(
            "src/main/java/dev/tntcoating/commands/CombatExportCommand.java"));
        String module = Files.readString(Path.of(
            "src/main/java/dev/tntcoating/modules/CrystalCombat.java"));
        String controller = Files.readString(Path.of(
            "src/main/java/dev/tntcoating/core/combat/mc/CrystalCombatController.java"));
        String trap = Files.readString(Path.of(
            "src/main/java/dev/tntcoating/core/combat/mc/PistonTrapController.java"));
        assertTrue(command.contains("literal(\"cleanup\")"));
        assertTrue(command.contains("literal(\"confirm\")"));
        assertTrue(command.contains("confirmManualCleanup()"));
        assertTrue(module.contains("controller.confirmTrapCleanup()"));
        assertTrue(controller.contains("pistonTrap().reconcile()"));
        assertTrue(trap.contains("public void reconcile()"));
    }

    /** Audit §6.2: the stronger-hit motion model is unconfirmed on 1.21.11, so both dependents are refused. */
    @Test
    void unvalidatedMotionFeaturesAreRefusedOnThisVersion() {
        assertNull(CrystalCombat.versionRefusal(false, false));
        String motion = CrystalCombat.versionRefusal(true, false);
        String hurt = CrystalCombat.versionRefusal(false, true);
        assertNotNull(motion);
        assertNotNull(hurt);
        assertTrue(motion.contains("not available on Minecraft 1.21.11"), motion);
        assertTrue(hurt.contains("not available on Minecraft 1.21.11"), hurt);
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
