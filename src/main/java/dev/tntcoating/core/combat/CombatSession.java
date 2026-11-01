/*
 * Original Comet implementation by Boyonabike.
 * Independent: written from the pinned 26.2 artifact and Meteor build 35 behaviour; no third-party code.
 * SPDX-License-Identifier: CC0-1.0
 */

package dev.tntcoating.core.combat;

/**
 * World epochs, config generations and the action generation. Pure: it has no runtime, packet or callback
 * dependency, so closing a session can never send or clean up anything.
 *
 * <p>Counters are monotonic for the life of the process. Every event that must stop in-flight work —
 * death, respawn, a config change, a dimension change and disable — ends the {@link ActionGeneration};
 * {@link #close()}, {@link #open()} and {@link #dimensionChanged()} also replace the
 * {@link ConnectionKey}.</p>
 *
 * <p>Client-thread use only. The engine calls these methods from the client tick and from client-thread
 * event tasks.</p>
 */
public final class CombatSession {
    private long nextConnection = 1L;
    private long nextWorld = 1L;
    private long nextConfig = 1L;
    private long nextGeneration = 1L;

    private ConnectionKey connection;
    private long world;
    private long config;
    private ActionGeneration generation;
    private boolean open;

    /** Opens a fresh connection and world epoch and starts a new action generation. */
    public WorldKey open() {
        connection = new ConnectionKey(nextConnection++);
        world = nextWorld++;
        config = nextConfig++;
        generation = new ActionGeneration(nextGeneration++);
        open = true;
        return new WorldKey(connection, world);
    }

    /**
     * Applies a config change: outstanding tickets are invalidated and in-flight actions end. Hazards and
     * sent risk are the ledger's and risk budget's to keep; this class only revokes authority.
     */
    public void reconfigure() {
        if (!open) return;
        config = nextConfig++;
        endActionGeneration();
    }

    /** Issues authority for one plan inside the current connection, action and config generation. */
    public Ticket ticket(long plan) {
        if (!open) throw new IllegalStateException("session is not open");
        return new Ticket(connection, generation, config, plan);
    }

    /** True only while every generation named by the ticket is still current. */
    public boolean valid(Ticket ticket) {
        return ticket != null && open
            && ticket.conn().equals(connection)
            && ticket.gen().equals(generation)
            && ticket.config() == config;
    }

    /** Closes the session. It performs no cleanup and never calls a runtime. */
    public void close() {
        open = false;
        generation = new ActionGeneration(nextGeneration++);
    }

    /** Death ends every in-flight action; crystals and structures stay in the world for a later rescan. */
    public void death() {
        if (open) endActionGeneration();
    }

    /** Respawn ends the previous generation; the ledger rescans surviving hazards before any new action. */
    public void respawn() {
        if (open) endActionGeneration();
    }

    /**
     * Applies a dimension change: the connection stays open but a new {@link ConnectionKey} epoch starts,
     * because numeric entity IDs and block positions in the old world mean nothing in the new one. Every
     * in-flight action ends. The ledger makes old-epoch hazards, ownership and crystals unaddressable.
     *
     * <p>Self debt carries across a dimension change: health is not reset, so sent risk does not retire.
     * The Phase 2 risk budget owns that accounting.</p>
     *
     * @return the new connection epoch
     */
    public ConnectionKey dimensionChanged() {
        if (!open) throw new IllegalStateException("session is not open");
        connection = new ConnectionKey(nextConnection++);
        world = nextWorld++;
        endActionGeneration();
        return connection;
    }

    /** Disable stops automatic work immediately; the generation check makes queued callbacks stale. */
    public void disable() {
        generation = new ActionGeneration(nextGeneration++);
    }

    public boolean isOpen() {
        return open;
    }

    public ConnectionKey connection() {
        return connection;
    }

    public ActionGeneration actionGeneration() {
        return generation;
    }

    public long config() {
        return config;
    }

    private void endActionGeneration() {
        generation = new ActionGeneration(nextGeneration++);
    }
}
