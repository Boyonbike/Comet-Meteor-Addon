package dev.tntcoating.core;

/** Enforces wing durability during flight, while allowing armor during recovery walks. */
public final class HighwayFlightSafety {
    public enum Action { CONTINUE, REPLACE, LOGOUT }
    private HighwayFlightSafety() {}
    public static Action action(int remaining, int threshold, boolean swap, boolean inventorySafe) {
        return action(remaining, threshold, swap, inventorySafe, true);
    }

    public static Action action(int remaining, int threshold, boolean swap, boolean inventorySafe, boolean needsWings) {
        if (!needsWings || remaining > Math.max(2, threshold)) return Action.CONTINUE;
        return swap && inventorySafe ? Action.REPLACE : Action.LOGOUT;
    }

    /** Landing retains wings until touchdown; an established walk can jump or drop in armor. */
    public static boolean requiresWings(boolean walking, boolean walkStarted, boolean onGround) {
        return !walking || (!walkStarted && !onGround);
    }
}
