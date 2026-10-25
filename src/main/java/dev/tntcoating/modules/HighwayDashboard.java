/*
 * Comet live highway dashboard module.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.TripEventLog;
import dev.tntcoating.core.dashboard.DashboardJson;
import dev.tntcoating.core.dashboard.DashboardLog;
import dev.tntcoating.core.dashboard.DashboardServer;
import dev.tntcoating.core.dashboard.DashboardSettings;
import dev.tntcoating.core.dashboard.DashboardSnapshot;
import dev.tntcoating.core.dashboard.HighwayTelemetry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Serves a read-only live dashboard for Highway Fly on the local network. All game state is collected on the game
 * thread; the HTTP server threads only read the published snapshot.
 */
public final class HighwayDashboard extends Module {
    private static final int KEEP_ALIVE_MILLIS = 10_000;

    private final SettingGroup serverGroup = settings.createGroup("Server", false);
    private final SettingGroup accessGroup = settings.createGroup("Access", false);
    private final SettingGroup publishingGroup = settings.createGroup("Publishing", false);
    private final SettingGroup diagnosticsGroup = settings.createGroup("Diagnostics", false);

    private final Setting<String> bindAddress = serverGroup.add(new StringSetting.Builder()
        .name("bind-address")
        .description("Interface to listen on. 0.0.0.0 opens the dashboard to every interface; 127.0.0.1 keeps it on this PC.")
        .defaultValue("0.0.0.0")
        .wide()
        .build());
    private final Setting<Integer> port = serverGroup.add(new IntSetting.Builder()
        .name("port")
        .description("Port the dashboard listens on. Browsers use http://<this-pc-ip>:<port>/.")
        .defaultValue(8734).min(1024).max(65535)
        .build());
    private final Setting<Integer> maxClients = serverGroup.add(new IntSetting.Builder()
        .name("max-clients")
        .description("Concurrent live browser streams. Each stream holds one server thread.")
        .defaultValue(8).min(1).max(32).sliderRange(1, 16)
        .build());
    private final Setting<String> accessToken = accessGroup.add(new StringSetting.Builder()
        .name("access-token")
        .description("Optional shared secret. Leave blank for an open LAN page; set it to require ?token= or the X-Highway-Token header.")
        .defaultValue("")
        .wide()
        .build());
    private final Setting<Integer> pushInterval = publishingGroup.add(new IntSetting.Builder()
        .name("push-interval-ms")
        .description("How often the page receives a new snapshot, in milliseconds.")
        .defaultValue(250).min(100).max(2000).sliderRange(100, 1000)
        .build());
    private final Setting<Boolean> debug = diagnosticsGroup.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log dashboard clients, failures and server restarts to the Minecraft log.")
        .defaultValue(false)
        .build());

    private DashboardServer server;
    private String runningKey;
    private String serverError;
    private String lastBuildError;
    private String lastPhaseKey;
    private String cachedVersion;
    private List<String> serverUrls = List.of();
    private long serverStartedAt;
    private int boundPort;
    private long sequence;
    private long lastBuildAt;

    public HighwayDashboard() {
        super(TntCoatingAddon.CATEGORY, "highway-dashboard",
            "Serves a live read-only Highway Fly dashboard on your local network.");
        runInMainMenu = true;
        chatFeedback = false;
    }

    @Override
    public void onActivate() {
        runningKey = null;
        serverError = null;
        lastBuildError = null;
        lastPhaseKey = null;
        sequence = 0;
        lastBuildAt = 0;
        startServer();
    }

    @Override
    public void onDeactivate() {
        stopServer();
        lastPhaseKey = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;
        maybeRestartServer();
        HighwayFly fly = Modules.get().get(HighwayFly.class);
        recordPhase(fly);
        DashboardServer current = server;
        if (current == null || !current.running()) return;
        long now = System.currentTimeMillis();
        if (now - lastBuildAt < pushInterval.get()) return;
        lastBuildAt = now;
        publish(current, fly);
    }

    private void startServer() {
        stopServer();
        runningKey = configKey();
        try {
            DashboardServer.Config config = new DashboardServer.Config(bindAddress.get(), port.get(),
                accessToken.get(), maxClients.get(), KEEP_ALIVE_MILLIS, debug.get(), version());
            DashboardServer created = new DashboardServer(config, dashboardLog());
            boundPort = created.start();
            server = created;
            serverError = null;
            DashboardServer.Info started = created.info();
            serverUrls = started.urls();
            serverStartedAt = started.startedAtMillis();
            info("Highway dashboard live at %s", primaryUrl());
        } catch (IOException | RuntimeException error) {
            server = null;
            serverUrls = List.of();
            serverStartedAt = 0;
            boundPort = 0;
            String message = error.getMessage();
            serverError = message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
            warning("Highway dashboard could not start: %s", serverError);
            MeteorClient.LOG.warn("Highway dashboard could not start", error);
        }
    }

    private void stopServer() {
        runningKey = null;
        DashboardServer current = server;
        server = null;
        serverUrls = List.of();
        serverStartedAt = 0;
        boundPort = 0;
        if (current == null) return;
        try {
            current.stop();
        } catch (RuntimeException error) {
            MeteorClient.LOG.warn("Highway dashboard did not stop cleanly", error);
        }
    }

    private void maybeRestartServer() {
        if (configKey().equals(runningKey)) return;
        startServer();
    }

    private String configKey() {
        return bindAddress.get() + "|" + port.get() + "|" + accessToken.get() + "|" + maxClients.get();
    }

    private void publish(DashboardServer current, HighwayFly fly) {
        try {
            DashboardSnapshot snapshot = build(fly);
            current.publish(DashboardJson.toJson(snapshot), snapshot.sequence());
            lastBuildError = null;
        } catch (RuntimeException error) {
            String message = error.getClass().getSimpleName() + ": " + error.getMessage();
            if (!message.equals(lastBuildError)) {
                lastBuildError = message;
                MeteorClient.LOG.warn("Highway dashboard snapshot failed", error);
            }
        }
    }

    private DashboardSnapshot build(HighwayFly fly) {
        HighwayTelemetry flight = fly == null ? HighwayTelemetry.idle() : fly.dashboardTelemetry();
        Map<String, List<DashboardSnapshot.SettingValue>> settings = new LinkedHashMap<>();
        if (fly != null) {
            for (SettingGroup group : fly.settings) {
                List<DashboardSnapshot.SettingValue> values = new ArrayList<>();
                for (Setting<?> setting : group) {
                    String rendered = DashboardSettings.isSensitive(setting.name) ? DashboardSettings.HIDDEN
                        : String.valueOf(setting.get());
                    values.add(new DashboardSnapshot.SettingValue(group.name, setting.name, setting.title, rendered,
                        setting.getClass().getSimpleName(), setting.isVisible()));
                }
                settings.put(group.name, values);
            }
        }
        List<DashboardSnapshot.EventEntry> events = new ArrayList<>();
        for (TripEventLog.Entry entry : TripEventLog.INSTANCE.entries()) {
            events.add(new DashboardSnapshot.EventEntry(entry.sequence(), entry.timeMillis(), entry.kind(),
                entry.type(), entry.title(), entry.color(), entry.description()));
        }
        boolean serverRunning = server != null && server.running();
        DashboardSnapshot.Server serverBlock = new DashboardSnapshot.Server(serverRunning, bindAddress.get(),
            boundPort, serverUrls, server == null ? 0 : server.clients(), serverStartedAt, serverError, version());
        return new DashboardSnapshot(DashboardSnapshot.SCHEMA, ++sequence, System.currentTimeMillis(), serverBlock,
            client(), flight, settings, DashboardSettings.signature(settings), events, lastBuildError);
    }

    private DashboardSnapshot.Client client() {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            return new DashboardSnapshot.Client(
                false, mc.isSingleplayer(), null, serverAddress(), null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                false, false, false, false,
                null, null, null, null, null, null, null);
        }
        Vec3 velocity = player.getDeltaMovement();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        return new DashboardSnapshot.Client(true, mc.isSingleplayer(), player.getScoreboardName(), serverAddress(),
            level.dimension().identifier().toString(),
            round(player.getX()), round(player.getY()), round(player.getZ()),
            round(player.getYRot()), round(player.getXRot()),
            round(player.getHealth()), round(player.getMaxHealth()), round(player.getAbsorptionAmount()),
            player.getArmorValue(), player.getFoodData().getFoodLevel(), player.getAirSupply(),
            player.getMaxAirSupply(),
            player.onGround(), player.isFallFlying(), player.isInWater(), player.isInLava(),
            round(velocity.length() * 20), round(horizontal * 20), round(velocity.y * 20),
            ping(player), level.getOverworldClockTime() % 24_000L, level.isRaining(), level.isThundering());
    }

    private Integer ping(LocalPlayer player) {
        if (mc.getConnection() == null) return null;
        var info = mc.getConnection().getPlayerInfo(player.getUUID());
        return info == null ? null : info.getLatency();
    }

    private String serverAddress() {
        return mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip;
    }

    private void recordPhase(HighwayFly fly) {
        if (fly == null) return;
        HighwayFly.TripStatus status = fly.tripStatus();
        if (status == null) {
            lastPhaseKey = null;
            return;
        }
        String key = status.state() + "|" + status.action();
        if (key.equals(lastPhaseKey)) return;
        lastPhaseKey = key;
        TripEventLog.INSTANCE.recordPhase(key, status.state(), status.detail());
    }

    private String primaryUrl() {
        return serverUrls.isEmpty() ? null : serverUrls.get(0);
    }

    private String version() {
        if (cachedVersion == null) {
            cachedVersion = FabricLoader.getInstance().getModContainer("tnt-coating")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
        }
        return cachedVersion;
    }

    private DashboardLog dashboardLog() {
        return new DashboardLog() {
            @Override
            public void info(String message) {
                if (debug.get()) MeteorClient.LOG.info(message);
            }

            @Override
            public void warn(String message, Throwable error) {
                if (debug.get()) MeteorClient.LOG.warn(message, error);
            }
        };
    }

    private static double round(double value) {
        if (!Double.isFinite(value)) return 0;
        double scaled = value * 1000.0;
        if (Math.abs(scaled) > 9.0e15) return value;
        return Math.round(scaled) / 1000.0;
    }

    @Override
    public String getInfoString() {
        return server != null && server.running() ? primaryUrl() : null;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        var list = theme.verticalList();
        DashboardServer current = server;
        if (current != null && current.running()) {
            for (String url : serverUrls) list.add(theme.label(url));
            list.add(theme.button("Copy URL")).widget().action = this::copyUrl;
            list.add(theme.button("Restart dashboard")).widget().action = this::restartFromWidget;
        } else {
            list.add(theme.label(serverError == null ? "Dashboard stopped" : "Error: " + serverError))
                .widget().color = theme.textSecondaryColor();
            list.add(theme.button("Start dashboard")).widget().action = this::restartFromWidget;
        }
        return list;
    }

    private void copyUrl() {
        String url = primaryUrl();
        if (url == null) return;
        mc.keyboardHandler.setClipboard(url);
        info("Dashboard URL copied.");
    }

    private void restartFromWidget() {
        mc.execute(() -> {
            stopServer();
            lastBuildAt = 0;
            startServer();
        });
    }
}
