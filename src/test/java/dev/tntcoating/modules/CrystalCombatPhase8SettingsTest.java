/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.Mode;
import dev.tntcoating.core.combat.PistonLayoutSelection;
import dev.tntcoating.core.combat.Safety;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Stage 5 acceptance: the Phase 8 settings groups, the experimental/beta warnings, and the fromTag
 * migration seam. The module cannot be constructed in a plain JUnit JVM (MeteorClient's static
 * initializer needs the Fabric loader), so, like the module's ASM registration test, this test reads the
 * production source and enumerates the settings the module actually declares.
 */
final class CrystalCombatPhase8SettingsTest {

    private static final String SOURCE = source();
    private static final Safety VALID = new Safety(4.0, 6.0, 2.0, 2.0);

    private static final List<String> EXPERIMENTAL_OR_BETA = List.of(
        "motion-prediction",
        "hurt-sync",
        "support",
        "surround-gap",
        "dangerous-crystal-break",
        "anti-piston",
        "footprint-concurrency",
        "piston-trap",
        "auto-cleanup");

    @Test
    void everySettingSitsInThePlanGroup() {
        Map<String, List<String>> groups = parseSettings();

        assertEquals(List.of("General", "Safety", "Direct", "Timing", "Piston", "Defence",
            "Pauses", "Experimental", "Render", "Diagnostics"), List.copyOf(groups.keySet()));
        assertEquals(List.of("mode", "profile", "target-range"), groups.get("General"));
        assertEquals(List.of("anti-suicide", "risk-model", "min-target-damage", "max-self-damage",
            "remaining-health-margin", "max-friend-damage"), groups.get("Safety"));
        assertEquals(List.of("place-range", "attack-range", "walls-range", "own-only", "support",
            "surround-gap"), groups.get("Direct"));
        assertEquals(List.of("motion-prediction", "hurt-sync"), groups.get("Timing"));
        assertEquals(List.of("piston-trap", "auto-cleanup"), groups.get("Piston"));
        assertEquals(List.of("dangerous-crystal-break", "anti-piston"), groups.get("Defence"));
        assertEquals(List.of("pause-when-eating", "pause-when-drinking", "pause-on-baritone",
            "pause-when-mining", "pause-on-screens", "health-floor", "pause-on-lag"),
            groups.get("Pauses"));
        assertEquals(List.of("footprint-concurrency"), groups.get("Experimental"));
        assertEquals(List.of("show-risk-debt"), groups.get("Render"));
        assertEquals(List.of("debug-log", "export-include-identities", "incident-marker-key"),
            groups.get("Diagnostics"));
        assertFalse(SOURCE.contains("createGroup(\"Ranges\")"),
            "the old Ranges group must not survive the tidy in the source");
    }

    @Test
    void theSixteenBetaLayoutsLiveInThePistonGroup() {
        String layoutBlock = between(SOURCE, "private void addLayoutSettings()", "private PistonLayoutSelection selection()");
        assertTrue(layoutBlock.contains("sgPiston.add(new BoolSetting.Builder()"),
            "layout toggles belong to the Piston group");
        assertFalse(layoutBlock.contains("sgGeneral.add("),
            "no layout toggle may stay in General");
        assertTrue(layoutBlock.contains(".name(\"piston-layout-\" + combination.id())"));
        assertTrue(layoutBlock.contains(".description(\"Beta single-push layout:"));
        assertEquals(16, PistonLayoutSelection.combinations().size(),
            "every measured layout is still exposed");
    }

    @Test
    void everyExperimentalOrBetaSettingHasAOneLineWarning() {
        Map<String, List<String>> groups = parseSettings();
        List<String> allNames = groups.values().stream().flatMap(List::stream).toList();

        for (String name : EXPERIMENTAL_OR_BETA) {
            assertTrue(allNames.contains(name), "unknown experimental setting " + name);
            String description = description(name);
            assertFalse(description.contains("\n"), name + " warning must be one line");
            assertFalse(description.contains("\\n"),
                name + " warning must not embed a literal \\n escape");
            assertTrue(description.startsWith("Experimental") || description.startsWith("Beta"),
                name + " must carry an Experimental/Beta warning, was: " + description);
            assertTrue(description.length() >= 40,
                name + " needs a concrete warning sentence, was: " + description);
        }
    }

    @Test
    void riskDebtDisplaySettingDefaultsOnAndLivesInRender() {
        assertEquals(List.of("show-risk-debt"), parseSettings().get("Render"));
        String block = block("show-risk-debt");
        assertTrue(block.contains(".defaultValue(true)"), "the debt display is on by default");
        assertTrue(SOURCE.contains("@EventHandler") && SOURCE.contains("controller.riskSnapshot()"),
            "the module reads only the published volatile snapshot");
        assertTrue(SOURCE.contains("CrystalCombatRender.render("),
            "the render callback delegates to the read-only renderer");
        String renderer = read("src/main/java/dev/tntcoating/render/CrystalCombatRender.java");
        assertTrue(renderer.contains("event.drawContext"),
            "the renderer draws through the 2D event's GuiGraphics");
        assertFalse(renderer.contains("risk().") || renderer.contains("stack()")
                || renderer.contains("budget.") || renderer.contains("world."),
            "the renderer may only read the published snapshot");
    }

    @Test
    void fromTagMigratesTheOldGroupsBeforeSuperLoadsThem() {
        String method = between(SOURCE, "public Module fromTag(CompoundTag tag)", "private void profileChanged()");
        assertNotNull(method, "the module must override fromTag");
        int migrate = method.indexOf("CrystalCombatSettingsMigration.migrate(tag)");
        int superCall = method.indexOf("return super.fromTag(");
        assertTrue(migrate >= 0, "fromTag must delegate to the migration helper");
        assertTrue(superCall > migrate, "the migration must run before super.fromTag");
        assertTrue(method.contains("super.fromTag("), "the module must keep Meteor's normal load");
    }

