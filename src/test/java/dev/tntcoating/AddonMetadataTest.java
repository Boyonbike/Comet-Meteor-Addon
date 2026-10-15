package dev.tntcoating;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class AddonMetadataTest {
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
