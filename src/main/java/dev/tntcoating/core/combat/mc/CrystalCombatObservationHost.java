/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
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

/**
 * Always-subscribed after-application observation path for spawns and removals.
 *
 * <p>The packet-receipt path in {@link ObservationIntake} cannot produce these events yet: Meteor only
 * announces an entity after vanilla applied the add/remove packet, and the ledger needs that applied
 * identity to bind a sent placement to the crystal that actually appeared. This host converts Meteor's
 * client-thread {@link EntityAddedEvent}/{@link EntityRemovedEvent} into fully-formed observations and hands
 * them to {@link CrystalCombatController#observeSpawn}/{@link CrystalCombatController#observeRemoval}.
 *
 * <p>It is subscribed once in the module constructor and deliberately not tied to module activation, so the
 * ledger keeps observing passively while CrystalCombat is disabled. The same lifetime is why it also forwards
 * client ticks to {@link Observer#tick()}: while the module is disabled it is the only always-subscribed
 * object that can keep draining receipts. It never sends and never mutates risk: it only resolves identity,
 * position and type on the client thread. Events are ignored when no stack exists, no session is open, or the
 * entity cannot be resolved.
 */
public final class CrystalCombatObservationHost {
    /** The controller-facing seam, so the host stays testable without a live session. */
    public interface Observer {
        /** The connection epoch observations currently belong to, or {@code null} when there is none. */
        ConnectionKey connection();

        void spawned(EntityKey key, Vec position, String entityType);

        void removed(EntityKey key);

        /** One applied block update: block-collision and placement candidates become stale. */
        default void blockUpdated(Cell cell) {
        }

        /** One client tick; the module may publish the status when enabled and must not when disabled. */
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
        EntityKey key = new EntityKey(connection, entity.getId(), entity.getUUID());
        observer.spawned(key, new Vec(entity.getX(), entity.getY(), entity.getZ()), entityType(entity));
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
        observer.blockUpdated(new Cell(event.pos.getX(), event.pos.getY(), event.pos.getZ()));
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
