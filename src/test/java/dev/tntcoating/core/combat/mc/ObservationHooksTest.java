/*
 * Original Comet implementation by Boyonabike.
 * Independent: written against the project's own interfaces; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tntcoating.core.combat.mc.ObservationHooks;
import dev.tntcoating.core.combat.mc.ObservationListener;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ObservationHooksTest {
    @AfterEach
    void clear() {
        for (ObservationListener listener : ObservationHooks.installed()) {
            ObservationHooks.uninstall(listener);
        }
    }

    @Test
    void installRejectsNullAndDeduplicates() {
        assertThrows(IllegalArgumentException.class, () -> ObservationHooks.install(null));
        ObservationListener listener = new ObservationListener() {
        };
        ObservationHooks.install(listener);
        ObservationHooks.install(listener);
        assertEquals(1, ObservationHooks.installed().size());
        assertSame(listener, ObservationHooks.installed().get(0));
        ObservationHooks.uninstall(listener);
        assertTrue(ObservationHooks.installed().isEmpty());
    }

    @Test
    void everyHookReachesEveryListener() {
        AtomicInteger calls = new AtomicInteger();
        ObservationListener listener = new ObservationListener() {
            @Override
            public void beforePacketRead(Connection connection, Packet<?> packet) {
                calls.incrementAndGet();
            }

            @Override
            public void afterPacketRead(Connection connection, Packet<?> packet) {
                calls.incrementAndGet();
            }

            @Override
            public void afterSetHealth(net.minecraft.network.protocol.game.ClientboundSetHealthPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterSetEntityData(
                net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterUpdateMobEffect(
                net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterRemoveMobEffect(
                net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterSetEquipment(
                net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterDamageEvent(
                net.minecraft.network.protocol.game.ClientboundDamageEventPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterExplosion(net.minecraft.network.protocol.game.ClientboundExplodePacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterPlayerPosition(
                net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterRespawn(net.minecraft.network.protocol.game.ClientboundRespawnPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterBlockChangedAck(ClientboundBlockChangedAckPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterBlockEvent(net.minecraft.network.protocol.game.ClientboundBlockEventPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterEntityEvent(
                net.minecraft.network.protocol.game.ClientboundEntityEventPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterMoveEntity(net.minecraft.network.protocol.game.ClientboundMoveEntityPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterTeleportEntity(
                net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket p) {
                calls.incrementAndGet();
            }

            @Override
            public void afterEntityPositionSync(
                net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket p) {
                calls.incrementAndGet();
            }
        };
        ObservationHooks.install(listener);
        ObservationHooks.beforePacketRead(null, null);
        ObservationHooks.afterPacketRead(null, null);
        ObservationHooks.afterSetHealth(null);
        ObservationHooks.afterSetEntityData(null);
        ObservationHooks.afterUpdateMobEffect(null);
        ObservationHooks.afterRemoveMobEffect(null);
        ObservationHooks.afterSetEquipment(null);
        ObservationHooks.afterDamageEvent(null);
        ObservationHooks.afterExplosion(null);
        ObservationHooks.afterPlayerPosition(null);
        ObservationHooks.afterRespawn(null);
        ObservationHooks.afterBlockChangedAck(null);
        ObservationHooks.afterBlockEvent(null);
        ObservationHooks.afterEntityEvent(null);
        ObservationHooks.afterMoveEntity(null);
        ObservationHooks.afterTeleportEntity(null);
        ObservationHooks.afterEntityPositionSync(null);
        assertEquals(17, calls.get());
    }

    @Test
    void oneThrowingListenerDoesNotStarveTheOthers() {
        AtomicInteger reached = new AtomicInteger();
        ObservationHooks.install(new ObservationListener() {
            @Override
            public void afterBlockChangedAck(ClientboundBlockChangedAckPacket packet) {
                throw new IllegalStateException("listener bug");
            }
        });
        ObservationHooks.install(new ObservationListener() {
            @Override
            public void afterBlockChangedAck(ClientboundBlockChangedAckPacket packet) {
                reached.incrementAndGet();
            }
        });
        ObservationHooks.afterBlockChangedAck(null);
        assertEquals(1, reached.get());
        assertFalse(ObservationHooks.installed().isEmpty());
    }
}
