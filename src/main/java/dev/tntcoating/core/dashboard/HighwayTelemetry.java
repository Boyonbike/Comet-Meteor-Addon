/*
 * Comet highway dashboard flight telemetry.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core.dashboard;

import java.util.List;
import dev.tntcoating.core.SetHomeScheduler;

/**
 * Immutable snapshot of everything Highway Fly knows about its current trip. Built on the game thread from private
 * module state, so the dashboard server can serve it without touching the game.
 */
public record HighwayTelemetry(
    boolean active,
    String stateName,
    String stateLabel,
    String action,
    String detail,
    boolean started,
    long tripNanos,
    long activeNanos,
    String dimension,
    String server,
    boolean recovering,
    boolean recoveryAllowed,
    String lobbyInfo,
    Home home,
    Route route,
    Altitude altitude,
    Counters counters,
    Path path,
    Walk walk,
    Portal portal,
    Safety safety,
    Elytra elytra,
    Webhook webhook,
    List<String> pausedModules
) {
    /** The snapshot shown while Highway Fly is off. */
    public static HighwayTelemetry idle() { return idle(null); }

    public static HighwayTelemetry idle(Home home) {
        return idle(home, new Elytra(false, null, 0, 0));
    }

    public static HighwayTelemetry idle(Home home, Elytra elytra) {
        return new HighwayTelemetry(false, null, null, null, null, false, 0, 0, null, null, false, false, null,
            home, null, new Altitude(null, null, null, false), new Counters(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new Path(false, List.of()), new Walk(false, false, null, null, null, 0, 0),
            new Portal(null, false, false, 0, false, false, null),
            new Safety(0, 0, 0, false, false), elytra, new Webhook(false, false, false),
            List.of());
    }

    /** Home schedule and persistent history remain available while the helper is paused. */
    public record Home(String mode, String home, boolean waiting, long waitMillis, long intervalMillis,
                       boolean fixedTimer, String cooldownLabel, boolean cooldownKnown,
                       SetHomeScheduler.Status schedule, boolean paused, String account, String server,
                       List<SetHomeScheduler.Entry> history) {
        public Home { history = List.copyOf(history); }
    }

    /** The active course, in the same units Highway Fly uses. */
    public record Route(double originX, double originZ, double destinationX, double destinationZ, double length,
                        double remaining, double distanceToDestination, double progress, double yaw, double targetY,
                        double arrivalDistance) {}

    /** Altitude controller values; null while there is no course. */
    public record Altitude(Double targetY, Double activeY, Double preferredY, boolean blocked) {}

    /** Counters that explain what the autopilot is reacting to. */
    public record Counters(int detourTicks, int blockedAttempts, int takeoffAttempts, int rubberbands,
                           int walkFailures, int failedSwaps, int walkBlocks, int walkTicks, int retryTicks,
                           int climbTicks) {}

    public record Point(double x, double y, double z) {}

    /** A bounded copy of the current path; truncated when the full path is longer than the dashboard cap. */
    public record Path(boolean truncated, List<Point> points) {}

    public record Walk(boolean started, boolean hasTarget, Double targetX, Double targetY, Double targetZ, int blocks,
                       int failures) {}

    public record Portal(String mode, boolean inside, boolean crossing, int crossingTicks, boolean walkPaused,
                         boolean lookaheadHit, Hit hit) {
        public record Hit(double distance, double x, double y, double z) {}
    }

    /** Trip damage is informational; minimumHealth is the remaining-health logout threshold (0 disables it). */
    public record Safety(double damageTaken, double minimumHealth, int noProgressLimitSeconds,
                         boolean safetyDisconnect, boolean deathNotified) {}

    /** Usable count includes worn, main inventory, hotbar and offhand elytra above minDurability. */
    public record Elytra(boolean equipped, Integer remaining, int minDurability, int usableCount) {}

    public record Webhook(boolean enabled, boolean configured, boolean terminal) {}
}
