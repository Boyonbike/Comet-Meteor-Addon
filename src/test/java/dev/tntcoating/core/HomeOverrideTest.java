/*
 * Tests Comet's auto-set-home dialog matching.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeOverrideTest {
    @Test void matchesTitlesAndLabelsIgnoringCase() {
        assertTrue(HomeOverride.matches("Override home", "HOME"));
        assertTrue(HomeOverride.isHomeDialog("Confirm", List.of("Override the home 'test'?"), "home"));
    }

    @Test void ignoresBlankPhrasesAndUnrelatedDialogs() {
        assertFalse(HomeOverride.matches("Override home", "  "));
        assertFalse(HomeOverride.isHomeDialog("Override home", List.of(), " "));
        assertFalse(HomeOverride.isHomeDialog("Set spawn", List.of("Set your spawn here."), "home"));
    }

    @Test void choosesOnlyTheMatchingButton() {
        assertTrue(HomeOverride.isOverrideButton("Override", "override"));
        assertFalse(HomeOverride.isOverrideButton("Cancel", "override"));
    }

    @Test void buildsTheServerCommandWithoutASlash() {
        assertEquals("sethome test", HomeOverride.command(" test "));
        assertThrows(IllegalArgumentException.class, () -> HomeOverride.command("  "));
    }
}
