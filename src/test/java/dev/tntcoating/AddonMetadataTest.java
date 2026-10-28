package dev.tntcoating;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AddonMetadataTest {
    @Test void fabricIdentityAndDisplayNameKeepAuthorSeparate() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fabric.mod.json")) {
            assertNotNull(stream);
            var metadata = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("comet", metadata.get("id").getAsString());
            assertEquals("Comet", metadata.get("name").getAsString());
            var authors = metadata.getAsJsonArray("authors");
            assertEquals(1, authors.size());
            assertEquals("Boyonabike", authors.get(0).getAsString());
        }
    }

    @Test void ownVersionLookupsUseTheRegisteredModId() throws Exception {
        for (String module : new String[] { "FlightDashboard", "CometScaffold" }) {
            var node = new org.objectweb.asm.tree.ClassNode();
            try (var stream = getClass().getResourceAsStream("/dev/tntcoating/modules/" + module + ".class")) {
                assertNotNull(stream);
                new org.objectweb.asm.ClassReader(stream).accept(node, 0);
            }
            int lookups = 0;
            for (var method : node.methods) for (var instruction : method.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.owner.equals("net/fabricmc/loader/api/FabricLoader") && call.name.equals("getModContainer")) {
                    assertInstanceOf(org.objectweb.asm.tree.LdcInsnNode.class, call.getPrevious());
                    assertEquals("comet", ((org.objectweb.asm.tree.LdcInsnNode) call.getPrevious()).cst,
                        module + " must resolve Comet metadata rather than falling back to an unknown version");
                    lookups++;
                }
            }
            assertTrue(lookups > 0, "Version lookup must be exercised for " + module);
        }
    }

    @Test void interactionAccessorIsPackagedAndRegistered() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fabric.mod.json")) {
            var metadata = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("comet.mixins.json", metadata.getAsJsonArray("mixins").get(0).getAsString());
        }
        try (var stream = getClass().getResourceAsStream("/comet.mixins.json")) {
            assertNotNull(stream);
            var config = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("dev.tntcoating.mixin", config.get("package").getAsString());
            assertEquals("GameModeInvoker", config.getAsJsonArray("client").get(0).getAsString());
        }
        assertNotNull(getClass().getResource("/dev/tntcoating/mixin/GameModeInvoker.class"));
    }

    @Test void processedMetadataSatisfiesMeteorAuthorRequirement() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fabric.mod.json")) {
            assertNotNull(stream, "The built addon must include fabric.mod.json.");
            var metadata = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(metadata.has("authors"), "Meteor AddonManager requires authors metadata.");
            var authors = metadata.getAsJsonArray("authors");
            assertFalse(authors.isEmpty(), "Meteor rejects addons without at least one author.");
            for (var author : authors) assertFalse(author.getAsString().isBlank());
            assertFalse(metadata.get("version").getAsString().contains("${"), "Resource placeholders must be expanded.");
        }
    }
}
