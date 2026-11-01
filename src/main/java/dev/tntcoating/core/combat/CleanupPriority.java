/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Phase 6.5 cleanup starvation hook.
 *
 * <p>Plan 6.5 says cleanup intents share the budgets and cannot be starved: after
 * {@value #STARVATION_TICKS} server ticks of waiting a cleanup gets the next free slot. This class is that
 * scheduling state and nothing else. It counts server ticks (never wall clock), it never sends, and it
 * never retires or spends risk — the trap's hazards and risk reservations stay exactly where Phase 5 left
 * them.</p>
 *
 * <p>The cleanup-intent origin is the piston trap's beta auto-cleanup stage (Stage 4 part B): while the
 * trap still has owned debris it asks here for its turn, and once this state is due the engine yields the
 * next free slot so the trap can dispatch a vanilla block break for {@link Action#MINE_POWER},
 * {@link Action#MINE_PISTON} or {@link Action#MINE_SUPPORT}. The trap reserves that packet through a
 * packet-only scheduler lease; this class only decides when cleanup may compete. A due wait is not
 * prioritised while the installed feasibility probe says the reservation would be refused, so the engine
 * never yields a tick that cleanup cannot use.</p>
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
    private BooleanSupplier feasibility;

    /**
     * Installs the reservation probe used by {@link #prioritise()}, or {@code null} to restore the default
     * (always feasible). The probe runs on the client thread under this object's lock, must be a pure
     * query, and must never send, retire risk or mutate scheduler state. It returns false when the
     * cleanup packet could not actually be reserved right now (budget, slot or target invalid), which
     * withholds the engine yield without ending the wait.
     */
    public synchronized void feasibility(BooleanSupplier probe) {
        this.feasibility = probe;
    }

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
     * Claims the next free slot for cleanup. Returns true only while a wait is due and the installed
     * feasibility probe allows the claim; the engine then yields and the trap dispatches its packet-only
     * cleanup break. The claim is a pure query: it is not consumed here and never reserves anything.
     */
    public synchronized boolean prioritise() {
        if (!waiting || !due) return false;
        BooleanSupplier probe = feasibility;
        return probe == null || probe.getAsBoolean();
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
