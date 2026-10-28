/*
 * Comet live flight dashboard module.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.TripEventLog;
import dev.tntcoating.core.dashboard.BoatTelemetry;
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
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
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
 * Serves one read-only live dashboard on the local network for whichever flight module is in use: Highway Fly or
 * Auto Boat Fly. All game state is collected on the game thread; the HTTP server threads only read the published
 * snapshot.
 */
public final class FlightDashboard extends Module {
    private static final int KEEP_ALIVE_MILLIS = 10_000;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Integer> port = sgGeneral.add(new IntSetting.Builder()
        .name("port")
        .description("Port the dashboard listens on. Open http://<this-pc-ip>:<port>/ in a browser.")
        .defaultValue(8734).min(1024).max(65535).noSlider()
        .build());
    private final Setting<String> bindAddress = sgGeneral.add(new StringSetting.Builder()
        .name("bind-address")
        .description("Network interface to listen on. 0.0.0.0 allows other devices on your network; 127.0.0.1 keeps it on this PC.")
        .defaultValue("0.0.0.0")
        .build());
    private final Setting<String> accessToken = sgGeneral.add(new StringSetting.Builder()
        .name("access-token")
        .description("Optional password for the page. Leave blank for an open LAN page; when set, open the page with ?token=<value>.")
        .defaultValue("")
        .build());
    private final Setting<Integer> pushInterval = sgGeneral.add(new IntSetting.Builder()
        .name("push-interval-ms")
        .description("How often the page receives new data, in milliseconds.")
        .defaultValue(250).min(100).max(2000).sliderRange(100, 1000)
        .build());
    private final Setting<Integer> maxClients = sgGeneral.add(new IntSetting.Builder()
        .name("max-clients")
        .description("How many browser tabs may watch at once. Each one holds a server thread.")
        .defaultValue(8).min(1).max(32).sliderRange(1, 16)
        .build());
    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log dashboard connections, failures and restarts to the Minecraft log.")
        .defaultValue(false)
        .build());

    private final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
    private DashboardServer server;
    private String runningKey;
    private String serverError;
    private String lastBuildError;
    private String lastHighwayPhase;
    private String lastBoatPhase;
    private String cachedVersion;
    private String mode = DashboardSnapshot.HIGHWAY;
    private List<String> serverUrls = List.of();
    private long serverStartedAt;
    private int boundPort;
    private long sequence;
    private long lastBuildAt;

    public FlightDashboard() {
        super(TntCoatingAddon.CATEGORY, "flight-dashboard",
            "Live browser dashboard for Highway Fly and Auto Boat Fly, served on your local network.");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        serverError = null;
        lastBuildError = null;
        lastHighwayPhase = lastBoatPhase = null;
        sequence = 0;
        lastBuildAt = 0;
        startServer();
    }

    @Override
    public void onDeactivate() {
        stopServer();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        maybeRestartServer();
        HighwayFly highway = Modules.get().get(HighwayFly.class);
        AutoBoatFly boat = Modules.get().get(AutoBoatFly.class);
        mode = selectMode(highway, boat);
        recordPhases(highway, boat);
        DashboardServer current = server;
        if (current == null || !current.running()) return;
        long now = System.currentTimeMillis();
        if (now - lastBuildAt < pushInterval.get()) return;
        lastBuildAt = now;
        publish(current, highway, boat);
    }

    /** The running module wins; otherwise the page keeps showing the last one used. */
    private String selectMode(HighwayFly highway, AutoBoatFly boat) {
        if (boat != null && boat.isActive()) return DashboardSnapshot.BOAT;
        if (highway != null && (highway.isActive() || highway.session.recovering())) return DashboardSnapshot.HIGHWAY;
        return mode;
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
            info("Flight dashboard live at %s", primaryUrl());
        } catch (IOException | RuntimeException error) {
            server = null;
            serverUrls = List.of();
            serverStartedAt = 0;
            boundPort = 0;
            String message = error.getMessage();
            serverError = message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
            warning("Flight dashboard could not start: %s", serverError);
            MeteorClient.LOG.warn("Flight dashboard could not start", error);
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
            MeteorClient.LOG.warn("Flight dashboard did not stop cleanly", error);
        }
    }

    private void maybeRestartServer() {
        if (configKey().equals(runningKey)) return;
        startServer();
    }

    private String configKey() {
        return bindAddress.get() + "|" + port.get() + "|" + accessToken.get() + "|" + maxClients.get();
    }

    private void publish(DashboardServer current, HighwayFly highway, AutoBoatFly boat) {
        try {
            DashboardSnapshot snapshot = build(highway, boat);
            current.publish(DashboardJson.toJson(snapshot), snapshot.sequence());
            lastBuildError = null;
        } catch (RuntimeException error) {
            String message = error.getClass().getSimpleName() + ": " + error.getMessage();
            if (!message.equals(lastBuildError)) {
                lastBuildError = message;
                MeteorClient.LOG.warn("Flight dashboard snapshot failed", error);
            }
        }
    }

    private DashboardSnapshot build(HighwayFly highway, AutoBoatFly boat) {
        boolean boatMode = DashboardSnapshot.BOAT.equals(mode) && boat != null;
        HighwayTelemetry flight = highway == null ? HighwayTelemetry.idle() : highway.dashboardTelemetry();
        BoatTelemetry boatTelemetry = boatMode ? boat.dashboardTelemetry() : null;
        List<Module> shown = boatMode ? List.of(boat, boat.lobby(), boat.homes())
            : highway == null ? List.of() : List.of(highway, highway.homes(), highway.portalGuard(), highway.lobby());
        Map<String, List<DashboardSnapshot.SettingValue>> settings = settingsOf(shown);
        TripEventLog log = boatMode ? boat.eventLog() : TripEventLog.INSTANCE;
        List<DashboardSnapshot.EventEntry> events = new ArrayList<>();
        for (TripEventLog.Entry entry : log.entries()) {
            events.add(new DashboardSnapshot.EventEntry(entry.sequence(), entry.timeMillis(), entry.kind(),
                entry.type(), entry.title(), entry.color(), entry.description()));
        }
        boolean serverRunning = server != null && server.running();
        DashboardSnapshot.Server serverBlock = new DashboardSnapshot.Server(serverRunning, bindAddress.get(),
            boundPort, serverUrls, server == null ? 0 : server.clients(), serverStartedAt, serverError, version());
        return new DashboardSnapshot(DashboardSnapshot.SCHEMA, ++sequence, System.currentTimeMillis(), serverBlock,
            client(), boatMode ? DashboardSnapshot.BOAT : DashboardSnapshot.HIGHWAY, flight, boatTelemetry,
            settings, DashboardSettings.signature(settings), events, lastBuildError);
    }

    /** The flight module's own groups first, then each helper's groups prefixed by its title. */
    private static Map<String, List<DashboardSnapshot.SettingValue>> settingsOf(List<Module> modules) {
        Map<String, List<DashboardSnapshot.SettingValue>> settings = new LinkedHashMap<>();
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            for (SettingGroup group : module.settings) {
                String groupName = i == 0 ? group.name : module.title + " / " + group.name;
                List<DashboardSnapshot.SettingValue> values = new ArrayList<>();
                for (Setting<?> setting : group) {
                    String rendered = DashboardSettings.isSensitive(setting.name) ? DashboardSettings.HIDDEN
                        : String.valueOf(setting.get());
                    values.add(new DashboardSnapshot.SettingValue(groupName, setting.name, setting.title, rendered,
                        setting.getClass().getSimpleName(), setting.isVisible()));
                }
                settings.put(groupName, List.copyOf(values));
            }
        }
        return settings;
    }

    private DashboardSnapshot.Client client() {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            return new DashboardSnapshot.Client(
                false, mc.isLocalServer(), null, serverAddress(), null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                false, false, false, false,
                null, null, null, null, null, null, null);
        }
        Vec3 velocity = player.getRootVehicle().getDeltaMovement();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        return new DashboardSnapshot.Client(true, mc.isLocalServer(), player.getScoreboardName(), serverAddress(),
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

    /** Phase changes go into the event feed of the module that produced them. */
    private void recordPhases(HighwayFly highway, AutoBoatFly boat) {
        HighwayFly.TripStatus status = highway == null ? null : highway.tripStatus();
        if (status == null) lastHighwayPhase = null;
        else {
            String key = status.state() + "|" + status.action();
            if (!key.equals(lastHighwayPhase)) {
                lastHighwayPhase = key;
                TripEventLog.INSTANCE.recordPhase(key, status.state(), status.detail());
            }
        }
        String state = boat == null || !boat.isActive() ? null : boat.tripState();
        if (state == null) lastBoatPhase = null;
        else if (!state.equals(lastBoatPhase)) {
            lastBoatPhase = state;
            boat.eventLog().recordPhase(state, state, null);
        }
    }

    private String primaryUrl() {
        return serverUrls.isEmpty() ? null : serverUrls.get(0);
    }

    private String version() {
        if (cachedVersion == null) {
            cachedVersion = FabricLoader.getInstance().getModContainer("comet")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
        }
        return cachedVersion;
    }

    private DashboardLog dashboardLog() {
        return new DashboardLog() {
            public void info(String message) {
                if (debug.get()) MeteorClient.LOG.info(message);
            }

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

    /** Running URL, or the reason it is not running; null while off. */
    private String statusText() {
        if (!isActive()) return "Off";
        DashboardServer current = server;
        if (current != null && current.running()) return primaryUrl();
        return serverError == null ? "Starting" : "Error: " + serverError;
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
            var buttons = list.add(theme.horizontalList()).widget();
            buttons.add(theme.button("Copy URL")).widget().action = this::copyUrl;
            buttons.add(theme.button("Restart")).widget().action = this::restartFromWidget;
        } else {
            list.add(theme.label(!isActive() ? "Enable this module to serve the dashboard."
                : serverError == null ? "Starting…" : "Error: " + serverError)).widget().color = theme.textSecondaryColor();
            if (isActive()) list.add(theme.button("Retry")).widget().action = this::restartFromWidget;
        }
        list.add(theme.label("Shows Auto Boat Fly while it runs, otherwise Highway Fly.")).widget().color = theme.textSecondaryColor();
        return list;
    }

    /** One row for a flight module's settings screen: dashboard status plus a shortcut to these settings. */
    public static void addStatusRow(GuiTheme theme, WTable table) {
        FlightDashboard dashboard = Modules.get().get(FlightDashboard.class);
        if (dashboard == null) return;
        table.add(theme.label(dashboard.title));
        table.add(theme.label(dashboard.statusText())).expandX().widget().color = theme.textSecondaryColor();
        table.add(theme.button("Settings")).widget().action = () ->
            net.minecraft.client.Minecraft.getInstance().gui.setScreen(theme.moduleScreen(dashboard));
        table.row();
    }

    private void copyUrl() {
        String url = primaryUrl();
        if (url == null) return;
        mc.keyboardHandler.setClipboard(url);
        info("Dashboard URL copied.");
    }

    private void restartFromWidget() {
        mc.execute(() -> {
            if (!isActive()) return;
            stopServer();
            lastBuildAt = 0;
            startServer();
        });
    }
}
