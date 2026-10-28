/*
 * Comet highway travel helpers.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.modules;

import dev.tntcoating.core.ChatSender;
import dev.tntcoating.core.HighwayWebhook;
import dev.tntcoating.core.dashboard.HighwayTelemetry;
import dev.tntcoating.core.HomeOverride;
import dev.tntcoating.core.SetHomeScheduler;
import dev.tntcoating.core.SetHomeStore;
import dev.tntcoating.core.ChatHomeNames;
import dev.tntcoating.core.SetHomePacer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import dev.tntcoating.modules.HighwayFly.AutoSetHomeStatus;
import dev.tntcoating.modules.HighwayFly.HomeTiming;

/** Independent helper, also available to Highway Fly through coordinated activation. */
public final class AutoSetHome extends HighwayHelper {
    public AutoSetHome() { super("auto-set-home", "Automatically update a home with server-aware scheduling."); }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private SetHomeStore store;
    private SetHomeScheduler scheduler;
    private String account;
    private String accountLabel;
    private String homeServer;
    private boolean storeWarning;
    private boolean invalidHomeWarning;
    private Connection connectionSession;
    private Screen confirmationScreen;

    private final Setting<String> home = sgGeneral.add(new StringSetting.Builder()
        .name("home")
        .description("The home name to move. It must already exist if the server asks to override it.")
        .defaultValue("test")
        .build());

    private final Setting<HomeTiming> timing = sgGeneral.add(new EnumSetting.Builder<HomeTiming>()
        .name("timing")
        .description("Auto detect reads the server's sethome cooldown from chat. Fixed timer sends at a steady interval instead.")
        .defaultValue(HomeTiming.AUTO)
        .build());

