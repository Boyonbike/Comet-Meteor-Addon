/*
 * Comet boat travel controller. Flight engine adapted from Quiettee Utils (GPL-3.0).
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.tntcoating.modules;

import dev.tntcoating.modules.boatfly.BoatFlyEngine;
import dev.tntcoating.core.BoatWebhook;
import dev.tntcoating.core.BoatAfkSafety;
import dev.tntcoating.core.BoatAfkWatchdog;
import dev.tntcoating.core.BoatEatingGuard;
import dev.tntcoating.core.BoatHealthMonitor;
import dev.tntcoating.core.BoatRecoveryPolicy;
import dev.tntcoating.core.BoatSupplies;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.Connection;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import dev.tntcoating.core.HighwayStatusTimer;
import dev.tntcoating.core.HighwayWebhook;
import dev.tntcoating.core.HighwayWebhookPolicy;
import dev.tntcoating.core.TripEventLog;
import dev.tntcoating.core.dashboard.BoatTelemetry;
import dev.tntcoating.core.dashboard.HighwayTelemetry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.AutoWalk;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

/** Fixed-heading AFK trip owner; helpers provide eating, homes and lobby recovery. */
public final class AutoBoatFly extends BoatFlyEngine {
    private final dev.tntcoating.core.BoatTravelControl travelControl = new dev.tntcoating.core.BoatTravelControl();
    private final SettingGroup trip = settings.createGroup("Trip", true);
    private final SettingGroup helpers = settings.createGroup("Helpers", false);
    private final SettingGroup food = settings.createGroup("Food", false);
    private final SettingGroup webhooks = settings.createGroup("Webhooks", false);
    private final Setting<Double> heading = trip.add(new DoubleSetting.Builder().name("heading")
        .description("Fixed yaw to travel along: 0 south, 90 west, -90 east, 180 north. Use current heading copies the way you face.")
        .defaultValue(0).range(-180, 180).sliderRange(-180, 180).build());
    private final Setting<meteordevelopment.meteorclient.utils.misc.Keybind> tripKey = trip.add(new KeybindSetting.Builder()
        .name("start-pause-trip-key").description("Start or pause automatic travel. While paused you steer by hand, as in Boat Fly.")
        .defaultValue(meteordevelopment.meteorclient.utils.misc.Keybind.none()).action(this::toggleAutomaticTravel).build());

    private final Setting<Boolean> autoLobby = helpers.add(new BoolSetting.Builder().name("lobby-recovery")
        .description("Let AutoLobby reconnect and walk back through the lobby portal to resume this trip. Login and reconnect timing live in AutoLobby.")
        .defaultValue(true).onChanged(v -> syncHelpers()).build());
    private final Setting<Boolean> autoHome = helpers.add(new BoolSetting.Builder().name("auto-set-home")
        .description("Let AutoSetHome replace your home while travelling. The home name and timing live in AutoSetHome.")
        .defaultValue(true).onChanged(v -> syncHelpers()).build());

    private final Setting<Boolean> autoEat = food.add(new BoolSetting.Builder().name("auto-eat")
        .description("Borrow AutoEat with the thresholds below, allow eGaps and search the whole inventory. Your AutoEat settings return when the trip stops.")
        .defaultValue(true).onChanged(v -> syncHelpers()).build());
    private final Setting<Double> eatHealth = food.add(new DoubleSetting.Builder().name("eat-health")
        .description("Eat at or below this much health.")
        .defaultValue(16).range(1, 19).sliderRange(1, 19).visible(autoEat::get).onChanged(v -> syncHelpers()).build());
    private final Setting<Integer> eatHunger = food.add(new IntSetting.Builder().name("eat-hunger")
        .description("Eat at or below this hunger level.")
        .defaultValue(19).range(1, 19).sliderRange(1, 19).visible(autoEat::get).onChanged(v -> syncHelpers()).build());
    private final Setting<Integer> eatingTimeout = food.add(new IntSetting.Builder().name("eating-failure-timeout-seconds")
        .description("Log out if food is needed but eating has not worked for this long. 0 disables.")
        .defaultValue(15).range(0, 300).sliderRange(0, 60).visible(autoEat::get).build());

