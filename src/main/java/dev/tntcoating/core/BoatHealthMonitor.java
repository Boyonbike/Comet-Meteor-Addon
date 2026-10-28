/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

/** Connection threads publish samples; only the game thread chooses the active connection. */
public final class BoatHealthMonitor {
    private record Sample(double health, boolean known, boolean dead) {}
    private final java.util.Map<Object, Sample> pending = new java.util.IdentityHashMap<>();
    private Object connection;
    private boolean known, dead;
    private double health;
    public synchronized void clear() { connection = null; known = dead = false; pending.clear(); }
    public synchronized void begin(Object current) {
        if (connection == current) return;
        connection = current; known = dead = false;
        Sample early = pending.remove(current);
        if (early != null) { health = early.health(); known = early.known(); dead = early.dead(); }
        pending.clear();
    }
    public synchronized void health(Object source, double value) {
        if (source == null || !Double.isFinite(value) || value < 0) return;
        if (source == connection) { health = value; known = true; return; }
        Sample previous = pending.get(source);
        store(source, new Sample(value, true, previous != null && previous.dead()));
    }
    public synchronized void death(Object source) {
        if (source == null) return;
        if (source == connection) { dead = true; return; }
        Sample previous = pending.get(source);
        store(source, new Sample(previous == null ? 20 : previous.health(), previous != null && previous.known(), true));
    }
    private void store(Object source, Sample sample) {
        if (pending.size() >= 2 && !pending.containsKey(source)) pending.clear();
        pending.put(source, sample);
    }
    public synchronized boolean dead() { return dead; }
    public synchronized boolean lowHealth(double playerHealth, double minimum) {
        return minimum > 0 && (known ? health : playerHealth) <= minimum;
    }
    public synchronized double currentHealth(double playerHealth) { return known ? health : playerHealth; }
}
