/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;
import java.util.Optional;

/**
 * Phase 6.5 cleanup starvation hook.
 *
 * <p>Plan 6.5 says cleanup intents share the budgets and cannot be starved: after
 * {@value #STARVATION_TICKS} server ticks of waiting a cleanup gets the next free slot. This class is that
 * scheduling state and nothing else. It counts server ticks (never wall clock), it never sends, and it
 * never retires or spends risk — the trap's hazards and risk reservations stay exactly where Phase 5 left
 * them.</p>
 *
 * <p><strong>Open gap:</strong> this build has no cleanup intent origin. {@link Action#MINE_POWER} and
 * {@link Action#MINE_PISTON} exist in the contract, but the runtime has no mining/packet path for them and
 * Phase 5.5 makes cleanup manual (vanilla cannot confirm that a cell is ours). So the hook can mark a
 * cleanup due, keep the next free slot for it and expose that state to the status string, but it cannot yet
 * reserve or dispatch a cleanup action. Nothing here pretends otherwise.</p>
 */
public final class CleanupPriority {
    /** Waiting ticks after which cleanup owns the next free slot. */
    public static final int STARVATION_TICKS = 20;

    /** The generation a wait belongs to; a wait never crosses a connection or generation boundary. */
    public record Deadline(ConnectionKey conn, ActionGeneration gen) {
        public Deadline {
            Objects.requireNonNull(conn, "conn");
            Objects.requireNonNull(gen, "gen");
        }
    }

    /** Immutable snapshot of the hook, safe to publish in the status string. */
    public record State(
        boolean waiting,
        boolean due,
        int waitedTicks,
        Optional<EntityKey> target,
        Optional<Deadline> deadline
    ) {
        public State {
            if (waitedTicks < 0) throw new IllegalArgumentException("waitedTicks must be non-negative");
            target = target == null ? Optional.empty() : target;
            deadline = deadline == null ? Optional.empty() : deadline;
        }

        public static State idle() {
            return new State(false, false, 0, Optional.empty(), Optional.empty());
        }
    }

    private boolean waiting;
    private boolean due;
    private int firstWaitTick = -1;
    private EntityKey target;
    private Deadline deadline;

    /** Starts (or keeps) the wait. Repeating the call for the same deadline does not reset the clock. */
    public synchronized void request(int serverTick, Deadline owner, EntityKey victim) {
        if (serverTick < 0) return;
        if (waiting) {
            if (Objects.equals(deadline, owner) && Objects.equals(target, victim)) return;
        }
        waiting = true;
        due = false;
        firstWaitTick = serverTick;
        deadline = owner;
        target = victim;
    }

    /** Advances the wait by one server tick. Returns true when cleanup is now due. */
    public synchronized boolean sample(int serverTick) {
        if (!waiting || serverTick < 0 || firstWaitTick < 0) return false;
        lastTick = serverTick;
        if (serverTick - firstWaitTick >= STARVATION_TICKS) due = true;
        return due;
    }

    /**
     * Claims the next free slot for cleanup. Returns true only while a wait is due; the engine records the
     * claim and, because no cleanup intent exists in this build, keeps naming the gap in its status.
     */
    public synchronized boolean prioritise() {
        return waiting && due;
    }

    /** Throws one completed/generation-ended cleanup wait away. Never touches risk or hazards. */
    public synchronized void clear() {
        waiting = false;
        due = false;
        firstWaitTick = -1;
        target = null;
        deadline = null;
    }

    public synchronized State state() {
        if (!waiting) return State.idle();
        int waited = firstWaitTick < 0 ? 0 : Math.max(0, lastTick - firstWaitTick);
        return new State(true, due, waited, Optional.ofNullable(target), Optional.ofNullable(deadline));
    }

    /** The newest server tick sampled, used only to report the waited-tick count. */
    private int lastTick = -1;

    /** Records the tick the state snapshot is taken at, so the status string can show the wait length. */
    public synchronized State state(int serverTick) {
        if (!waiting) return State.idle();
        if (serverTick >= 0) lastTick = serverTick;
        return state();
    }

    /** True when the wait belongs to another generation or connection and must not be carried over. */
    public synchronized boolean expired(ConnectionKey conn, ActionGeneration gen) {
        if (!waiting || deadline == null) return false;
        return !deadline.conn().equals(conn) || !deadline.gen().equals(gen);
    }
}
