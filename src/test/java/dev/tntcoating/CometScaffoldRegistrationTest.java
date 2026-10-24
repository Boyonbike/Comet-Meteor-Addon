package dev.tntcoating;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class CometScaffoldRegistrationTest {
    @Test void independentScaffoldIsPackagedAndRegisteredBesideLegacyUpgrade() throws Exception {
        assertNotNull(getClass().getResource("/dev/tntcoating/modules/CometScaffold.class"));
        ClassNode node = new ClassNode();
        try (var in = getClass().getResourceAsStream("/dev/tntcoating/TntCoatingAddon.class")) {
            new ClassReader(in).accept(node, 0);
        }
        var initialize = node.methods.stream().filter(m -> m.name.equals("onInitialize")).findFirst().orElseThrow();
        boolean independent = false, legacy = false;
        for (var instruction : initialize.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                independent |= call.owner.equals("dev/tntcoating/modules/CometScaffold") && call.name.equals("<init>");
                legacy |= call.name.equals("upgraded");
            }
        }
        assertTrue(independent, "new module must be registered independently of Meteor upgrade compatibility");
        assertTrue(legacy, "preserve legacy upgrades");
    }
}
