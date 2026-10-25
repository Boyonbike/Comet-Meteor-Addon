/*
 * Original Comet implementation by Boyonabike.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalGuardTest {
    @Test
    void blocksThePortalTypesThatAreEnabled() {
        assertTrue(PortalGuard.shouldBlock(true, false, true, false));
        assertTrue(PortalGuard.shouldBlock(false, true, false, true));
        assertFalse(PortalGuard.shouldBlock(true, false, false, true));
        assertFalse(PortalGuard.shouldBlock(false, true, true, false));
        assertFalse(PortalGuard.shouldBlock(false, false, true, true));
    }

    @Test
    void keepsAnAnchorInsideTheConfiguredRange() {
        assertTrue(PortalGuard.anchorUsable(0.0, 16.0));
        assertTrue(PortalGuard.anchorUsable(255.999, 16.0));
        assertTrue(PortalGuard.anchorUsable(256.0, 16.0));
    }

    @Test
    void rejectsAStaleAnchor() {
        assertFalse(PortalGuard.anchorUsable(256.001, 16.0));
        assertFalse(PortalGuard.anchorUsable(Double.NaN, 16.0));
        assertFalse(PortalGuard.anchorUsable(1.0, 0.0));
        assertFalse(PortalGuard.anchorUsable(1.0, -1.0));
    }
}
