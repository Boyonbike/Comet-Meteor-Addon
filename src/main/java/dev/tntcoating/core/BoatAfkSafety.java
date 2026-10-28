/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

/** Trip safety decisions, independent of Minecraft events and wall-clock changes. */
public final class BoatAfkSafety {
    public record Limits(boolean supplies, int reserve, double health, boolean lava, int dismountedSeconds, int stalledSeconds) {}
    private boolean tracking, missingDriver, advanced;
    private long progressAt, dismountedAt;
    private double bestProgress, progressHeading;

    public void reset() { tracking = missingDriver = advanced = false; }
    public boolean advanced() { return advanced; }

    public String evaluate(long now, boolean recovering, boolean driver, int egaps, double health,
                           boolean lava, double x, double z, double heading, Limits limits) {
        advanced = false;
        if (limits.health() > 0 && health <= limits.health()) return "Health fell to " + health + " points.";
        if (limits.lava() && lava) return "Player or boat entered lava.";
        if (recovering) { reset(); return null; }
        if (limits.supplies() && egaps <= limits.reserve()) return "eGaps reached the safety reserve (" + egaps + " remaining).";
        if (!driver) {
            if (!missingDriver) { missingDriver = true; dismountedAt = now; }
            return elapsed(now, dismountedAt, limits.dismountedSeconds()) ? "Boat driver seat was lost for too long." : null;
        }
        missingDriver = false;
        double radians = Math.toRadians(heading);
        double progress = -Math.sin(radians) * x + Math.cos(radians) * z;
        if (!Double.isFinite(progress) || !Double.isFinite(heading)) return "Invalid boat travel position or heading.";
        if (!tracking || heading != progressHeading || progress - bestProgress >= 1) {
            advanced = tracking && heading == progressHeading && progress - bestProgress >= 1;
            tracking = true; progressAt = now; bestProgress = progress; progressHeading = heading;
        }
        return elapsed(now, progressAt, limits.stalledSeconds()) ? "Boat travel stopped making progress." : null;
    }

    private static boolean elapsed(long now, long since, int seconds) {
        return seconds > 0 && now - since >= seconds * 1_000_000_000L;
    }
}
