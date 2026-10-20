package dev.tntcoating.compat;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MeteorUpgradeCheckTest {
    private static final String SCAFFOLD = "meteordevelopment/meteorclient/systems/modules/movement/Scaffold";
    private static final String AUTO_WEAPON = "meteordevelopment/meteorclient/systems/modules/combat/AutoWeapon";

    /** Every Comet mixin aimed at Meteor must fit the Meteor build Comet is compiled and shipped against. */
    @Test void everyMeteorUpgradeFitsThePinnedMeteorBuild() throws Exception {
        int checked = 0;
        for (String mixin : configuredMixins()) {
            ClassNode node = read("dev/tntcoating/mixin/" + mixin);
            assertNotNull(node, mixin);
            if (!MeteorUpgradeCheck.targetsMeteor(node)) continue;
            checked++;
            assertEquals(List.of(), MeteorUpgradeCheck.problems(node, MeteorUpgradeCheckTest::read), mixin);
        }
        // Elytra Fly, Auto Tool/Weapon, Scaffold, Inventory Tweaks, module order and settings links.
        assertTrue(checked >= 8, "only " + checked + " Meteor mixins were checked");
    }

    @Test void aRemovedTargetMethodIsReported() throws Exception {
        ClassNode scaffold = read(SCAFFOLD);
        scaffold.methods.removeIf(method -> method.name.equals("onTick"));
        var problems = MeteorUpgradeCheck.problems(read("dev/tntcoating/mixin/ScaffoldMixin"), withReplacement(SCAFFOLD, scaffold));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("onTick")), problems.toString());
    }

    @Test void minecraftHooksFitThePinnedMinecraftBuild() throws Exception {
        for (String name : List.of("NoHurtCamMixin", "SmartElytraSwapMixin")) {
            ClassNode mixin = read("dev/tntcoating/mixin/" + name);
            assertNotNull(mixin);
            assertEquals(List.of(), MeteorUpgradeCheck.problems(mixin, MeteorUpgradeCheckTest::read), name);
        }
    }

    @Test void replenishUpgradeFallsBackIfMeteorChangesItsSlotTracking() throws Exception {
        String name = "meteordevelopment/meteorclient/systems/modules/player/AutoReplenish";
        ClassNode replenish = read(name);
        replenish.fields.removeIf(field -> field.name.equals("items"));
        var problems = MeteorUpgradeCheck.problems(read("dev/tntcoating/mixin/AutoReplenishMixin"), withReplacement(name, replenish));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("items")), problems.toString());
    }

    @Test void aChangedConstantIsReported() throws Exception {
        ClassNode weapon = read(AUTO_WEAPON);
        MethodNode best = weapon.methods.stream().filter(method -> method.name.equals("getBestWeapon")).findFirst().orElseThrow();
        for (var insn : best.instructions) {
            if (insn.getOpcode() == Opcodes.BIPUSH && insn instanceof IntInsnNode push && push.operand == 9) push.operand = 10;
        }
        var problems = MeteorUpgradeCheck.problems(read("dev/tntcoating/mixin/AutoWeaponMixin"), withReplacement(AUTO_WEAPON, weapon));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("constant 9")), problems.toString());
    }

    @Test void aRemovedInjectionPointIsReported() throws Exception {
        ClassNode scaffold = read(SCAFFOLD);
        for (MethodNode method : scaffold.methods) {
            if (!method.name.equals("onTick")) continue;
            // As if Meteor stopped streaming the tower collision check.
            for (var insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("stream")) call.name = "streamOf";
            }
        }
        var problems = MeteorUpgradeCheck.problems(read("dev/tntcoating/mixin/ScaffoldMixin"), withReplacement(SCAFFOLD, scaffold));
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("Streams;stream")), problems.toString());
    }

    @Test void aMissingTargetClassIsReported() throws Exception {
        var problems = MeteorUpgradeCheck.problems(read("dev/tntcoating/mixin/ScaffoldMixin"), name -> null);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("missing"));
    }

    @Test void minecraftTargetsAreLeftToMixin() throws Exception {
        assertFalse(MeteorUpgradeCheck.targetsMeteor(read("dev/tntcoating/mixin/ShulkerIconsMixin")));
        assertTrue(MeteorUpgradeCheck.targetsMeteor(read("dev/tntcoating/mixin/ScaffoldMixin")));
    }

    @Test void anUngroupedMixinAppliesAlone() {
        assertEquals(List.of("ScaffoldMixin"), MeteorUpgrades.group("ScaffoldMixin"));
    }

    private static MeteorUpgradeCheck.ClassNodes withReplacement(String name, ClassNode replacement) {
        return requested -> requested.replace('.', '/').equals(name) ? replacement : read(requested);
    }

    private static List<String> configuredMixins() throws Exception {
        try (InputStream stream = MeteorUpgradeCheckTest.class.getResourceAsStream("/comet.mixins.json")) {
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> mixins = new ArrayList<>();
            config.getAsJsonArray("client").forEach(element -> mixins.add(element.getAsString()));
            return mixins;
        }
    }

    private static ClassNode read(String name) throws Exception {
        try (InputStream stream = MeteorUpgradeCheckTest.class.getClassLoader()
            .getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

}