    @Test
    void profileOffersConservativeAndCustomButNoAnarchy() {
        String enumBlock = between(SOURCE, "public enum Profile", "}");
        assertNotNull(enumBlock);
        assertTrue(enumBlock.contains("CONSERVATIVE"), enumBlock);
        assertTrue(enumBlock.contains("CUSTOM"), enumBlock);
        assertFalse(enumBlock.contains("ANARCHY"), "Anarchy is not offered or implemented");
        assertNull(CrystalCombat.settingsRefusal(Mode.DIRECT_ONLY,
            CrystalCombat.Profile.CUSTOM, true, VALID),
            "Custom keeps the user's values but is still accepted");
        assertNull(CrystalCombat.settingsRefusal(Mode.PISTON_ONLY,
            CrystalCombat.Profile.CUSTOM, true, VALID));
        assertTrue(CrystalCombat.settingsRefusal(Mode.DIRECT_ONLY,
            CrystalCombat.Profile.CUSTOM, false, VALID).contains("anti-suicide"));
        assertTrue(CrystalCombat.settingsRefusal(Mode.DIRECT_ONLY,
            CrystalCombat.Profile.CUSTOM, true, new Safety(1.0, 6.0, 2.0, 2.0))
            .contains("unsafe safety settings"),
            "Custom cannot weaken the production floors");
    }

    @Test
    void switchingToConservativeReappliesTheConservativeSafetyDefaults() {
        String block = between(SOURCE, "private void profileChanged()", "private void applyConservativeSafety()");
        assertNotNull(block);
        assertTrue(block.contains("Profile.CONSERVATIVE"), block);
        assertTrue(block.contains("applyConservativeSafety()"), block);
        assertTrue(block.contains("configChanged()"), "the profile switch still goes through configChanged");

        String reset = between(SOURCE, "private void applyConservativeSafety()", "private void configChanged()");
        assertNotNull(reset);
        assertTrue(reset.contains("CombatProfiles.CONSERVATIVE_SAFETY"), reset);
        for (String field : List.of("minTargetDamage", "maxSelfDamage", "remainingHealthMargin",
            "maxFriendDamage")) {
            assertTrue(reset.contains(field + ".set("), field + " must be reapplied");
        }
        assertTrue(block("profile").contains(".onChanged(value -> profileChanged())"));
    }

    @Test
    void theProfileSwitchStillRunsTheRefusalAndRuntimeReconfigureInOrder() {
        String block = between(SOURCE, "private void configChanged()", "private Object currentConnection()");
        assertNotNull(block);
        assertEquals(1, count(block, "settingsRefusal("), "one refusal check per config change");
        assertTrue(block.indexOf("settingsRefusal(") < block.indexOf("controller.onConfigChanged()"));
        assertTrue(block.indexOf("disable()") < block.indexOf("controller.onConfigChanged()"),
            "an unsafe change disables before the runtime is reconfigured");
        assertTrue(block.lastIndexOf("controller.reconfigure(directSettings())")
            > block.indexOf("controller.onConfigChanged()"),
            "the settings push stays last");
    }

    @Test
    void settingsTagMigrationHelperExistsForDeferredKeys() {
        Path migration = Path.of("src/main/java/dev/tntcoating/core/CrystalCombatSettingsMigration.java");
        assertTrue(Files.exists(migration), "the fromTag migration helper must exist");
        assertTrue(SOURCE.contains("import dev.tntcoating.core.CrystalCombatSettingsMigration;"));
    }

    // ------------------------------------------------------------------ source parsing

    private static String source() {
        return read("src/main/java/dev/tntcoating/modules/CrystalCombat.java");
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Enumerates every {@code sgX.add(new ...Setting.Builder()... .name("..."))} declaration in the
     * production source. The dynamic layout name is handled separately by the layout test.
     */
    private static Map<String, List<String>> parseSettings() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Matcher add = Pattern.compile("sg([A-Z][A-Za-z]*)\\.add\\(new ").matcher(SOURCE);
        while (add.find()) {
            int build = SOURCE.indexOf(".build())", add.end());
            if (build < 0) continue;
            String block = SOURCE.substring(add.end(), build);
            Matcher name = Pattern.compile("\\.name\\(\"([^\"]+)\"\\)").matcher(block);
            if (name.find()) groups.computeIfAbsent(add.group(1), key -> new ArrayList<>()).add(name.group(1));
        }
        return groups;
    }

    private static String block(String settingName) {
        int at = SOURCE.indexOf(".name(\"" + settingName + "\")");
        assertTrue(at >= 0, "missing setting " + settingName);
        int start = SOURCE.lastIndexOf(".add(new ", at);
        int end = SOURCE.indexOf(".build())", at);
        return SOURCE.substring(start, end);
    }

    private static String description(String settingName) {
        String block = block(settingName);
        int at = block.indexOf(".description(");
        assertTrue(at >= 0, "missing description for " + settingName);
        int end = block.indexOf(".defaultValue(", at);
        assertTrue(end > at, "missing default after the description of " + settingName);
        return literals(block.substring(at, end));
    }

    private static String literals(String block) {
        Matcher matcher = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(block);
        StringBuilder text = new StringBuilder();
        while (matcher.find()) text.append(matcher.group(1));
        return text.toString();
    }

    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        if (start < 0) return null;
        int end = source.indexOf(to, start + from.length());
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }

    private static int count(String source, String needle) {
        int count = 0;
        int at = 0;
        while ((at = source.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }
}