    // Logout checks join the engine's lava settings in one Safety section.
    private final Setting<Double> minimumHealth = sgSafety.add(new DoubleSetting.Builder().name("minimum-health")
        .description("Log out at or below this much red health (2 points = 1 heart). Absorption is excluded; 0 disables.")
        .defaultValue(8).range(0, 20).sliderRange(0, 20).build());
    private final Setting<Boolean> logoutLava = sgSafety.add(new BoolSetting.Builder().name("logout-in-lava")
        .description("Log out if the player or boat ends up in lava.").defaultValue(true).build());
    private final Setting<Boolean> logoutEgaps = sgSafety.add(new BoolSetting.Builder().name("logout-on-low-egaps")
        .description("Log out when your enchanted golden apples run down to the reserve.").defaultValue(true).build());
    private final Setting<Integer> egapReserve = sgSafety.add(new IntSetting.Builder().name("egap-reserve")
        .description("eGaps to keep in reserve. 0 logs out only when none remain.")
        .defaultValue(0).min(0).sliderRange(0, 64).visible(logoutEgaps::get).build());
    private final Setting<Integer> dismountTimeout = sgSafety.add(new IntSetting.Builder().name("dismount-timeout-seconds")
        .description("Log out after this long out of the driver's seat, outside lobby recovery. 0 disables.")
        .defaultValue(30).range(0, 600).sliderRange(0, 120).build());
    private final Setting<Integer> progressTimeout = sgSafety.add(new IntSetting.Builder().name("no-progress-timeout-seconds")
        .description("Log out after this long without moving one new block along the heading. 0 disables.")
        .defaultValue(60).range(0, 3600).sliderRange(0, 300).build());
    private final Setting<Integer> worldTimeout = sgSafety.add(new IntSetting.Builder().name("world-wait-timeout-seconds")
        .description("Log out if you end up in an unexpected world or server and the trip world does not return within this time. 0 disables.")
        .defaultValue(30).range(0, 3600).sliderRange(0, 120).build());
    private final Setting<Integer> recoveryTimeout = sgSafety.add(new IntSetting.Builder().name("recovery-timeout-seconds")
        .description("Log out if lobby recovery has not resumed the trip within this total time, across every retry. 0 disables.")
        .defaultValue(300).range(0, 7200).sliderRange(0, 600).visible(autoLobby::get).build());

    private final Setting<Boolean> webhook = webhooks.add(new BoolSetting.Builder().name("webhook")
        .description("Post trip events and the eGaps you have left to Discord.").defaultValue(false).build());
    private final Setting<String> webhookUrl = webhooks.add(new StringSetting.Builder().name("webhook-url")
        .description("Discord webhook URL from Channel settings → Integrations → Webhooks. Keep it private.").defaultValue("").wide().visible(webhook::get).build());
    private final Setting<String> mention = webhooks.add(new StringSetting.Builder().name("mention")
        .description("Optional Discord user or role ID to ping on deaths, safety logouts and login failures.").defaultValue("").visible(webhook::get).build());
    private final Setting<Integer> statusMinutes = webhooks.add(new IntSetting.Builder().name("status-interval-minutes")
        .description("Minutes between status reports while travelling. 0 disables periodic reports.")
        .defaultValue(30).range(0, 1440).sliderRange(0, 120).visible(webhook::get).build());
    private final BoatAutomationLease automation = new BoatAutomationLease();
    private final TripEventLog events = new TripEventLog(100, System::currentTimeMillis);
    private final HighwayWebhookPolicy notifications = new HighwayWebhookPolicy();
    private final HighwayStatusTimer statusTimer = new HighwayStatusTimer();
    private long tripStart, tripEnd, activeStart, activeNanos;
    private double distance;
    private Vec3 lastPosition;
    private Object lastLevel;
    private boolean started, wasRecovering, walkSuspended, deathReported;
    private HighwayWebhook.Context lastContext;
    private int lastEgaps;
    private final BoatAfkSafety afkSafety = new BoatAfkSafety();
    private boolean safetyDisconnect, travelWorldReady, restoringSavedTrip, leavingWorld;
    private final BoatAfkWatchdog watchdog = new BoatAfkWatchdog();
    private final BoatEatingGuard eatingGuard = new BoatEatingGuard();
    private final BoatHealthMonitor healthMonitor = new BoatHealthMonitor();
    private long connectedSince;
    private boolean stableConnectionReported;
    private String stopReason;

