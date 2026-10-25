package dev.tntcoating.hud;

/** Compact elapsed-time text shared by the Highway Fly runtime HUD. */
final class HudTime {
    private HudTime() {}

    static String duration(long nanos) {
        long seconds = Math.max(0, nanos) / 1_000_000_000L;
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        if (hours > 0) return hours + "h " + two(minutes) + "m " + two(secs) + "s";
        if (minutes > 0) return minutes + "m " + two(secs) + "s";
        return secs + "s";
    }

    private static String two(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }
}