    private final Setting<Integer> intervalSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("interval-seconds")
        .description("Fixed timer: real seconds between attempts, even during a server cooldown. Auto detect: discovery/fallback delay until the server cooldown is learned.")
        .defaultValue(60)
        .range(5, 3600)
        .sliderRange(5, 300)
        .build());

    private final Setting<Integer> cooldownBufferSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-buffer-seconds")
        .description("Auto detect only: extra seconds to wait after the server's cooldown ends, so the next command is not sent on the exact boundary.")
        .defaultValue((int) (SetHomePacer.DEFAULT_BUFFER_MILLIS / 1000))
        .range(0, 120)
        .sliderRange(0, 60)
        .visible(() -> timing.get() == HomeTiming.AUTO)
        .build());

    private final Setting<Integer> retryDelaySeconds = sgGeneral.add(new IntSetting.Builder()
        .name("retry-delay-seconds")
        .description("Auto detect only: seconds to wait after the server refuses a command without a usable countdown (for example \"wait 0m 0sec\") before trying again.")
        .defaultValue(60)
        .range(0, 3600)
        .sliderRange(0, 120)
        .visible(() -> timing.get() == HomeTiming.AUTO)
        .build());

    private final Setting<Boolean> override = sgGeneral.add(new BoolSetting.Builder()
        .name("override")
        .description("Click Override when the server asks to replace the existing home.")
        .defaultValue(true)
        .build());

    private final Setting<String> dialogMatch = sgGeneral.add(new StringSetting.Builder()
        .name("dialog-match")
        .description("Text that must appear in the confirmation dialog's title or body. Blank disables clicking.")
        .defaultValue("home")
        .visible(override::get)
        .build());

    private final Setting<String> buttonMatch = sgGeneral.add(new StringSetting.Builder()
        .name("button-match")
        .description("Text on the confirmation button to click. Cancel is left alone.")
        .defaultValue("override")
        .visible(override::get)
        .build());

    private final Setting<Integer> timeoutSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("timeout-seconds")
        .description("How long to wait for the override dialog after sending the command.")
        .defaultValue(10)
        .range(1, 60)
        .sliderRange(1, 30)
        .visible(override::get)
        .build());

    protected void started() {
        confirmationScreen = null;
        if (scheduler != null) scheduler.cancel();
    }

    protected void stopped() {
        confirmationScreen = null;
        connectionSession = null;
        if (scheduler != null) scheduler.cancel();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!allowed() || mc.player == null || mc.level == null || mc.getConnection() == null) {
            stopped();
            return;
        }
        checkSession();
        scheduler.expire();
        if (scheduler.waiting()) {
            if (!home.get().strip().equals(scheduler.requestedHome())) {
                scheduler.cancel();
                confirmationScreen = null;
                return;
            }
            if (scheduler.waitingForDialog() && override.get() && tryOverride()) {
                scheduler.clicked(location());
                confirmationScreen = null;
            }
            return;
        }
        confirmationScreen = null;
        if (mc.screen != null || !scheduler.ready(timing.get() == HomeTiming.FIXED, intervalMillis(), bufferMillis())) return;
        String name = ChatHomeNames.valid(home.get());
        if (name == null) {
            if (!invalidHomeWarning) warning("Auto set home: choose a valid single home name.");
            invalidHomeWarning = true;
            return;
        }
        invalidHomeWarning = false;
        // Record and persist before dispatch so even a crash after sending retains the attempt timestamp.
        scheduler.sent(name, location(), override.get(), timeoutSeconds.get() * 1_000L, intervalMillis());
        save();
        mc.getConnection().sendCommand(HomeOverride.command(name));
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof ClientboundSystemChatPacket packet)) return;
        mc.execute(() -> {
            if (!allowed()
                || mc.player == null || mc.level == null || mc.getConnection() == null
                || event.connection != mc.getConnection().getConnection()) return;
            checkSession();
            String text = packet.content().getString();
            if (ChatSender.fromPlayer(text, this::online)) return;
            SetHomeScheduler.Reply reply = scheduler.message(text, retryMillis());
            if (reply == SetHomeScheduler.Reply.IGNORED) return;
            confirmationScreen = null;
            save();
            if (reply == SetHomeScheduler.Reply.SUCCESS) {
                SetHomeScheduler.Entry entry = scheduler.state().history().getFirst();
                List<HighwayWebhook.Field> fields = new ArrayList<>(highway().isActive() ? highway().routeFields() : List.<HighwayWebhook.Field>of());
                fields.add(new HighwayWebhook.Field("Home", entry.home(), true, true));
                fields.add(new HighwayWebhook.Field("Confirmed", java.time.Instant.ofEpochMilli(entry.confirmedAt()).toString(), true));
                fields.add(new HighwayWebhook.Field("Home position", entry.location().x() + ", " + entry.location().y() + ", " + entry.location().z(), true));
                fields.add(new HighwayWebhook.Field("Home dimension", entry.location().dimension(), true));
                fields.add(new HighwayWebhook.Field("Health", String.valueOf(mc.player.getHealth()), true));
                AutoBoatFly boat = meteordevelopment.meteorclient.systems.modules.Modules.get().get(AutoBoatFly.class);
                if (boat != null && boat.isActive()) boat.notifyTrip(HighwayWebhook.Event.HOME_REPLACED,
                    "Successfully replaced home " + entry.home() + ".", fields);
                else highway().notifyWebhook(HighwayWebhook.Event.HOME_REPLACED, "Successfully replaced home " + entry.home() + ".", fields);
            }
        });
    }

    private void checkSession() {
        if (store == null) {
            store = new SetHomeStore(MeteorClient.FOLDER.toPath().resolve("auto-set-home-state.json"));
            if (store.loadError() != null) warning("Auto set home: saved state could not be loaded; learning again.");
        }
        String currentAccount = mc.player == null ? mc.getUser().getProfileId().toString() : mc.player.getUUID().toString();
        boolean connected = mc.player != null && mc.getConnection() != null;
        String currentServer = connected ? SetHomeStore.normaliseServer(serverAddress())
            : currentAccount.equals(account) ? homeServer : store.latestServer(currentAccount);
        if (currentServer == null) {
            scheduler = null;
            account = null;
            accountLabel = null;
            homeServer = null;
            connectionSession = null;
            confirmationScreen = null;
            return;
        }
        if (scheduler == null || !currentAccount.equals(account) || !currentServer.equals(homeServer)) {
            account = currentAccount;
            accountLabel = (mc.player == null ? mc.getUser().getName() : mc.player.getName().getString()) + " (" + account + ")";
            homeServer = currentServer;
            scheduler = new SetHomeScheduler(System::currentTimeMillis, store.load(account, homeServer));
            confirmationScreen = null;
        }
        Connection current = connected ? mc.getConnection().getConnection() : null;
        if (current != connectionSession) {
            connectionSession = current;
            scheduler.cancel();
            confirmationScreen = null;
        }
    }

    private void save() {
        try {
            store.save(account, homeServer, scheduler.state());
            storeWarning = false;
        } catch (IOException | RuntimeException e) {
            if (!storeWarning) warning("Auto set home: saving failed; timing may be lost after restart.");
            storeWarning = true;
            MeteorClient.LOG.warn("Saving auto set home state failed", e);
        }
    }

    private SetHomeScheduler.Location location() {
        return new SetHomeScheduler.Location(mc.player.getX(), mc.player.getY(), mc.player.getZ(), dimensionId());
    }

    private boolean tryOverride() {
        Screen screen = mc.screen;
        if (!(screen instanceof DialogScreen<?>) || screen != confirmationScreen) return false;

        String phrase = dialogMatch.get();
        String buttonPhrase = buttonMatch.get();
        if (phrase.isBlank() || buttonPhrase.isBlank()) return false;

        List<String> labels = new ArrayList<>();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget) labels.add(widget.getMessage().getString());
        }
        if (!HomeOverride.isHomeDialog(screen.getTitle().getString(), labels, phrase)) return false;

        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractButton button
                && button.active && button.visible
                && HomeOverride.isOverrideButton(button.getMessage().getString(), buttonPhrase)) {
                button.onClick(new MouseButtonEvent(
                    button.getX() + button.getWidth() / 2.0,
                    button.getY() + button.getHeight() / 2.0,
                    new MouseButtonInfo(0, 0)
                ), false);
                return true;
            }
        }
        return false;
    }

    private void onScreen(Screen screen) {
        if (scheduler != null && scheduler.waitingForDialog() && confirmationScreen == null && screen instanceof DialogScreen<?>) confirmationScreen = screen;
    }

    private long intervalMillis() {
        return Math.max(1, intervalSeconds.get() * 1_000L);
    }

    private long bufferMillis() {
        return Math.max(0, cooldownBufferSeconds.get() * 1_000L);
    }

    private long retryMillis() { return Math.max(0, retryDelaySeconds.get() * 1_000L); }

    public AutoSetHomeStatus status() {
        checkSession();
        if (scheduler == null) return null;
        boolean fixed = timing.get() == HomeTiming.FIXED;
        SetHomeScheduler.Status schedule = scheduler.snapshot(fixed, intervalMillis(), bufferMillis());
        boolean paused = !isActive() || mc.player == null || mc.level == null
            || !allowed();
        String label = schedule.cooldownMillis() > 0
            ? dev.tntcoating.core.ChatTeleportState.remaining(schedule.cooldownMillis())
            : schedule.bufferMillis() > 0 ? "Buffer " + dev.tntcoating.core.ChatTeleportState.remaining(schedule.bufferMillis()) : "";
        return new AutoSetHomeStatus(timing.get().toString(), home.get().strip(), schedule.waiting(),
            schedule.waitMillis(), intervalMillis(), fixed, label, scheduler.state().cooldownEnd() != 0,
            schedule, paused, accountLabel, homeServer, scheduler.state().history());
    }

    HighwayTelemetry.Home telemetry() {
        AutoSetHomeStatus status = status();
        return status == null ? null : new HighwayTelemetry.Home(status.mode(), status.home(), status.waiting(),
            status.waitMillis(), status.intervalMillis(), status.fixedTimer(), status.cooldownLabel(),
            status.cooldownKnown(), status.schedule(), status.paused(), status.account(), status.server(), status.history());
    }

    private boolean online(String name) {
        return mc.getConnection() != null && mc.getConnection().getPlayerInfoIgnoreCase(name) != null;
    }

    private boolean allowed() { return isActive() && !loadingProfile() && highway().homesAllowed(); }
    @EventHandler private void onOpenScreen(OpenScreenEvent event) { if (allowed()) onScreen(event.screen); }
    @EventHandler private void onLeft(GameLeftEvent event) { stopped(); }
    private String serverAddress() { return mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip; }
    private String dimensionId() { return mc.level == null ? null : mc.level.dimension().identifier().toString(); }

}