    public double heading() { return heading.get(); }
    @Override protected boolean automaticForward() {
        return isAutomaticTravel() && travelWorldReady && !lobby().isNativeBoatRecovering();
    }
    @Override protected float automaticYaw() { return heading.get().floatValue(); }
    public void useCurrentHeading() {
        if (mc.player != null) heading.set((double) net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()));
    }
    private void captureTripWorld() {
        if (mc.level != null) travelControl.bindWorld(serverAddress(), mc.level.dimension().identifier().toString());
    }
    private boolean sameTripWorld() {
        return mc.level != null && travelControl.inTripWorld(serverAddress(), mc.level.dimension().identifier().toString());
    }


    public AutoBoatFly() {
        super("auto-boat-fly", "AFK boat travel along a fixed heading, with lobby recovery, homes, eating and safety logouts.");
        runInMainMenu = true;
        leadWithGroups(trip, sgControls, sgSpeed, sgPhasing, helpers, food, sgSafety, webhooks);
    }

    @Override protected String boardedMessage(String dismount) {
        return isAutomaticTravel() ? "Boat ready: travelling along " + heading.get() + " degrees; " + dismount + " dismounts."
            : "Boat ready: steer by hand, then start automatic travel; " + dismount + " dismounts.";
    }

    AutoLobby lobby() { return Modules.get().get(AutoLobby.class); }
    AutoSetHome homes() { return Modules.get().get(AutoSetHome.class); }
    public boolean isAutomaticTravel() { return isActive() && travelControl.automatic(); }
    boolean permitsSavedRecovery() {
        return BoatRecoveryPolicy.controllerAuthorized(isActive(), autoLobby.get(), travelControl.automatic(),
            profileLoading || restoringSavedTrip);
    }
    boolean wantsLobbyRecovery() { return isAutomaticTravel() && autoLobby.get(); }
    boolean autoWalkEnabled() { return isAutomaticTravel(); }
    boolean homesAllowed() { return !isAutomaticTravel() || travelWorldReady && mounted(); }
    public TripEventLog eventLog() { return events; }

    @Override public void onActivate() {
        if (profileLoading) return;
        var saved = restoringSavedTrip ? lobby().savedNativeBoatTrip().orElse(null) : null;
        if (restoringSavedTrip && !BoatRecoveryPolicy.canResume(saved, serverAddress(), mc.level != null, autoLobby.get())) {
            rejectActivation("No authorized saved boat trip for this server; start a new trip from the driver seat."); return;
        }
        if (!restoringSavedTrip && (!mounted() || lobby().isKnownLobby())) {
            rejectActivation("Start a new Auto Boat Fly trip from the driver's seat in the world you want to travel in."); return;
        }
        Module highway = Modules.get().get(HighwayFly.class);
        if (highway != null && highway.isActive()) {
            if (restoringSavedTrip) ((HighwayFly) highway).yieldToBoatRecovery(); else highway.disable();
        }
        Module external = Modules.get().get("boat-phase");
        if (external != null && external.isActive()) external.disable();
        super.onActivate();
        travelControl.restore(true);
        safetyDisconnect = leavingWorld = false; stopReason = null; travelWorldReady = false;
        resetSafety(); healthMonitor.clear(); healthMonitor.begin(connection());
        connectedSince = System.nanoTime(); stableConnectionReported = false;
        travelControl.bindWorld(null, null);
        if (restoringSavedTrip) {
            heading.set((double) net.minecraft.util.Mth.wrapDegrees(saved.boatYaw()));
            travelControl.bindWorld(saved.server(), saved.dimension());
            if (!lobby().restoreNativeBoatTrip()) { rejectActivation("The saved boat trip could not be loaded safely."); return; }
        } else {
            captureTripWorld();
            if (autoLobby.get() && !lobby().startNativeBoatTrip(mc.level.dimension().identifier().toString(), serverAddress(), heading.get().floatValue())) {
                rejectActivation("Could not save the new boat recovery identity; automatic travel was not started."); return;
            }
        }
        tripStart = System.nanoTime(); tripEnd = 0; activeNanos = 0; activeStart = 0;
        distance = 0; lastPosition = null; lastLevel = null;
        started = wasRecovering = walkSuspended = deathReported = false;
        notifications.reset(); statusTimer.reset(tripStart);
        syncHelpers();
    }

    private void rejectActivation(String reason) {
        warning(reason);
        // Rejection must not erase a marker belonging to a different profile/server.
        lobby().releaseNativeBoatRuntime();
        profileLoading = true;
        try { disable(); } finally { profileLoading = false; }
        stopReason = reason;
    }

    private void resetSafety() { afkSafety.reset(); watchdog.reset(); eatingGuard.reset(); }
    private Connection connection() { return mc.getConnection() == null ? null : mc.getConnection().getConnection(); }
    String recoveryServer() {
        String address = mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip;
        java.nio.file.Path save = address == null && mc.getSingleplayerServer() != null
            ? ((meteordevelopment.meteorclient.mixin.MinecraftServerAccessor) (Object) mc.getSingleplayerServer())
                .meteor$getSession().getLevelPath(net.minecraft.world.level.storage.LevelResource.ROOT) : null;
        return dev.tntcoating.core.BoatTripIdentity.serverKey(address, save);
    }
    private String serverAddress() { return recoveryServer(); }

    @Override public void onDeactivate() {
        if (!profileLoading && !safetyDisconnect && started) notifyTrip(HighwayWebhook.Event.STOPPED, "Auto Boat Fly was turned off; boat travel stopped.");
        pauseTime();
        tripEnd = System.nanoTime();
        if (!profileLoading && lobby().nativeBoatTrip()) lobby().cancelRecovery();
        super.onDeactivate();
        travelControl.pause();
        travelWorldReady = false; resetSafety(); healthMonitor.clear();
        automation.release();
        lobby().boatManaged(false);
        homes().boatManaged(false);
        lastPosition = null;
        statusTimer.clear();
    }

    void helperDisabled(HighwayHelper helper) {
        if (helper instanceof AutoLobby) autoLobby.set(false);
        if (helper instanceof AutoSetHome) autoHome.set(false);
    }

    private void syncHelpers() {
        if (autoLobby == null || autoHome == null || autoEat == null || eatHealth == null || eatHunger == null || profileLoading) return;
        boolean running = travelControl.helpersRequested(isActive());
        automation.direction(isActive());
        if (isActive() && !running) automation.positioningWalk();
        else automation.walking(running);
        automation.eating(running && autoEat.get(), eatHealth.get(), eatHunger.get(), inventoryAccessSafe());
        if (running && !autoLobby.get()) lobby().cancelNativeBoatRecovery();
        lobby().boatManaged(running && autoLobby.get());
        if (running && autoLobby.get()) lobby().claimNativeBoatController();
        homes().boatManaged(running && autoHome.get());
        if (running && (!mounted() || lobby().isBoatRecovering())) suspendWalking();
    }

    public void startAutomaticTravel() {
        if (!mounted()) { warning("Board the driver's seat before starting automatic travel."); return; }
        if (!isActive()) { enable(); return; }
        automaticTravelChanged(true);
    }

    public void pauseAutomaticTravel() { automaticTravelChanged(false); }

    private void toggleAutomaticTravel() {
        if (isAutomaticTravel()) pauseAutomaticTravel(); else startAutomaticTravel();
    }

    private void automaticTravelChanged(boolean requested) {
        if (profileLoading) return;
        if (!isActive()) return;
        if (requested) {
            if (!travelControl.start(mounted())) {
                warning("Board the driver's seat before starting automatic travel.");
                return;
            }
            resetSafety(); healthMonitor.clear(); healthMonitor.begin(connection());
            stopReason = null; captureTripWorld();
            if (autoLobby.get() && !lobby().startNativeBoatTrip(mc.level.dimension().identifier().toString(), serverAddress(), heading.get().floatValue())) {
                logOut("Could not save the new boat recovery identity."); return;
            }
            tripStart = System.nanoTime(); tripEnd = activeNanos = activeStart = 0;
            distance = 0; lastPosition = null; lastLevel = null;
            started = wasRecovering = walkSuspended = deathReported = false;
            notifications.reset(); statusTimer.reset(tripStart);
        } else {
            if (travelControl.automatic() && started) notifyTrip(HighwayWebhook.Event.STOPPED,
                "Automatic travel paused; manual boat positioning is available.");
            travelControl.pause();
            resetSafety(); travelWorldReady = false;
            pauseTime(); tripEnd = System.nanoTime(); statusTimer.clear();
            lobby().cancelNativeBoatRecovery();
            pauseAutomation(true, false);
            pauseAutomation(false, false);
        }
        syncHelpers();
    }

    private boolean mounted() {
        return mc.player != null && mc.player.isAlive()
            && mc.player.getRootVehicle() instanceof AbstractBoat boat && boat.getControllingPassenger() == mc.player;
    }

    private void suspendWalking() {
        Modules.get().get(AutoWalk.class).disable();
        walkSuspended = true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void boatHealthPacket(PacketEvent.Receive event) {
        if (profileLoading || !isAutomaticTravel() || event.isCancelled() || event.connection != connection()) return;
        if (event.packet instanceof ClientboundSetHealthPacket health) healthMonitor.health(event.connection, health.getHealth());
        if (event.packet instanceof ClientboundPlayerCombatKillPacket killed && mc.player != null && killed.playerId() == mc.player.getId())
            healthMonitor.death(event.connection);
    }

    /** Always run immediate protection, even while waiting in the wrong world or for recovery. */
    private boolean immediateProtection() {
        double health = mc.player == null ? 20 : mc.player.getHealth();
        if (healthMonitor.dead() || mc.player != null && !mc.player.isAlive()) {
            if (!deathReported) { deathReported = true; notifyTrip(HighwayWebhook.Event.DEATH, "The player died during boat travel."); }
            logOut("Player died during boat travel."); return false;
        }
        if (healthMonitor.lowHealth(health, minimumHealth.get())) {
            logOut("Remaining health is at or below " + minimumHealth.get() + " health points."); return false;
        }
        if (logoutLava.get() && mc.player != null && (mc.player.isInLava() || mc.player.getRootVehicle().isInLava())) {
            logOut("Player or boat entered lava."); return false;
        }
        return true;
    }

    @EventHandler
    private void boatTripTick(TickEvent.Pre event) {
        if (profileLoading) return;
        if (!travelControl.automatic()) { pauseTime(); lastPosition = null; resetSafety(); return; }
        if (connection() != null) healthMonitor.begin(connection());
        if (!immediateProtection()) return;
        long now = System.nanoTime();
        boolean online = mc.player != null && mc.level != null && connection() != null;
        boolean recovering = wantsLobbyRecovery() && lobby().isNativeBoatRecovering();
        boolean tripWorld = online && sameTripWorld();
        String overdue = watchdog.evaluate(now, tripWorld, recovering || !online && wantsLobbyRecovery(),
            tripWorld && mounted() && mc.player.tickCount >= 40, worldTimeout.get(), recoveryTimeout.get());
        if (overdue != null) { logOut(overdue); return; }
        if (!online) {
            pauseTime(); lastPosition = null; travelWorldReady = false;
            afkSafety.reset(); eatingGuard.reset(); return;
        }
        leavingWorld = false;
        automation.direction(true);
        automation.eating(autoEat.get(), eatHealth.get(), eatHunger.get(), inventoryAccessSafe());
        if (!stableConnectionReported && now - connectedSince >= TimeUnit.MINUTES.toNanos(5)) {
            Modules.get().get(HighwayFly.class).resetBoatReconnectBackoff(); stableConnectionReported = true;
        }
        if (recovering && !wasRecovering) notifyTrip(HighwayWebhook.Event.RECOVERY, "Recovering the boat trip through the lobby.");
        if (!recovering && wasRecovering && mounted()) notifyTrip(HighwayWebhook.Event.RESUMED, "Boat travel resumed.");
        wasRecovering = recovering;
        travelWorldReady = tripWorld && !recovering && mc.player.tickCount >= 40;
        if (!tripWorld || mc.player.tickCount < 40) {
            pauseTime(); lastPosition = null; afkSafety.reset(); eatingGuard.reset();
            if (!recovering) { pauseAutomation(true, false); suspendWalking(); }
            return;
        }
        String unsafe = afkSafety.evaluate(now, recovering, mounted(), egapsRemaining(), healthMonitor.currentHealth(mc.player.getHealth()),
            mc.player.isInLava() || mc.player.getRootVehicle().isInLava(), mc.player.getX(), mc.player.getZ(), heading.get(),
            new BoatAfkSafety.Limits(logoutEgaps.get(), egapReserve.get(), minimumHealth.get(), logoutLava.get(),
                dismountTimeout.get(), progressTimeout.get()));
        if (unsafe != null) { logOut(unsafe); return; }
        if (afkSafety.advanced()) watchdog.confirmTravel();
        if (!recovering) {
            pauseAutomation(false, false);
            if (mounted()) {
                mc.player.setYRot(heading.get().floatValue());
                mc.player.getRootVehicle().setYRot(heading.get().floatValue());
                lobby().nativeBoatHeading(heading.get().floatValue());
            }
            if (!checkEating(now)) return;
        } else eatingGuard.reset();
        if (!mounted() || recovering) {
            pauseTime(); lastPosition = null;
            if (!recovering) suspendWalking();
        } else {
            if (walkSuspended) { Modules.get().get(AutoWalk.class).enable(); walkSuspended = false; }
            if (activeStart == 0) activeStart = now;
            if (!started) { started = true; notifyTrip(HighwayWebhook.Event.STARTED, "AFK boat travel started along the fixed heading."); }
            Vec3 position = mc.player.getRootVehicle().position();
            if (lastPosition != null && lastLevel == mc.level && position.distanceToSqr(lastPosition) < 4096) distance += position.distanceTo(lastPosition);
            lastPosition = position; lastLevel = mc.level;
            if (statusMinutes.get() > 0 && statusTimer.due(now, TimeUnit.MINUTES.toNanos(statusMinutes.get())))
                notifyTrip(HighwayWebhook.Event.STATUS, "Boat travel status.");
        }
    }

    private boolean inventoryAccessSafe() {
        return mc.player != null && mc.screen == null && mc.gameMode != null
            && mc.player.containerMenu == mc.player.inventoryMenu && mc.player.inventoryMenu.getCarried().isEmpty();
    }

    private boolean checkEating(long now) {
        AutoEat eat = Modules.get().get(AutoEat.class);
        boolean inventorySafe = inventoryAccessSafe();
        var food = BoatSupplies.food(mc.player.getInventory()::getItem, mc.player.getOffhandItem(), eat.blacklist.get(), inventorySafe);
        double health = healthMonitor.currentHealth(mc.player.getHealth());
        int hunger = mc.player.getFoodData().getFoodLevel();
        boolean needed = autoEat.get() && (health <= eatHealth.get() || hunger <= eatHunger.get());
        boolean accessible = inventorySafe && food.accessible() && eat.isActive() && eat.shouldEat();
        String failure = eatingGuard.evaluate(now, needed, accessible, food.units(), health, hunger, eatingTimeout.get());
        if (failure == null) return true;
        logOut(failure); return false;
    }

    @EventHandler private void boatJoined(GameJoinedEvent event) {
        leavingWorld = false;
        healthMonitor.begin(connection());
        connectedSince = System.nanoTime(); stableConnectionReported = false;
    }

    @EventHandler private void boatLeft(GameLeftEvent event) {
        leavingWorld = true;
        if (!travelControl.automatic()) return;
        if (!wantsLobbyRecovery()) {
            notifyTrip(HighwayWebhook.Event.STOPPED, "Disconnected with boat lobby recovery off; the trip ended.");
            disable(); return;
        }
        if (started) notifyTrip(HighwayWebhook.Event.DISCONNECTED, "Boat trip paused until the next join.");
        // Start one deadline now; repeated joins and recovery stages must not renew it.
        watchdog.evaluate(System.nanoTime(), false, true, false, worldTimeout.get(), recoveryTimeout.get());
        pauseTime(); lastPosition = null; travelWorldReady = false; afkSafety.reset(); eatingGuard.reset();
    }

    private void logOut(String reason) {
        if (safetyDisconnect) return;
        safetyDisconnect = true; stopReason = reason; travelWorldReady = false;
        notifyTrip(HighwayWebhook.Event.SAFETY_LOGOUT, reason);
        var connection = mc.player == null || leavingWorld ? null : mc.player.connection;
        lobby().cancelRecovery();
        holdRestorationForDisconnect();
        disable();
        Modules.get().get(HighwayFly.class).releaseReconnect();
        AutoReconnect reconnect = Modules.get().get(AutoReconnect.class);
        if (reconnect != null) reconnect.disable();
        if (connection != null) connection.handleDisconnect(new ClientboundDisconnectPacket(Component.literal("[Auto Boat Fly] " + reason)));
    }

    private void pauseTime() {
        if (activeStart != 0) { activeNanos += System.nanoTime() - activeStart; activeStart = 0; }
    }

    public int egapsRemaining() {
        if (mc.player == null) return lastEgaps;
        lastEgaps = dev.tntcoating.core.BoatSupplies.egaps(mc.player.getInventory()::getItem, mc.player.getOffhandItem());
        return lastEgaps;
    }

    public void notifyTrip(HighwayWebhook.Event event, String description, HighwayWebhook.Field... extra) {
        notifyTrip(event, description, List.of(extra));
    }
    public void notifyTrip(HighwayWebhook.Event event, String description, List<HighwayWebhook.Field> extra) {
        events.record(event, description);
        if (!webhook.get() || !HighwayWebhook.configured(webhookUrl.get())) return;
        if (!notifications.allow(event, System.nanoTime(), TimeUnit.SECONDS.toNanos(30))) return;
        if (mc.player != null && mc.level != null) lastContext = new HighwayWebhook.Context(mc.player.getScoreboardName(),
            server(), mc.level.dimension().identifier().toString(), mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
        List<HighwayWebhook.Field> fields = new ArrayList<>(extra);
        fields.add(new HighwayWebhook.Field("Heading", heading.get() + " degrees", true));
        fields.add(new HighwayWebhook.Field("Boat speed", String.format(java.util.Locale.ROOT, "%.1f blocks/s", sentSpeedBps()), true));
        fields.add(new HighwayWebhook.Field("Distance travelled", Math.round(distance) + " blocks", true));
        BoatWebhook.send(webhookUrl.get(), event, description, lastContext, fields, egapsRemaining(),
            event == HighwayWebhook.Event.SAFETY_LOGOUT || event == HighwayWebhook.Event.DEATH
                || event == HighwayWebhook.Event.NEEDS_HELP || event == HighwayWebhook.Event.LOGIN_FAILED ? mention.get() : "");
    }

    private String server() { return mc.getCurrentServer() == null ? "Singleplayer" : mc.getCurrentServer().ip; }

    String tripState() {
        boolean recovery = lobby().isNativeBoatRecovering();
        return safetyDisconnect ? "Safety logout" : !isActive() ? "Stopped" : !isAutomaticTravel() ? "Paused for positioning"
            : recovery ? "Recovering" : !sameTripWorld() ? "Waiting for travel world" : !mounted() ? "Waiting for boat" : "Travelling";
    }

    private String tripDetail() {
        return stopReason != null ? stopReason : !isActive() ? "Set a heading, sit in the driver's seat and enable Auto Boat Fly."
            : !isAutomaticTravel() ? "Steer with WASD, jump and sprint, then start automatic travel." : engineStatus();
    }

    public BoatTelemetry dashboardTelemetry() {
        boolean world = mc.player != null && mc.level != null;
        boolean recovery = lobby().isNativeBoatRecovering();
        boolean driver = world && mounted();
        AutoEat eat = Modules.get().get(AutoEat.class);
        long now = System.nanoTime();
        return new BoatTelemetry(isActive(), isAutomaticTravel(), tripState(), tripDetail(), world,
            world ? mc.player.getScoreboardName() : null, server(), world ? mc.level.dimension().identifier().toString() : null,
            world ? mc.player.getX() : 0, world ? mc.player.getY() : 0, world ? mc.player.getZ() : 0, world ? mc.player.getYRot() : 0,
            world ? mc.player.getHealth() : 0, world ? mc.player.getAbsorptionAmount() : 0,
            world ? mc.player.getFoodData().getFoodLevel() : 0, egapsRemaining(), eat.isActive() && eat.eating,
            driver, recovery, lobby().getInfoString(), driver ? sentSpeedBps() : 0,
            tripStart == 0 ? 0 : (tripEnd == 0 ? now : tripEnd) - tripStart, activeNanos + (activeStart == 0 ? 0 : now - activeStart), correctionCount(),
            homes().telemetry(), Modules.get().isActive(AutoWalk.class), lobby().isActive(), homes().isActive(), eat.isActive(),
            heading.get(),
            distance, new HighwayTelemetry.Webhook(webhook.get(), HighwayWebhook.configured(webhookUrl.get()), false),
            speedDescription(), minimumHealth.get(), logoutEgaps.get() ? egapReserve.get() : null);
    }

    @Override public void restoreBorrowedInSavedConfig(net.minecraft.nbt.CompoundTag root) {
        super.restoreBorrowedInSavedConfig(root);
        automation.restoreSavedPreferences(root);
        var marker = lobby().savedNativeBoatTrip().orElse(null);
        String targetServer = travelControl.server();
        boolean active = BoatRecoveryPolicy.saveActive(marker, targetServer, travelControl.dimension(), heading.get(),
            isAutomaticTravel(), autoLobby.get());
        for (var entry : root.getListOrEmpty("modules")) if (entry instanceof net.minecraft.nbt.CompoundTag module
            && name.equals(module.getStringOr("name", ""))) module.putBoolean("active", active);
    }

    @Override public void beginProfileLoad() {
        profileLoading = true;
        if (isActive()) onDeactivate();
        super.beginProfileLoad();
    }
    @Override public void endProfileLoad() {
        profileLoading = false;
        restoringSavedTrip = true;
        try { if (isActive()) onActivate(); } finally { restoringSavedTrip = false; }
    }

    @Override public WWidget getWidget(GuiTheme theme) {
        var list = theme.verticalList();
        list.add(theme.label(tripState() + " · " + tripDetail()));
        var buttons = list.add(theme.horizontalList()).widget();
        buttons.add(theme.button(isAutomaticTravel() ? "Restart automatic travel" : "Start automatic travel")).widget().action = this::startAutomaticTravel;
        buttons.add(theme.button("Pause for positioning")).widget().action = this::pauseAutomaticTravel;

        WTable trip = list.add(theme.table()).expandX().widget();
        trip.add(theme.label("Heading " + heading.get() + "°" + (mc.player == null ? ""
            : String.format(java.util.Locale.ROOT, " (facing %.1f°)", net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()))))).expandX();
        trip.add(theme.button("Use current heading")).widget().action = this::useCurrentHeading;
        trip.row();
        trip.add(theme.label("eGaps carried: " + egapsRemaining())).expandX();
        trip.row();

        list.add(theme.horizontalSeparator("Helpers")).expandX();
        WTable rows = list.add(theme.table()).expandX().widget();
        for (Class<? extends Module> type : List.of(AutoLobby.class, AutoSetHome.class, AutoEat.class)) {
            Module helper = Modules.get().get(type);
            String state = helper instanceof HighwayHelper managed ? managed.activationLabel()
                : !autoEat.get() ? "Not used by this trip" : helper.isActive() ? "Borrowed for this trip" : "Borrowed during automatic travel";
            rows.add(theme.label(helper.title));
            rows.add(theme.label(state)).expandX().widget().color = theme.textSecondaryColor();
            rows.add(theme.button("Settings")).widget().action = () -> mc.setScreen(theme.moduleScreen(helper));
            rows.row();
        }
        FlightDashboard.addStatusRow(theme, rows);

        list.add(theme.horizontalSeparator("Movement")).expandX();
        list.add(speedRecoveryWidget(theme)).expandX();
        if (webhook.get()) list.add(theme.button("Send test webhook")).widget().action = this::sendTestWebhook;
        return list;
    }

    private void sendTestWebhook() {
        if (!HighwayWebhook.configured(webhookUrl.get())) { warning("Set a valid Discord webhook URL before sending a test."); return; }
        HighwayWebhook.Context context = mc.player == null || mc.level == null ? lastContext : new HighwayWebhook.Context(mc.player.getScoreboardName(),
            server(), mc.level.dimension().identifier().toString(), mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ());
        BoatWebhook.send(webhookUrl.get(), HighwayWebhook.Event.TEST,
            "Webhook test from Auto Boat Fly. If this message arrives, the URL and Discord permissions work.", context,
            List.of(new HighwayWebhook.Field("Note", "Manual test; trip events are unchanged.", false)), egapsRemaining(), "");
        info("Test webhook sent. Check the Discord channel.");
    }
}
