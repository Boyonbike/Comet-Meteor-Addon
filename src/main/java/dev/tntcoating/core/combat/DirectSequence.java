/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;

/**
 * Phase 3.4 placement/attack sequence. One sequence owns at most one spawn hazard, one placement and one
 * crystal risk; every transition is guarded so a tick path and an event path cannot drive the same action
 * twice.
 *
 * <p>The engine reserves the spawn hazard and the crystal risk before the placement packet may leave, then
 * calls {@link #beginPlacement}. A unique matching spawn reported by the ledger calls {@link #bind}; the
 * attack is proposed in the same client-thread task but this class only records that it was sent. A removal
 * or explosion moves to {@link State#REMOVAL_OBSERVED}; the engine retires the sequence and the risk follows
 * the Phase 2 safety model. Any transition may abort; aborted sequences are reset explicitly so a late
 * event can never start a second action for the same spawn.</p>
 */
public final class DirectSequence {
    public enum State {
        IDLE,
        PLACE_SENT,
        SPAWN_OBSERVED,
        ATTACK_SENT,
        REMOVAL_OBSERVED,
        ABORTED
    }

    private State state = State.IDLE;
    private Cell cell;
    private HazardId hazard;
    private PlacementId placement;
    private RiskId risk;
    private EntityKey entity;
    private Vec position;
    private int placeSentTick = -1;
    private int spawnObservedTick = -1;
    private int lastAttackTick = -1;
    private int removalTick = -1;
    private int attacks;
    private String abortReason;

    public State state() {
        return state;
    }

    public boolean isIdle() {
        return state == State.IDLE;
    }

    public boolean isAborted() {
        return state == State.ABORTED;
    }

    public boolean active() {
        return state == State.PLACE_SENT || state == State.SPAWN_OBSERVED || state == State.ATTACK_SENT;
    }

    public Cell cell() {
        return cell;
    }

    public HazardId hazard() {
        return hazard;
    }

    public PlacementId placement() {
        return placement;
    }

    public RiskId risk() {
        return risk;
    }

    public EntityKey entity() {
        return entity;
    }

    public Vec position() {
        return position;
    }

    public int placeSentTick() {
        return placeSentTick;
    }

    public int spawnObservedTick() {
        return spawnObservedTick;
    }

    public int lastAttackTick() {
        return lastAttackTick;
    }

    public int removalTick() {
        return removalTick;
    }

    public int attacks() {
        return attacks;
    }

    public String abortReason() {
        return abortReason;
    }

    /** True only while this sequence owns the given crystal entity. */
    public boolean owns(EntityKey crystal) {
        return crystal != null && crystal.equals(entity)
            && (state == State.SPAWN_OBSERVED || state == State.ATTACK_SENT);
    }

    /**
     * Records that the placement packet was accepted. The caller must have reserved the spawn hazard, the
     * placement marker and the crystal risk first.
     */
    public boolean beginPlacement(Cell cell, HazardId hazard, PlacementId placement, RiskId risk, int tick) {
        if (state != State.IDLE) return false;
        if (cell == null || hazard == null || placement == null || risk == null) return false;
        if (tick < 0) return false;
        this.state = State.PLACE_SENT;
        this.cell = cell;
        this.hazard = hazard;
        this.placement = placement;
        this.risk = risk;
        this.placeSentTick = tick;
        this.entity = null;
        this.position = null;
        this.spawnObservedTick = -1;
        this.lastAttackTick = -1;
        this.removalTick = -1;
        this.attacks = 0;
        this.abortReason = null;
        return true;
    }

    /**
     * Binds the single unique spawn the ledger attributed to this sequence's hazard. A second spawn or a
     * bind before a placement is refused, so a duplicate hook can never re-target the sequence.
     */
    public boolean bind(EntityKey crystal, Vec position) {
        if (state != State.PLACE_SENT || entity != null) return false;
        if (crystal == null || position == null) return false;
        if (hazard == null) return false;
        this.entity = crystal;
        this.position = position;
        this.state = State.SPAWN_OBSERVED;
        return true;
    }

    /** Records an accepted attack packet for this sequence's own crystal. */
    public boolean attackSent(EntityKey crystal, int tick) {
        if (state != State.SPAWN_OBSERVED && state != State.ATTACK_SENT) return false;
        if (crystal == null || entity == null || !entity.equals(crystal)) return false;
        if (tick < 0) return false;
        this.state = State.ATTACK_SENT;
        this.lastAttackTick = tick;
        this.attacks++;
        return true;
    }

    /** Terminal evidence for the bound crystal: removal, tracking loss or explosion. */
    public boolean removalObserved(EntityKey crystal, int tick) {
        if (state != State.SPAWN_OBSERVED && state != State.ATTACK_SENT) return false;
        if (crystal == null || entity == null || !entity.equals(crystal)) return false;
        this.state = State.REMOVAL_OBSERVED;
        this.removalTick = tick;
        return true;
    }

    /** Consumes the terminal state, allowing the next placement. */
    public boolean finish() {
        if (state != State.REMOVAL_OBSERVED) return false;
        clear();
        return true;
    }

    /** Any state may abort; the hazard and sent risk stay with the ledger/risk budget. */
    public boolean abort(String reason) {
        if (state == State.IDLE || state == State.ABORTED || state == State.REMOVAL_OBSERVED) {
            return false;
        }
        this.state = State.ABORTED;
        this.abortReason = reason;
        return true;
    }

    /** Operator/engine reset after an abort. The next placement requires a fresh reservation. */
    public boolean reset() {
        if (state != State.ABORTED) return false;
        clear();
        return true;
    }

    private void clear() {
        state = State.IDLE;
        cell = null;
        hazard = null;
        placement = null;
        risk = null;
        entity = null;
        position = null;
        placeSentTick = -1;
        spawnObservedTick = -1;
        lastAttackTick = -1;
        removalTick = -1;
        attacks = 0;
        abortReason = null;
    }

    @Override
    public String toString() {
        return "DirectSequence{" + state
            + (entity == null ? "" : ", entity=" + entity)
            + (cell == null ? "" : ", cell=" + cell)
            + (abortReason == null ? "" : ", reason=" + abortReason) + '}';
    }

    /** Internal invariant used by tests: an active sequence always has a hazard and a risk. */
    public boolean consistent() {
        if (state == State.IDLE) return cell == null && hazard == null && risk == null && entity == null;
        if (state == State.ABORTED) return true;
        return Objects.nonNull(hazard) && Objects.nonNull(risk) && Objects.nonNull(cell)
            && (state == State.PLACE_SENT || entity != null);
    }
}
