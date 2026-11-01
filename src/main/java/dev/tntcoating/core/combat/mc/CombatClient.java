/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.Snapshot;
import dev.tntcoating.core.combat.Vec;
import java.util.List;
import net.minecraft.world.level.Level;

/**
 * Read-only view of the Minecraft client state the combat runtime is allowed to consult. Every method is
 * a query: the runtime never mutates world state through this interface, which keeps the dispatch
 * revalidation and the observation intake testable without a game client.
 *
 * <p>All methods except the network-receipt hooks are client-thread only; {@link #onClientThread()} gates
 * them. Production implementation: {@link MinecraftCombatClient}.
 */
public interface CombatClient {
    /** Whether the calling thread is the Minecraft client thread. */
    boolean onClientThread();

    /** Whether the client currently has a connected server connection. */
    boolean connectionOpen();

    /** The current client level, or {@code null} outside a world. Used only for packet entity resolution. */
    Level level();

    /** The local player's identity in the current connection epoch, or {@code null} outside a world. */
    EntityKey selfKey();

    /** The current client entity for a numeric id, with UUID resolved from the world, or {@code null}. */
    EntityKey entity(int id);

    /** Registry id of an entity type, for example {@code minecraft:end_crystal}; empty when absent. */
    String entityType(EntityKey entity);

    /** The single end crystal within {@code tolerance} of {@code position}, or {@code null} if none/many. */
    EntityKey crystalAt(Vec position, double tolerance);

    /** Current applied dimension identifier, for example {@code minecraft:overworld}. */
    String dimensionId();

    /** Every loaded end crystal, as a position snapshot stamped by the caller's revision. */
    List<Snapshot.Crystal> crystals();

    /** Local player eye position. */
    Vec eyePosition();

    /** Position of a verified entity; {@code null} when absent. */
    Vec entityPosition(EntityKey entity);

    /** Bounding box of a verified entity; {@code null} when absent. */
    Box entityBox(EntityKey entity);

    /** Whether the entity is still present with the same UUID in the current world. */
    boolean entityPresent(EntityKey entity);

    /** The platform's real block interaction range for the local player. */
    double blockInteractionRange();

    /**
     * Phase 4.6: whether a blocker/support may be placed on top of {@code base}. Read-only: the same
     * {@code isFaceSturdy} fact vanilla placement uses. Never mutates the world.
     */
    boolean supportBelow(Cell base);

    /** Registry id at the cell, or {@code null}/air when empty. Default is for lightweight fakes. */
    default String blockId(Cell cell) {
        return null;
    }

    /** True when a block may be placed into the cell. Default fails open for old fakes only. */
    default boolean replaceable(Cell cell) {
        // Fail closed: a client that cannot answer must never authorise a structural placement.
        return false;
    }

    /** True when no entity bounding box overlaps the cell. */
    default boolean entityFree(Cell cell) {
        return false;
    }

    /** Observed piston facing, or {@code null} when the cell does not hold a piston. */
    default dev.tntcoating.core.combat.PistonFacing pistonFacing(Cell cell) {
        return null;
    }

    /** True when the piston at the cell is powered. */
    default boolean pistonPowered(Cell cell) {
        return false;
    }

    /** The platform's real entity interaction range for the local player. */
    double entityInteractionRange();

    /** Whether {@code eye} can see the target box (vanilla block clip). */
    boolean visible(Vec eye, Box target);

    /** Current player yaw in degrees. */
    double yaw();

    /** Current player pitch in degrees. */
    double pitch();

    /** Whether the current player rotation is close enough to the requested aim. */
    boolean rotationSatisfied(double targetYaw, double targetPitch);

    /**
     * Requests a Meteor rotation. Must only be called from inside dispatch; the callback runs after the
     * rotation has been applied to the outgoing movement packets.
     *
     * @return whether the request was accepted
     */
    boolean requestRotation(double targetYaw, double targetPitch, Runnable afterRotate);

    /** Whether any screen (container, chat, pause, ...) is open. */
    boolean screenOpen();

    /** Currently selected hotbar slot, 0-8. */
    int selectedSlot();

    /** Registry id of the item in a hotbar slot, or empty string. */
    String hotbarItem(int slot);

    /** Registry id of the offhand item, or empty string. Never mutated by combat. */
    String offhandItem();

    /** Selects a hotbar slot client-side; callers send the server packet explicitly through the sink. */
    void selectHotbarSlot(int slot);

    /** The connection epoch of the current world, or the last one before a disconnect. */
    ConnectionKey connection();
}
