/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

/** An eating animation is not success: require consumption or improved vitals within the deadline. */
public final class BoatEatingGuard {
    private boolean needed;
    private long since;
    private int previousFood, previousHunger;
    private double previousHealth;
    public void reset() { needed = false; }
    public String evaluate(long now, boolean demand, boolean accessible, int food, double health, int hunger, int timeoutSeconds) {
        if (!demand || timeoutSeconds <= 0) { reset(); return null; }
        if (!needed || food < previousFood || health > previousHealth || hunger > previousHunger) since = now;
        needed = true; previousFood = food; previousHealth = health; previousHunger = hunger;
        if (now - since < timeoutSeconds * 1_000_000_000L) return null;
        return accessible ? "Eating did not consume food or restore health/hunger in time."
            : "AutoEat cannot access usable food; check the hotbar, offhand and inventory.";
    }
}
