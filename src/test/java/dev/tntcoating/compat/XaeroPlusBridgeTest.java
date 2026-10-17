package dev.tntcoating.compat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XaeroPlusBridgeTest {
    @Test void bridgeCanBeUsedWithoutXaeroPlusOnTheRuntimeClasspath() {
        // Compile-only integrations are deliberately absent from the test runtime too.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("xaeroplus.XaeroPlus"));
        assertNull(XaeroPlusBridge.create());
    }

    @Test void unknownCacheEntriesDoNotBecomeOldOrFreshEvidence() {
        var unknown = new XaeroPlusChunkClassification(false, false, false, false);
        assertFalse(unknown.known());
        assertNull(unknown.chunk(3, 4, null, XaeroPlusBridge.Detection.EITHER, 0));
        assertFalse(unknown.selectedComplete(XaeroPlusBridge.Detection.EITHER, true, true));
    }

    @Test void inverseOldClassificationIsKnownButNeverImpliesFresh() {
        var modern = new XaeroPlusChunkClassification(false, true, false, false);
        var chunk = modern.chunk(-10, 20, null, XaeroPlusBridge.Detection.OLD_112, 500);
        assertNotNull(chunk);
        assertFalse(chunk.old112());
        assertFalse(chunk.nonFresh());
        assertFalse(chunk.fresh());
        assertEquals(500, chunk.receivedAtMillis());
        assertTrue(chunk.modern(), "1.12 detection reports the explicit modern result");
        assertTrue(modern.selectedComplete(XaeroPlusBridge.Detection.OLD_112, true, true));
        assertFalse(modern.chunk(-10, 20, null, XaeroPlusBridge.Detection.NON_FRESH, 500).modern(),
            "palette-only detection never reports OldChunks results");
    }

    @Test void explicitFreshEvidenceIsAvailableWithOldOnlyDetection() {
        var fresh = new XaeroPlusChunkClassification(false, true, false, true);
        var chunk = fresh.chunk(3, -4, null, XaeroPlusBridge.Detection.OLD_112, 0);
        assertNotNull(chunk);
        assertTrue(chunk.fresh());
        assertFalse(chunk.old112());
        assertFalse(chunk.nonFresh());
        assertEquals(0, chunk.receivedAtMillis());
        assertFalse(fresh.selectedPositive(XaeroPlusBridge.Detection.OLD_112));
    }

    @Test void selectedDetectionControlsTrailFlagsWithoutLosingFreshClassification() {
        var classified = new XaeroPlusChunkClassification(true, false, true, false);
        var oldOnly = classified.chunk(0, 0, null, XaeroPlusBridge.Detection.OLD_112, 1);
        assertTrue(oldOnly.old112());
        assertFalse(oldOnly.nonFresh());
        var paletteOnly = classified.chunk(0, 0, null, XaeroPlusBridge.Detection.NON_FRESH, 1);
        assertFalse(paletteOnly.old112());
        assertTrue(paletteOnly.nonFresh());
        assertTrue(classified.selectedPositive(XaeroPlusBridge.Detection.EITHER));
    }

    @Test void positiveEitherResultCanArriveBeforeOtherDetectorCompletes() {
        var old = new XaeroPlusChunkClassification(true, false, false, false);
        assertTrue(old.selectedPositive(XaeroPlusBridge.Detection.EITHER));
        assertFalse(old.selectedComplete(XaeroPlusBridge.Detection.EITHER, true, true));
        assertTrue(old.selectedComplete(XaeroPlusBridge.Detection.EITHER, true, false));
    }

    @Test void oldChunkConstructorDoesNotInventFreshEvidence() {
        var chunk = new XaeroPlusBridge.Chunk(1, 2, null, false, true, 100);
        assertTrue(chunk.nonFresh());
        assertFalse(chunk.fresh());
        assertEquals(100, chunk.receivedAtMillis());
    }
}
