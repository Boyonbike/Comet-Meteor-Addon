/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1.1 purity guard. The {@code core/combat} package must compile and test without Minecraft or
 * Meteor on the classpath; only {@code core/combat/mc} may name their types.
 *
 * <p>The same rule is enforced as a Gradle {@code verifyCombatPurity} check over main and test sources so
 * a violating import fails the build before tests run. This test additionally proves the scanner itself
 * catches a violation and that the {@code check} task depends on the guard.</p>
 */
class CombatPurityTest {
    // Built from parts so this guard's own source never contains the token it scans for.
    private static final String MINECRAFT = "net." + "minecraft";
    private static final String METEOR = "meteordevelop" + "ment";
    private static final List<String> FORBIDDEN = List.of(MINECRAFT, METEOR);
    private static final String MAIN_ROOT = "src/main/java/dev/tntcoating/core/combat";
    private static final String TEST_ROOT = "src/test/java/dev/tntcoating/core/combat";

    @Test
    void theScannerFindsViolationsOnlyOutsideTheMcPackage() throws IOException {
        Path root = temp.resolve("combat");
        write(root.resolve("types/ConnectionKey.java"), "package dev.tntcoating.core.combat.types;",
            "import " + MINECRAFT + ".world.entity.Entity;");
        write(root.resolve("mc/Adapter.java"), "package dev.tntcoating.core.combat.mc;",
            "import " + MINECRAFT + ".world.entity.Entity;",
            "import " + METEOR + ".MeteorClient;");
        write(root.resolve("CrystalLedger.java"), "package dev.tntcoating.core.combat;",
            "import java.util.List;");

        List<String> violations = scan(root);

        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().contains("ConnectionKey.java"), violations.toString());
        assertTrue(violations.getFirst().contains(MINECRAFT), violations.toString());
    }

    @Test
    void noPureCombatSourceNamesMinecraftOrMeteorTypes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String root : List.of(MAIN_ROOT, TEST_ROOT)) {
            Path path = Path.of(root);
            assertTrue(Files.isDirectory(path),
                "run tests from the 26.2 project directory; missing " + path);
            violations.addAll(scan(path));
        }

        assertTrue(violations.isEmpty(), "core/combat must stay Minecraft-free; " + violations);
    }

    @Test
    void theCheckTaskDependsOnVerifyCombatPurity() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("build.gradle.kts"));
        int wiring = IntStream.range(0, lines.size())
            .filter(i -> lines.get(i).contains("tasks.named(\"check\")"))
            .findFirst()
            .orElse(-1);
        assertTrue(wiring >= 0, "build.gradle.kts must wire the purity check into the check task");
        String statement = String.join(" ",
            lines.subList(wiring, Math.min(lines.size(), wiring + 3)));
        assertTrue(statement.contains("dependsOn(verifyCombatPurity)"),
            "check must depend on verifyCombatPurity, not merely register it: " + statement);
        assertTrue(String.join("\n", lines).contains(TEST_ROOT),
            "the Gradle guard must scan test sources too");
    }

    @TempDir
    Path temp;

    private static void write(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, List.of(lines));
    }

    /** Returns one message per offending file/line; the {@code mc} package is the only exemption. */
    private static List<String> scan(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Path relative = root.relativize(file);
                if (relative.getNameCount() > 1 && relative.getName(0).toString().equals("mc")) continue;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    for (String token : FORBIDDEN) {
                        if (line.contains(token)) {
                            violations.add(relative + ":" + (i + 1) + " names " + token);
                        }
                    }
                }
            }
        }
        return violations;
    }
}
