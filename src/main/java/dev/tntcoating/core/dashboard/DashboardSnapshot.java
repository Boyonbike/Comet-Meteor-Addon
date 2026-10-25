/*
 * Comet highway dashboard snapshot schema.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core.dashboard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One published dashboard frame. Schema 1 is frozen for the served frontend; new fields may be added, but existing
 * names and meanings must not change without a schema bump.
 */
public record DashboardSnapshot(int schema, long sequence, long generatedAt, Server server, Client client,
                                HighwayTelemetry flight, Map<String, List<SettingValue>> settings,
                                int settingsSignature, List<EventEntry> events, String error) {
    public static final int SCHEMA = 1;

    public DashboardSnapshot {
        schema = SCHEMA;
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        events = List.copyOf(events);
    }

    /** The dashboard server's own state, so the page can report where it is served from. */
    public record Server(boolean running, String bindAddress, int port, List<String> urls, int clients,
                         long startedAt, String error, String version) {
        public Server {
            urls = List.copyOf(urls);
        }
    }

    /** Client state around the player. Every field is nullable when there is no world. */
    public record Client(boolean inWorld, boolean singleplayer, String player, String server, String dimension,
                         Double x, Double y, Double z, Double yaw, Double pitch, Double health, Double maxHealth,
                         Double absorption, Integer armor, Integer food, Integer air, Integer maxAir,
                         boolean onGround, boolean fallFlying, boolean inWater, boolean inLava, Double speedBps,
                         Double horizontalSpeedBps, Double verticalSpeedBps, Integer ping, Long dayTime,
                         Boolean raining, Boolean thundering) {}

    /** One setting row; sensitive values are masked before they reach the snapshot. */
    public record SettingValue(String group, String name, String title, String value, String type,
                               boolean visible) {}

    /** One dashboard event row, oldest to newest inside the snapshot. */
    public record EventEntry(long sequence, long time, String kind, String type, String title, int color,
                             String description) {}
}
