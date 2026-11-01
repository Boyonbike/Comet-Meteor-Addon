/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnarchyQuietWindowTest {
    @Test
    void disabledWindowNeverRecordsOrLogs() {
        AnarchyQuietWindow window = AnarchyQuietWindow.disabled();
        assertFalse(window.enabled());
        window.recordRetirement("retire");
        window.recordMiss("miss");
        assertEquals(0, window.retirements());
        assertEquals(0, window.misses());
    }

    @Test
    void statisticalWindowLogsEveryRetirementAndMiss() {
        List<String> log = new ArrayList<>();
        AnarchyQuietWindow window = AnarchyQuietWindow.statistical(40L, log::add);
        assertTrue(window.enabled());
        assertEquals(40L, window.quietTicks());
        window.recordRetirement("STATISTICAL_RETIREMENT risk=1");
        window.recordMiss("STATISTICAL_MISS entity=2");
        assertEquals(1, window.retirements());
        assertEquals(1, window.misses());
        assertEquals(List.of("STATISTICAL_RETIREMENT risk=1", "STATISTICAL_MISS entity=2"), log);
    }

    @Test
    void statisticalFactoryRequiresAPositiveWindowAndSink() {
        assertThrows(IllegalArgumentException.class,
            () -> AnarchyQuietWindow.statistical(0L, message -> { }));
        assertThrows(NullPointerException.class,
            () -> AnarchyQuietWindow.statistical(40L, null));
        assertThrows(IllegalArgumentException.class,
            () -> new AnarchyQuietWindow(false, -1L, null));
    }

    @Test
    void defaultQuietWindowIsFortyTicks() {
        assertEquals(40L, AnarchyQuietWindow.DEFAULT_QUIET_TICKS);
    }
}
