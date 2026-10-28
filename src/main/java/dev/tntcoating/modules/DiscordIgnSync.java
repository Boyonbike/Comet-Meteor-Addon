package dev.tntcoating.modules;

import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.ChatClan;
import dev.tntcoating.core.DiscordIgn;
import dev.tntcoating.core.DiscordConnection;
import dev.tntcoating.core.DiscordIgnClient;
import dev.tntcoating.core.DiscordIgnSyncState;
import dev.tntcoating.core.DiscordRoles;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;

/**
 * Keeps the Chat Tools clan list and Auto TPY whitelist in step with the Minecraft names posted in a Discord #ign
 * channel. Every eligible guild member counts; Discord roles supply labels and ordering rather than filtering.
 * The sync never empties a list: a failed or empty read leaves both lists as they were.
 */
public final class DiscordIgnSync extends Module {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Comet Discord IGN sync");
        thread.setDaemon(true);
        return thread;
    });

    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<String> botToken = general.add(new StringSetting.Builder().name("bot-token")
        .description("Token of a dedicated Discord app with Server Members Intent and Message Content Intent. Comet only reads; the token is stored in Meteor's config, so use a separate app, not the clan's admin bot.")
        .defaultValue("").build());
    private final Setting<String> guildId = general.add(new StringSetting.Builder().name("guild-id")
        .description("Discord server ID (turn on Developer Mode, then right-click the server → Copy Server ID).")
        .defaultValue("").build());
    private final Setting<String> channelId = general.add(new StringSetting.Builder().name("ign-channel-id")
        .description("Channel where members post their Minecraft Java usernames, separated by commas, slashes or new lines. The bot needs View Channel and Read Message History there; it does not need Send Messages.")
        .defaultValue("").build());
    private final SettingGroup applying = settings.createGroup("Applying");
    private final Setting<Boolean> applyToClan = applying.add(new BoolSetting.Builder().name("apply-to-clan")
        .description("Keep the Chat Tools clan list in step with the linked players. Names you added by hand stay; names an earlier sync added are removed when Discord no longer lists them.")
        .defaultValue(true).build());
    private final Setting<Boolean> applyToAutoTpy = applying.add(new BoolSetting.Builder().name("apply-to-auto-tpy")
        .description("Keep the Auto TPY whitelist in step with the linked players. The Auto TPY blocklist still wins.")
        .defaultValue(true).build());
    private final Setting<Integer> autoSyncMinutes = applying.add(new IntSetting.Builder().name("auto-sync-minutes")
        .description("Sync while the module is on, this many minutes apart. Zero syncs only when you press Sync now.")
        .defaultValue(5).range(0, 120).sliderRange(0, 60).build());
    private final Setting<List<String>> clanSynced = applying.add(new StringListSetting.Builder().name("clan-synced")
        .description("Names the last sync added to the clan list. Hidden; used to remove them again when they leave.")
        .defaultValue().visible(() -> false).build());
    private final Setting<List<String>> autoTpySynced = applying.add(new StringListSetting.Builder().name("auto-tpy-synced")
        .description("Names the last sync added to the Auto TPY whitelist. Hidden; used to remove them again when they leave.")
        .defaultValue().visible(() -> false).build());

    private final Setting<Integer> ownershipVersion = applying.add(new IntSetting.Builder().name("ownership-version")
        .description("Internal schema for sync-owned names; older ownership data included manual entries.")
        .defaultValue(0).range(0, 1).visible(() -> false).build());

    private final DiscordIgnSyncState syncState = new DiscordIgnSyncState();
    private Future<?> task;
    private String status = "Not synced yet.";
    private long successfulSync;
    private int accountCount;
    /** The last finished read failed or could not be applied, so the player has something to fix. */
    private boolean failed;
    /** Last completed read or rejected attempt, in monotonic milliseconds. */
    private long lastSync;
    private final AtomicLong retryNotBefore = new AtomicLong();

    public DiscordIgnSync() {
        super(TntCoatingAddon.CATEGORY, "discord-ign-sync",
            "Keeps the clan list and Auto TPY whitelist in step with the Minecraft names posted in a Discord #ign channel.");
    }

    @Override public void onActivate() {
        String problem = problem();
        if (problem != null) {
            status = problem;
            error(problem);
            toggle();
            status = problem;
            return;
        }
        syncNow();
    }

    @Override public void onDeactivate() {
        syncState.cancel();
        if (task != null) task.cancel(true);
        task = null;
        lastSync = now();
        status = "Discord sync disabled.";
    }

    @EventHandler private void onTick(TickEvent.Pre event) {
        int minutes = autoSyncMinutes.get();
        if (minutes <= 0 || syncState.busy() || now() < retryNotBefore.get()) return;
        if (now() - lastSync < minutes * 60_000L) return;
        syncNow();
    }

    @Override public WWidget getWidget(GuiTheme theme) {
        var button = theme.button("Sync now");
        button.action = this::syncNow;
        return button;
    }

    public String configuredGuildId() { return guildId.get().strip(); }
    public String configuredChannelId() { return channelId.get().strip(); }
    public boolean hasBotToken() { return !token().isEmpty(); }
    public boolean syncBusy() { return syncState.busy(); }
    public String syncStatus() { return status; }
    /** Monotonic milliseconds; zero until a readable, nonempty roster has been applied. */
    public long lastSuccessfulSync() { return successfulSync; }
    public boolean syncFailed() { return failed; }
    public int syncedAccounts() { return accountCount; }
    public int intervalMinutes() { return autoSyncMinutes.get(); }
    public void setIntervalMinutes(int minutes) {
        mc.execute(() -> autoSyncMinutes.set(Math.max(0, Math.min(120, minutes))));
    }
    public boolean appliesToClan() { return applyToClan.get(); }
    public void appliesToClan(boolean value) { mc.execute(() -> applyToClan.set(value)); }
    public boolean appliesToAutoTpy() { return applyToAutoTpy.get(); }
    public void appliesToAutoTpy(boolean value) { mc.execute(() -> applyToAutoTpy.set(value)); }

    /** Validate all fields before changing any setting. An empty password keeps the stored token. */
    public String configureConnection(String tokenOrBlank, String guild, String channel) {
        if (!mc.isSameThread()) return mc.submit(() -> configureConnection(tokenOrBlank, guild, channel)).join();
        String nextToken = DiscordConnection.replacementToken(tokenOrBlank, botToken.get());
        String nextGuild = guild == null ? "" : guild.strip();
        String nextChannel = channel == null ? "" : channel.strip();
        String problem = DiscordConnection.problem(nextToken, nextGuild, nextChannel);
        if (problem != null) {
            status = problem;
            return problem;
        }
        botToken.set(nextToken);
        guildId.set(nextGuild);
        channelId.set(nextChannel);
        // Role access is bound to its source: a changed guild or channel must stop admitting the old roster now.
        ChatTools chatTools = Modules.get().get(ChatTools.class);
        if (chatTools != null) chatTools.refreshAutoTpyPolicy();
        if (!syncState.busy()) status = "Connection saved. Ready to sync.";
        return null;
    }

    /** Reads Discord now and applies the result, off the game thread. Safe to call from a button or a key press. */
    public void syncNow() {
        if (!mc.isSameThread()) {
            mc.execute(this::syncNow);
            return;
        }
        if (syncState.busy()) {
            warning("Still reading Discord; wait for the current sync to finish.");
            return;
        }
        if (now() < retryNotBefore.get()) {
            status = "Discord is rate limiting the sync. Wait before trying again.";
            warning("Discord is rate limiting the sync. Wait %d seconds.", (retryNotBefore.get() - now() + 999) / 1000);
            return;
        }
        lastSync = now();
        String problem = problem();
        if (problem != null) {
            status = problem;
            error(problem);
            return;
        }
        ChatTools chatTools = Modules.get().get(ChatTools.class);
        if (chatTools == null) {
            status = "Chat Tools is not available, so nothing was changed.";
            error(status);
            return;
        }
        var config = configuration();
        var ticket = syncState.begin(config, chatTools.savedClanMembers(), chatTools.autoTpyWhitelist());
        status = "Reading Discord members and #ign...";
        info(status);
        task = WORKER.submit(() -> {
            try {
                DiscordIgnClient.Sync sync = new DiscordIgnClient(DiscordIgnClient.live(), config.token(), this::deferRetry)
                    .fetch(config.guildId(), config.channelId());
                mc.execute(() -> finished(ticket, sync));
            } catch (DiscordIgnClient.ApiException exception) {
                mc.execute(() -> failed(ticket, exception.explanation(), exception.retryDelayMillis()));
            } catch (IOException exception) {
                MeteorClient.LOG.warn("Reading Discord IGNs failed", exception);
                mc.execute(() -> failed(ticket, exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "Could not reach Discord; the clan list and whitelist were left unchanged."
                    : "Could not read Discord: " + exception.getMessage() + " The lists were left unchanged.", 0));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                mc.execute(() -> failed(ticket, "The Discord sync was interrupted; the lists were left unchanged.", 0));
            } catch (RuntimeException exception) {
                MeteorClient.LOG.warn("Reading Discord IGNs failed", exception);
                mc.execute(() -> failed(ticket, "Discord returned data Comet could not read; the lists were left unchanged.", 0));
            }
        });
    }

    private DiscordIgnSyncState.Settings configuration() {
        return new DiscordIgnSyncState.Settings(token(), guildId.get().strip(), channelId.get().strip(),
            applyToClan.get(), applyToAutoTpy.get());
    }

    /** Only the matching, unchanged read may finish; old callbacks cannot release a newer read. */
    private boolean accept(DiscordIgnSyncState.Ticket ticket) {
        if (!syncState.isCurrent(ticket)) return false;
        task = null;
        lastSync = now();
        ChatTools chatTools = Modules.get().get(ChatTools.class);
        if (chatTools == null) {
            syncState.cancel();
            status = "Chat Tools is not available, so nothing was changed.";
            error(status);
            return false;
        }
        boolean accepted = syncState.complete(ticket, configuration(), chatTools.savedClanMembers(), chatTools.autoTpyWhitelist());
        if (!accepted) {
            status = "Discord settings or destination lists changed during the read; discarded the result. Sync again to apply the new settings.";
            warning(status);
        }
        return accepted;
    }

    private void finished(DiscordIgnSyncState.Ticket ticket, DiscordIgnClient.Sync sync) {
        if (!accept(ticket)) return;
        DiscordIgn.Result result = sync.result();
        ChatTools chatTools = Modules.get().get(ChatTools.class);
        if (chatTools == null) {
            status = "Chat Tools is not available, so nothing was changed.";
            error(status);
            return;
        }
        if (result.unreadable()) {
            failed = true;
            status = "One or more #ign posts have no readable text. Enable Message Content Intent and remove empty or attachment-only posts from eligible members; the lists were left unchanged.";
            error(status);
            return;
        }
        if (result.names().isEmpty()) {
            // Readable and complete, but nobody is linked any more: drop role access at once while the
            // clan list and whitelist stay as they were.
            chatTools.setDiscordRoles(Map.of(), source(ticket));
            status = "No IGNs found in #ign; the lists were left unchanged and Discord role access was cleared.";
            warning(status);
            warnConflicts(result);
            countInvalid(result);
            return;
        }
        chatTools.setDiscordRoles(sync.roles(), source(ticket));
        chatTools.setDiscordUsernames(result.usernames());
        boolean trustedOwnership = ownershipVersion.get() >= 1;
        boolean legacyNames = !trustedOwnership && (!clanSynced.get().isEmpty() || !autoTpySynced.get().isEmpty());
        List<String> changes = new ArrayList<>();
        if (applyToClan.get()) {
            List<String> before = chatTools.savedClanMembers();
            DiscordIgn.Update update = DiscordIgn.update(before, clanSynced.get(), result.names(), trustedOwnership);
            List<String> after = update.names();
            chatTools.setClanMembers(after);
            clanSynced.set(update.managed());
            changes.add("clan list +" + added(before, after) + "/-" + removed(before, after));
        }
        if (applyToAutoTpy.get()) {
            List<String> before = chatTools.autoTpyWhitelist();
            DiscordIgn.Update update = DiscordIgn.update(before, autoTpySynced.get(), result.names(), trustedOwnership);
            List<String> after = update.names();
            chatTools.setAutoTpyWhitelist(after);
            autoTpySynced.set(update.managed());
            changes.add("Auto TPY whitelist +" + added(before, after) + "/-" + removed(before, after));
        }

        if (!trustedOwnership) {
            if (!applyToClan.get()) clanSynced.set(List.of());
            if (!applyToAutoTpy.get()) autoTpySynced.set(List.of());
            ownershipVersion.set(1);
            if (legacyNames) warning("Upgraded Discord sync tracking: existing entries were preserved as manual because old metadata could not distinguish them. Review the clan list and Auto TPY whitelist for old members.");
        }
        successfulSync = now();
        failed = false;
        accountCount = result.names().size();
        status = "Synced " + accountCount + " account" + plural(accountCount) + ".";
        String members = result.members() + " guild member" + plural(result.members());
        info("Discord IGN sync: %d IGN%s from %s (%s).", result.names().size(), plural(result.names().size()), members,
            String.join("; ", changes));
        warnConflicts(result);
        countInvalid(result);
    }

    /** Guild and channel of the accepted read, so role access can never outlive the source that granted it. */
    private static String source(DiscordIgnSyncState.Ticket ticket) {
        return DiscordRoles.source(ticket.settings().guildId(), ticket.settings().channelId());
    }

    private void warnConflicts(DiscordIgn.Result result) {
        if (!result.conflicts().isEmpty()) {
            warning("Skipped names claimed by more than one Discord user: %s.", String.join(", ", result.conflicts()));
        }
    }

    private void countInvalid(DiscordIgn.Result result) {
        if (result.invalid() > 0) {
            info("Ignored %d message%s that were not valid Java username lists.", result.invalid(), plural(result.invalid()));
        }
    }

    private void failed(DiscordIgnSyncState.Ticket ticket, String message, long retryMillis) {
        // Even discarded reads must not permit another request before Discord's retry deadline.
        if (retryMillis > 0) deferRetry(retryMillis);
        if (!accept(ticket)) return;
        failed = true;
        String secret = ticket.settings().token();
        status = secret.isEmpty() ? message : message.replace(secret, "[redacted]");
        error(status);
    }

    private void deferRetry(long millis) {
        long deadline = now() + millis;
        retryNotBefore.accumulateAndGet(deadline, Math::max);
    }

    /** What stops a sync, or null when the settings are usable. */
    private String problem() {
        String problem = DiscordConnection.problem(token(), configuredGuildId(), configuredChannelId());
        if (problem != null) return problem;
        if (!applyToClan.get() && !applyToAutoTpy.get()) {
            return "Turn on apply-to-clan, apply-to-auto-tpy, or both; otherwise there is nowhere to put the names.";
        }
        return null;
    }

    private String token() {
        return DiscordConnection.token(botToken.get());
    }

    private static String plural(int count) {
        return count == 1 ? "" : "s";
    }

    private static int added(List<String> before, List<String> after) {
        Set<String> had = ChatClan.keys(before);
        int count = 0;
        for (String name : after) if (!had.contains(name.toLowerCase(Locale.ROOT))) count++;
        return count;
    }

    private static int removed(List<String> before, List<String> after) {
        Set<String> has = ChatClan.keys(after);
        int count = 0;
        for (String name : before) if (!has.contains(name.toLowerCase(Locale.ROOT))) count++;
        return count;
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
