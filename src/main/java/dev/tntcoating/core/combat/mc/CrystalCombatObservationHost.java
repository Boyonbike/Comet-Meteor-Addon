/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Vec;
import java.util.Objects;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Always-subscribed after-application observation path for spawns, removals and block updates.
 *
 * <p>The receipt path cannot produce spawn/removal observations: a placement binds to the crystal that
 * actually appeared, and Meteor only reports {@link EntityAddedEvent} after vanilla added the entity while
 * {@link EntityRemovedEvent} fires before the vanilla removal. This host converts those client-thread events
 * into fully formed observations and forwards them to the controller. It is subscribed for the module's
 * lifetime, not just while enabled, so the ledger keeps observing passively; since it is the only
 * always-subscribed object, it also forwards client ticks to keep receipt draining alive while disabled.
 * It never sends, never resolves a predicted id and never mutates risk.
 */
public final class CrystalCombatObservationHost {
    /** Controller-facing seam so the host is testable without a live session. */
    public interface Observer {
        ConnectionKey connection();

        void spawned(EntityKey key, Vec position, String entityType);

        void removed(EntityKey key);

        /** One applied block update: collision and placement candidates become stale. */
        default void blockUpdated(Cell cell) {
        }

        /** One applied block update with both block ids, for the ownership ledger. */
        default void blockUpdated(Cell cell, String oldBlock, String newBlock) {
            blockUpdated(cell);
        }

        /** One client tick. */
        void tick();
    }

    private static final String UNKNOWN_TYPE = "unknown";

    private final Observer observer;

    public CrystalCombatObservationHost(Observer observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        Entity entity = event.entity;
        if (entity == null || entity.isRemoved()) return;
        ConnectionKey connection = observer.connection();
        if (connection == null) return;
        observer.spawned(new EntityKey(connection, entity.getId(), entity.getUUID()),
            new Vec(entity.getX(), entity.getY(), entity.getZ()), entityType(entity));
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        Entity entity = event.entity;
        if (entity == null) return;
        ConnectionKey connection = observer.connection();
        if (connection == null) return;
        observer.removed(new EntityKey(connection, entity.getId(), entity.getUUID()));
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (event == null || event.pos == null) return;
        if (observer.connection() == null) return;
        observer.blockUpdated(new Cell(event.pos.getX(), event.pos.getY(), event.pos.getZ()),
            blockId(event.oldState), blockId(event.newState));
    }

    private static String blockId(BlockState state) {
        if (state == null) return "";
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "" : id.toString();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        observer.tick();
    }

    private static String entityType(Entity entity) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? UNKNOWN_TYPE : id.toString();
    }
}
