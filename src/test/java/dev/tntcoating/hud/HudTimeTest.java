package dev.tntcoating.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudTimeTest {
    @Test
    void formatsSecondsMinutesAndHours() {
        assertEquals("0s", HudTime.duration(0));
        assertEquals("9s", HudTime.duration(9_900_000_000L));
        assertEquals("1m 05s", HudTime.duration(65_000_000_000L));
        assertEquals("1h 02m 03s", HudTime.duration(3_723_000_000_000L));
    }

    @Test
    void negativeDurationsClampToZero() {
        assertEquals("0s", HudTime.duration(-1));
    }
}
