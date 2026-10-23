/*
 * Tests Comet's dub-counter adaptation from Majkey's DubCounter, with permission.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DubCountTest {
    @Test void pairsAreWholeDubsAndAnUnpairedChestIsHalf() {
        assertEquals("0", DubCount.format(0));
        assertEquals("0.5", DubCount.format(1));
        assertEquals("1", DubCount.format(2));
        assertEquals("12.5", DubCount.format(25));
        assertEquals("1073741823.5", DubCount.format(Integer.MAX_VALUE));
    }
}
