/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared hook layer is the one Phase 2b reuses; it must deliver to every installed listener so the
 * recorder can stay attached beside the runtime.
 */
class ObservationHooksTest {
    private static final class Counter implements ObservationListener {
        private int acks;

        @Override
        public void afterBlockChangedAck(ClientboundBlockChangedAckPacket packet) {
            acks++;
        }
    }

    private final Counter first = new Counter();
    private final Counter second = new Counter();

    @AfterEach
    void uninstallAll() {
        ObservationHooks.uninstall(first);
        ObservationHooks.uninstall(second);
    }

    @Test
    void everyInstalledListenerReceivesTheHook() {
        ObservationHooks.install(first);
        ObservationHooks.install(second);
        assertTrue(ObservationHooks.installed());

        ObservationHooks.afterBlockChangedAck(null);

        assertEquals(1, first.acks);
        assertEquals(1, second.acks);
    }

    @Test
    void uninstallRemovesOnlyThatListener() {
        ObservationHooks.install(first);
        ObservationHooks.install(second);

        ObservationHooks.uninstall(first);
        ObservationHooks.afterBlockChangedAck(null);

        assertEquals(0, first.acks);
        assertEquals(1, second.acks);
        assertTrue(ObservationHooks.installed());
    }

    @Test
    void noListenerMeansNoWork() {
        assertFalse(ObservationHooks.installed());
        ObservationHooks.afterBlockChangedAck(null);
        assertEquals(0, first.acks);
        assertEquals(0, second.acks);
    }
}
