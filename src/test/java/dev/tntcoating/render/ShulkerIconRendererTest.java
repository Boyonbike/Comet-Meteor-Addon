package dev.tntcoating.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShulkerIconRendererTest {
    @Test void coloursAreScopedToOneDrawAndNestedNormalIconsKeepTheirTexture() {
        assertNull(ShulkerIconRenderer.extractingColour());
        ShulkerIconRenderer.withColour(0xff12ab34, () -> {
            assertEquals(0xff12ab34, ShulkerIconRenderer.extractingColour());
            ShulkerIconRenderer.withColour(null, () -> assertNull(ShulkerIconRenderer.extractingColour()));
            assertEquals(0xff12ab34, ShulkerIconRenderer.extractingColour());
        });
        assertNull(ShulkerIconRenderer.extractingColour());
    }

    @Test void failedDrawCannotColourTheNextIcon() {
        assertThrows(IllegalStateException.class, () -> ShulkerIconRenderer.withColour(0xff000000, () -> {
            throw new IllegalStateException("draw failed");
        }));
        assertNull(ShulkerIconRenderer.extractingColour());
    }
}
