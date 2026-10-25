package dev.tntcoating.core.scaffold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScaffoldOwnershipTest {
    @Test void lastEnabledWinsAndNestedDisableDoesNotReviveRival() {
        var ownership = new ScaffoldOwnership();
        assertEquals(ScaffoldOwnership.Disable.NONE, ownership.changed(false, true));
        assertFalse(ownership.buildersPaused());
        long before = ownership.generation();
        assertEquals(ScaffoldOwnership.Disable.LEGACY, ownership.changed(true, true));
        assertTrue(ownership.generation() > before);
        assertEquals(ScaffoldOwnership.Disable.NONE, ownership.changed(true, false));
        assertTrue(ownership.buildersPaused());
        assertEquals(ScaffoldOwnership.Disable.COMET, ownership.changed(true, true));
        assertEquals(ScaffoldOwnership.Disable.NONE, ownership.changed(false, true));
        assertEquals(ScaffoldOwnership.Disable.NONE, ownership.changed(false, false));
        assertFalse(ownership.buildersPaused());
    }
    @Test void unrelatedModuleEventsDoNotInvalidateReservations() {
        var ownership = new ScaffoldOwnership();
        ownership.changed(true, false);
        long generation = ownership.generation();
        ownership.changed(true, false);
        assertEquals(generation, ownership.generation());
        ownership.changed(false, false);
        assertTrue(ownership.generation() > generation);
    }
}
