/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 1.21.11 artifact and Meteor build 82 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat.mc;

import dev.tntcoating.core.combat.Box;
import dev.tntcoating.core.combat.Cell;
import dev.tntcoating.core.combat.ConnectionKey;
import dev.tntcoating.core.combat.EntityKey;
import dev.tntcoating.core.combat.PistonFacing;
import dev.tntcoating.core.combat.Snapshot;
import dev.tntcoating.core.combat.Vec;
import java.util.List;
import net.minecraft.world.level.Level;

/**
 * Read-only Minecraft facade for the runtime. Every method answers one client-thread question about the
 * current world; nothing here sends or mutates. Defaults fail closed: unknown block/occupation facts report
 * "not usable" so a conservative planner cannot place into a cell it could not read.
 */
public interface CombatClient {
    boolean onClientThread();

    boolean connectionOpen();

    Level level();

    EntityKey selfKey();

    EntityKey entity(int id);

    String entityType(EntityKey entity);

    EntityKey crystalAt(Vec position, double tolerance);

    String dimensionId();

    List<Snapshot.Crystal> crystals();

    Vec eyePosition();

    Vec entityPosition(EntityKey entity);

    Box entityBox(EntityKey entity);

    boolean entityPresent(EntityKey entity);

    double blockInteractionRange();

    /** Tests the top face of the supplied support block; callers pass placementCell.below(). */
    boolean supportBelow(Cell cell);

    /** Registry id of the block, or {@code null} when the cell is air/unreadable. */
    default String blockId(Cell cell) {
        return null;
    }

    /** Air or a replaceable block; false when unknown. */
    default boolean replaceable(Cell cell) {
        return false;
    }

    /** No non-spectator entity box intersects the cell; false when unknown. */
    default boolean entityFree(Cell cell) {
        return false;
    }

    default PistonFacing pistonFacing(Cell cell) {
        return null;
    }

    default boolean pistonPowered(Cell cell) {
        return false;
    }

    double entityInteractionRange();

    boolean visible(Vec eye, Box target);

    double yaw();

    double pitch();

    boolean rotationSatisfied(double yaw, double pitch);

    boolean requestRotation(double yaw, double pitch, Runnable afterRotate);

    boolean screenOpen();

    int selectedSlot();

    String hotbarItem(int slot);

    String offhandItem();

    void selectHotbarSlot(int slot);

    ConnectionKey connection();

    /** Health and absorption of a living entity as currently applied on the client. */
    record HealthPair(double health, double absorption) {
    }

    /**
     * The applied health/absorption pair of the living entity with this network id, read on the client
     * thread after the vanilla handler ran, or {@code null} when it is absent or not a living entity.
     */
    default HealthPair appliedHealth(int entityId) {
        return null;
    }
}
