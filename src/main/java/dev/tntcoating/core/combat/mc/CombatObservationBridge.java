/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import java.util.Objects;
import java.util.function.IntConsumer;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Applied-observation tap for per-target invalidation. Installed once with {@link ObservationHooks}, it
 * forwards only the identity of the entity whose movement, health, equipment or effects just changed. It
 * never plans, never sends, never mutates risk, and never reads the world beyond resolving a moved packet.
 * Every hook runs at handler RETURN, which is the client thread after vanilla applied the packet.
 */
public final class CombatObservationBridge implements ObservationListener {
    private final IntConsumer targetChanged;

    public CombatObservationBridge(IntConsumer targetChanged) {
        this.targetChanged = Objects.requireNonNull(targetChanged, "targetChanged");
    }

    @Override
    public void afterSetEntityData(ClientboundSetEntityDataPacket packet) {
        if (packet != null) targetChanged.accept(packet.id());
    }

    @Override
    public void afterUpdateMobEffect(ClientboundUpdateMobEffectPacket packet) {
        if (packet != null) targetChanged.accept(packet.getEntityId());
    }

    @Override
    public void afterRemoveMobEffect(ClientboundRemoveMobEffectPacket packet) {
        if (packet != null) targetChanged.accept(packet.entityId());
    }

    @Override
    public void afterSetEquipment(ClientboundSetEquipmentPacket packet) {
        if (packet != null) targetChanged.accept(packet.getEntity());
    }

    @Override
    public void afterDamageEvent(ClientboundDamageEventPacket packet) {
        if (packet != null) targetChanged.accept(packet.entityId());
    }

    @Override
    public void afterMoveEntity(ClientboundMoveEntityPacket packet) {
        if (packet == null) return;
        Level level = MeteorClient.mc == null ? null : MeteorClient.mc.level;
        if (level == null) return;
        // T1 move packets expose no id accessor; resolve through the already-applied world.
        Entity entity = packet.getEntity(level);
        if (entity != null) targetChanged.accept(entity.getId());
    }

    @Override
    public void afterTeleportEntity(ClientboundTeleportEntityPacket packet) {
        if (packet != null) targetChanged.accept(packet.id());
    }

    @Override
    public void afterEntityPositionSync(ClientboundEntityPositionSyncPacket packet) {
        if (packet != null) targetChanged.accept(packet.id());
    }
}
