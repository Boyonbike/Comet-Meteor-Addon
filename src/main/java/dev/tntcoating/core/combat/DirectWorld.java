/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.List;

/**
 * One client-thread snapshot of everything the DirectOnly planner may read. The Minecraft-facing reader
 * builds this; the pure planner never touches {@code Minecraft}. Implementations must be read-only.
 */
public interface DirectWorld {
    ConnectionKey connection();

    SafetyState safetyState();

    EntityKey self();

    Vec eye();

    Vec selfPosition();

    List<DirectTarget> targets();

    List<DirectCrystal> crystals();

    List<DirectItem> hotbar();

    int selectedSlot();

    /** True while a Weakness effect is active. */
    boolean weakness();

    DirectBlocks blocks();

    DirectDamage damage();

    /**
     * Read-only mitigation view for an entity, used by the Phase 5 trap planner. Null means unknown; the
     * caller must fail closed rather than assume an armour state.
     */
    default MitigationView mitigation(EntityKey entity) {
        return null;
    }

    /** Reach and line-of-sight check for attacking a crystal, including the configured walls allowance. */
    boolean crystalReachable(Vec crystalPosition, Box crystalBox, double attackRange, double wallsRange);

    /** Reach check for placing a crystal in the cell (interaction distance to the base's top face). */
    boolean placementReachable(Cell crystalCell, double placeRange);

    boolean visible(Vec eye, Box box);

    boolean eating();

    boolean drinking();

    boolean mining();

    boolean screenOpen();

    boolean baritoneActive();

    /** Server tick rate estimate, or a value below the configured floor when stale. */
    double tps();

    /** Nanoseconds since the last server time/feedback packet. */
    long nanosSinceLastFeedback();

    /**
     * Phase 4.2 motion seam: the latest motion sample for a tracked entity, or {@code null} when motion is
     * unknown. Returning {@code null} keeps prediction off for that entity; the default keeps Phase 3
     * behaviour for every world that does not track motion.
     */
    default MotionSample motion(EntityKey entity) {
        return null;
    }

    /**
     * Phase 4.2 collision seam used to bound a predicted reachable set. The default is open space, which
     * can only over-approximate motion; worlds that can read block collision shapes should override it.
     */
    default CollisionView collision() {
        return CollisionView.open();
    }
}
