package dev.tntcoating.core;

/** Low durability is a flight safety condition, including landing and blocked inventory. */
public final class HighwayFlightSafety {
    public enum Action { CONTINUE, REPLACE, LOGOUT }
    private HighwayFlightSafety() {}
    public static Action action(int remaining, int threshold, boolean swap, boolean inventorySafe) {
        if (remaining > Math.max(2, threshold)) return Action.CONTINUE;
        return swap && inventorySafe && remaining >= 0 ? Action.REPLACE : Action.LOGOUT;
    }
}
