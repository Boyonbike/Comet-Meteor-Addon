/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core.dashboard;

/**
 * Immutable Auto Boat Fly trip data collected on the Minecraft thread. {@code egapReserve} is null while the
 * low-eGap logout is off; {@code minimumHealth} 0 disables the health logout.
 */
public record BoatTelemetry(boolean active, boolean automaticTravel, String state, String detail, boolean inWorld,
    String player, String server, String dimension, double x, double y, double z, double yaw,
    double health, double absorption, int food, int egaps, boolean eating, boolean mounted,
    boolean recovering, String lobbyInfo, double speedBps, long tripNanos, long activeNanos,
    int corrections, HighwayTelemetry.Home home, boolean autoWalk, boolean autoLobby,
    boolean autoSetHome, boolean autoEat, double heading, double distanceTravelled, HighwayTelemetry.Webhook webhook,
    String speedMode, double minimumHealth, Integer egapReserve) {}
