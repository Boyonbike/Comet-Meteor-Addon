/*
 * Original Comet implementation by Boyonabike.
 * Independent: tests written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Guards the tester export contract without a client: {@code .crystalcombat export} must build and print the
 * archive plan before it can write anything, and the two subcommands must be registered. This is the
 * command-level companion to the bundle's in-memory plan/preview tests.
 */
final class CombatExportCommandPlanTest {
    private static final String COMMAND = "dev/tntcoating/commands/CombatExportCommand";
    private static final String BUNDLE = "dev/tntcoating/core/tester/CombatExportBundle";

    @Test
    void exportPlansAndPreviewsBeforeItWrites() throws Exception {
        MethodNode export = method(node("/dev/tntcoating/commands/CombatExportCommand.class"), "export");
        int create = indexOfCall(export, BUNDLE, "create");
        int preview = indexOfCall(export, BUNDLE, "preview");
        int write = indexOfCall(export, COMMAND, "write");
        assertTrue(create >= 0, "export must build the archive plan in memory");
        assertTrue(preview >= 0, "export must render the plan before writing");
        assertTrue(create < preview, "the plan must exist before the preview explains it");
        assertTrue(write >= 0, "export must be able to write after the plan");
        assertTrue(preview < write, "the preview must run before the confirm write");
        assertTrue(indexOfCall(export, BUNDLE, "write") < 0,
            "the plan preview path must not write the zip directly");
    }

    @Test
    void buildRegistersExportAndConfirmSubcommands() throws Exception {
        MethodNode build = method(node("/dev/tntcoating/commands/CombatExportCommand.class"), "build");
        assertTrue(hasLiteral(build, "export"), "export subcommand must be registered");
        assertTrue(hasLiteral(build, "confirm"), "confirm subcommand must be registered");
        assertTrue(countCalls(build, "executes") >= 3,
            "root usage, plan-only export and confirm export each need an executor");
    }

    private static boolean hasLiteral(MethodNode method, String text) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant && text.equals(constant.cst)) return true;
        }
        return false;
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

    private static ClassNode node(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (var stream = CombatExportCommandPlanTest.class.getResourceAsStream(resource)) {
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
}
