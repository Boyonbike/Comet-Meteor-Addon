package dev.tntcoating.modules;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.ChatClan;
import dev.tntcoating.core.DiscordRoles;
import dev.tntcoating.core.DiscordIgn;
import dev.tntcoating.core.AutoTpyController;
import dev.tntcoating.core.AutoTpyPolicy;
import dev.tntcoating.core.AutoTpyQueue;
import dev.tntcoating.core.ChatCommandTemplate;
import dev.tntcoating.core.ChatHomeNames;
import dev.tntcoating.core.ChatHomeEdit;
import dev.tntcoating.core.ChatSender;
import dev.tntcoating.core.ChatTeleportState;
import dev.tntcoating.core.ChatTpaRequests;
import dev.tntcoating.core.ChatFriendHighlight;
import dev.tntcoating.core.ChatMessages;
import dev.tntcoating.core.ChatHomeFavorites;
import dev.tntcoating.core.ChatSavedCooldowns;
import dev.tntcoating.core.ChatRecentPlayers;
import dev.tntcoating.core.ChatWhisperQueue;
import dev.tntcoating.core.ChatPublicQueue;
import dev.tntcoating.core.ChatCountdowns;
import dev.tntcoating.gui.ChatToolsScreen;
import dev.tntcoating.mixin.EntityArgumentAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.settings.ColorSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.mixin.MinecraftServerAccessor;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.friends.Friend;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.LevelResource;

public final class ChatTools extends Module {
    private static final SystemToast.SystemToastId DM_TOAST = new SystemToast.SystemToastId(5_000);
    private static final Pattern HOME_CLICK = Pattern.compile("^/(?:[a-z0-9_.-]+:)?home\\s+\\S+\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int FRIEND_REFRESH_TICKS = 20;
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Boolean> autoDetect = general.add(new BoolSetting.Builder().name("auto-detect")
        .description("Add commands from the server's command tree whose next argument accepts a player name.").defaultValue(true).build());
    private final Setting<Boolean> highlightFriendNames = general.add(new BoolSetting.Builder().name("highlight-friends")
        .description("Colour Meteor friends' names in chat.").defaultValue(true).build());
    private final Setting<SettingColor> friendChatColor = general.add(new ColorSetting.Builder().name("friend-chat-color")
        .description("Colour of Meteor friends' names in chat and in the Chat Tools window.").defaultValue(new SettingColor(0, 255, 180)).build());
    private final Setting<Boolean> highlightFriendsTab = general.add(new BoolSetting.Builder().name("highlight-friends-tab")
        .description("Colour Meteor friends' names in the tab list.").defaultValue(true).build());
    private final Setting<SettingColor> friendTabColor = general.add(new ColorSetting.Builder().name("friend-tab-color")
        .description("Colour of Meteor friends' names in the tab list.").defaultValue(new SettingColor(0, 255, 180)).build());
    private final Setting<Boolean> highlightFriendRows = general.add(new BoolSetting.Builder().name("highlight-friend-rows")
        .description("Highlight Meteor friends' rows in the tab list with a coloured bar behind them.").defaultValue(false).build());
    private final Setting<SettingColor> friendRowColor = general.add(new ColorSetting.Builder().name("friend-row-color")
        .description("Colour of the bar behind Meteor friends' rows in the tab list. Its alpha sets how strong the bar is.")
        .defaultValue(new SettingColor(0, 255, 180, 96)).build());
    private final Setting<Boolean> runOnClick = general.add(new BoolSetting.Builder().name("run-complete-commands")
        .description("Run complete player commands immediately. Off fills the Chat Tools input for confirmation.").defaultValue(false).build());
    private final Setting<List<String>> configured = general.add(new StringListSetting.Builder().name("command-options")
        .description("Extra choices: 'Label | /command {player}'. A trailing space leaves the command open for more text. %player% also works.")
        .defaultValue("Message | /msg {player} ", "Whisper | /w {player} ", "Teleport Request | /tpa {player}").build());
    private final Setting<List<String>> configuredHomes = general.add(new StringListSetting.Builder().name("homes")
        .description("Extra home names for servers that do not provide /home tab suggestions or clickable /homes output.")
        .defaultValue().build());
    private final Setting<List<String>> savedFavorites = general.add(new StringListSetting.Builder().name("favorite-homes")
        .description("Saved home favourites for each server and account. Managed from the Chat Tools Homes tab.")
        .defaultValue().visible(() -> false).build());
    private final Setting<Double> windowScale = general.add(new DoubleSetting.Builder().name("window-scale")
        .description("Size of the Chat Tools window. Zero sizes it from your screen height; larger values make everything bigger.")
        .defaultValue(0).range(0, 4).sliderRange(0, 4).decimalPlaces(2).build());
    private final Setting<Integer> requestTimeout = general.add(new IntSetting.Builder().name("tpa-request-timeout")
        .description("Seconds to retain incoming requests when the server does not report their expiry.")
        .defaultValue(120).range(5, 3600).sliderRange(30, 300).build());
    private final SettingGroup autoTpySettings = settings.createGroup("Auto TPY");
    private final Setting<Boolean> autoTpyEnabledSetting = autoTpySettings.add(new BoolSetting.Builder().name("auto-tpy")
        .description("Buffer and automatically accept eligible incoming requests. Configure the whitelist first.").defaultValue(false)
        .onChanged(ignored -> syncAutoTpy()).build());
    private final Setting<Boolean> autoWhitelistOnly = autoTpySettings.add(new BoolSetting.Builder().name("auto-tpy-whitelist-only")
        .description("Only allow whitelisted players, plus Meteor friends when the bypass is enabled.").defaultValue(true).build());
    private final Setting<Boolean> autoAllowFriends = autoTpySettings.add(new BoolSetting.Builder().name("auto-tpy-allow-meteor-friends")
        .description("Allow Meteor friends without whitelist membership. The blocklist still wins.").defaultValue(false).build());
    private final Setting<Boolean> autoToMe = autoTpySettings.add(new BoolSetting.Builder().name("auto-tpy-to-me")
        .description("Accept requests for players to teleport to you.").defaultValue(true).build());
    private final Setting<Boolean> autoToThem = autoTpySettings.add(new BoolSetting.Builder().name("auto-tpy-to-them")
        .description("Accept requests asking you to teleport to someone else.").defaultValue(false).build());
    private final Setting<List<String>> autoWhitelist = autoTpySettings.add(new StringListSetting.Builder().name("auto-tpy-whitelist")
        .description("Allowed usernames. Manage or import CSV in Chat Tools > Overview > Auto TPY.").defaultValue().build());
    private final Setting<List<String>> autoBlocklist = autoTpySettings.add(new StringListSetting.Builder().name("auto-tpy-blocklist")
        .description("Never accept these players, including friends and whitelisted names.").defaultValue().build());
    private final Setting<List<String>> autoDiscordRoleIds = autoTpySettings.add(new StringListSetting.Builder().name("auto-tpy-discord-role-ids")
        .description("Discord role IDs whose synced accounts may be accepted. Empty uses the whitelist settings.").defaultValue().build());
    private final Setting<Integer> autoInterval = autoInt("auto-tpy-interval-ms", "Minimum milliseconds between accept commands; zero sends at most once per client tick.", 500, 0, 60000);
    private final Setting<Boolean> autoAdaptive = autoTpySettings.add(new BoolSetting.Builder().name("auto-tpy-adaptive")
        .description("Learn pacing from attributable server confirmations and rate-limit replies. Unknown replies never train faster.").defaultValue(false).build());
    private final Setting<Integer> autoMinimum = autoTpySettings.add(new IntSetting.Builder().name("auto-tpy-min-interval-ms")
        .description("Fastest adaptive interval.").defaultValue(50).range(0,60000).sliderRange(0,5000).onChanged(ignored -> autoBounds(true)).build());
    private final Setting<Integer> autoMaximum = autoTpySettings.add(new IntSetting.Builder().name("auto-tpy-max-interval-ms")
        .description("Slowest adaptive interval; explicit server pauses can be longer.").defaultValue(5000).range(0,60000).sliderRange(0,10000).onChanged(ignored -> autoBounds(false)).build());
    private final Setting<Integer> autoCapacity = autoInt("auto-tpy-buffer-limit", "Maximum live buffered requests. New players are rejected visibly when full.", 1024, 64, 16384);
    private final Setting<Integer> autoInFlight = autoInt("auto-tpy-in-flight", "Maximum commands awaiting replies while incoming requests keep buffering.", 4, 1, 32);
    private final Setting<Integer> autoAttempts = autoInt("auto-tpy-max-attempts", "Total attempts including the first send.", 3, 1, 10);
    private final Setting<Integer> autoReplyTimeout = autoInt("auto-tpy-reply-timeout-ms", "Wait for confirmation before scheduling a priority retry.", 3000, 500, 30000);
    private final Setting<Integer> autoBackoff = autoInt("auto-tpy-retry-backoff-ms", "Initial retry delay, doubling each retry up to 30 seconds.", 1000, 0, 30000);

    private final SettingGroup cooldownSettings = settings.createGroup("Teleport Cooldowns");
    private final Setting<Integer> homeCooldown = cooldownSettings.add(new IntSetting.Builder().name("home-cooldown-seconds")
        .description("Estimated cooldown after a successful home teleport until the server reports one. Learned and manual lengths in the Chat Tools window replace it. Zero disables the fallback.")
        .defaultValue(30).range(0, 3600).sliderRange(0, 300).onChanged(ignored -> refreshCooldowns()).build());
    private final Setting<Integer> tpaCooldown = cooldownSettings.add(new IntSetting.Builder().name("tp-cooldown-seconds")
        .description("Estimated cooldown after a successful player teleport until the server reports one. Learned and manual lengths in the Chat Tools window replace it. Zero disables the fallback.")
        .defaultValue(30).range(0, 3600).sliderRange(0, 300).onChanged(ignored -> refreshCooldowns()).build());
    private final Setting<List<String>> savedCooldowns = cooldownSettings.add(new StringListSetting.Builder().name("saved-cooldowns")
        .description("Cooldown lengths for each server and account: learned from server replies or set manually in the Chat Tools window.")
        .defaultValue().visible(() -> false).build());
    private final SettingGroup messaging = settings.createGroup("Messages");
    private final Setting<Keybind> openPopupKey = messaging.add(new KeybindSetting.Builder().name("open-popup-key")
        .description("Key that opens the Chat Tools window: messages, friends, homes and teleports.")
        .defaultValue(Keybind.fromKey(org.lwjgl.glfw.GLFW.GLFW_KEY_Y)).action(this::openPopupFromKey).build());
    private final Setting<Boolean> hideMessages = messaging.add(new BoolSetting.Builder().name("hide-private-messages")
        .description("Hide recognised private messages from normal chat after capturing them in the popup.").defaultValue(false).build());
    private final Setting<Boolean> messageToasts = messaging.add(new BoolSetting.Builder().name("dm-toasts")
        .description("Notify you when a private message arrives outside the conversation you are reading.").defaultValue(true).build());
    private final Setting<String> messageCommand = messaging.add(new StringSetting.Builder().name("message-command")
        .description("Command used by DM windows; {player} is replaced by the recipient.")
        .defaultValue("/msg {player}").build());
    private final Setting<List<String>> incomingPatterns = messaging.add(new StringListSetting.Builder().name("incoming-dm-patterns")
        .description("Extra full-line regexes: group 1 is the username, group 2 is the message.")
        .defaultValue().onChanged(ignored -> refreshMessagePatterns()).build());
    private final Setting<List<String>> outgoingPatterns = messaging.add(new StringListSetting.Builder().name("outgoing-dm-patterns")
        .description("Extra full-line regexes for server echoes: group 1 username, group 2 message.")
        .defaultValue().onChanged(ignored -> refreshMessagePatterns()).build());
    private final Setting<List<String>> savedRecents = messaging.add(new StringListSetting.Builder().name("recent-players")
        .description("Recently contacted players for each server and account, so the Chat Tools list survives rejoins and restarts.")
        .defaultValue().visible(() -> false).build());
    private final Setting<Boolean> queueChat = messaging.add(new BoolSetting.Builder().name("queue-chat")
        .description("Enter adds public chat to a queue that sends at the server's pace, so you can keep typing.").defaultValue(true).build());
    private final Setting<Integer> chatCooldown = messaging.add(new IntSetting.Builder().name("chat-cooldown-ms")
        .description("Milliseconds between queued public chat messages. A longer wait the server reports still wins.")
        .defaultValue(5000).range(0, 30000).sliderRange(0, 10000).build());
    private final Setting<Integer> whisperCooldown = messaging.add(new IntSetting.Builder().name("whisper-cooldown-ms")
        .description("Milliseconds between queued private messages. A longer wait the server reports still wins.")
        .defaultValue(2500).range(0, 30000).sliderRange(0, 10000).build());
    private final Setting<Boolean> waitingNotice = messaging.add(new BoolSetting.Builder().name("waiting-notification")
        .description("Keep a notification on screen while teleport requests, unread messages or sync problems wait for you.")
        .defaultValue(true).build());
    private final SettingGroup clan = settings.createGroup("Clan");
    private final Setting<List<String>> clanMembers = clan.add(new StringListSetting.Builder().name("clan-members")
        .description("Minecraft usernames maintained by Discord IGN Sync.")
        .defaultValue().visible(() -> false).build());
    private final Setting<String> savedDiscordRoles = clan.add(new StringSetting.Builder().name("discord-role-roster")
        .description("Saved Discord roles and hierarchy for the synced Minecraft accounts.")
        .defaultValue("{}").visible(() -> false).build());
    private final Setting<String> savedDiscordRoleSource = clan.add(new StringSetting.Builder().name("discord-role-source")
        .description("Guild and channel that produced the saved role roster. Role access is ignored once the connection no longer matches it.")
        .defaultValue("").visible(() -> false).build());
    private final Setting<String> savedDiscordUsernames = clan.add(new StringSetting.Builder().name("discord-username-roster")
        .description("Saved Discord usernames for each synced Minecraft account.")
        .defaultValue("{}").visible(() -> false).build());
    private String cachedDiscordUsernameData;
    private Map<String, String> cachedDiscordUsernames = Map.of();
    private String cachedDiscordRoleData;
    private Map<String, List<DiscordRoles.Role>> cachedDiscordRoles = Map.of();
    private final Setting<Boolean> highlightClanChat = clan.add(new BoolSetting.Builder().name("highlight-clan-chat")
        .description("Colour clan members' names in chat. Clan members who are Meteor friends get the friend colour while that is on.").defaultValue(true).build());
    private final Setting<SettingColor> clanChatColor = clan.add(new ColorSetting.Builder().name("clan-chat-color")
        .description("Colour of clan members' names in chat and in the Chat Tools window.").defaultValue(new SettingColor(255, 170, 60)).build());
    private final Setting<Boolean> highlightClanTab = clan.add(new BoolSetting.Builder().name("highlight-clan-tab")
        .description("Colour clan members' names in the tab list.").defaultValue(true).build());
    private final Setting<SettingColor> clanTabColor = clan.add(new ColorSetting.Builder().name("clan-tab-color")
        .description("Colour of clan members' names in the tab list.").defaultValue(new SettingColor(255, 170, 60)).build());
    private final Setting<Boolean> highlightClanRows = clan.add(new BoolSetting.Builder().name("highlight-clan-rows")
        .description("Highlight clan members' rows in the tab list with a coloured bar behind them.").defaultValue(false).build());
    private final Setting<SettingColor> clanRowColor = clan.add(new ColorSetting.Builder().name("clan-row-color")
        .description("Colour of the bar behind clan members' rows in the tab list. Its alpha sets how strong the bar is.")
        .defaultValue(new SettingColor(255, 170, 60, 96)).build());

    private final ChatMessages messages = new ChatMessages();
    private final ChatRecentPlayers recentPlayers = new ChatRecentPlayers();
    private final ChatWhisperQueue whispers = new ChatWhisperQueue();
    private final ChatPublicQueue chats = new ChatPublicQueue();
    private String replyPlayer;
    private final List<String> hotspotDetails = new ArrayList<>();
    private long hotspotReadingUntil;
    private ChatToolsScreen popup;
    private final ChatTeleportState teleports = new ChatTeleportState();
    private final ChatTpaRequests incomingRequests = new ChatTpaRequests();
    private final List<String> discoveredHomes = new ArrayList<>();
    private final List<String> suggestedHomes = new ArrayList<>();
    private Connection session;
    private final AutoTpyController autoTpy = new AutoTpyController();
    private Map<String, List<DiscordRoles.Role>> autoRoleRoster;
    private String autoRoleSource;
    private Set<String> autoRoleSelection = Set.of();
    private Set<String> autoRoleMembers = Set.of();
    private List<DiscordRoles.Role> autoAvailableRoles = List.of();
    private String autoAccount;
    private long autoTick;
    private volatile int homeRequest;
    private int nextHomeRequest = -1_400_000_000;
    private long homeRequestedAt;
    private ChatHomeEdit pendingHomeEdit;
    private long homeEditExpires;
    private Set<String> friendNames = Set.of(), clanNames = Set.of();
    private int friendColor, clanColor;
    private boolean friendsInChat, clanInChat;
    private int friendRefreshTicks;
    private String savedKey, recentKey;
    private ChatSavedCooldowns.Saved saved = ChatSavedCooldowns.Saved.NONE;

    public ChatTools() {
        super(TntCoatingAddon.CATEGORY, "chat-tools", "Gathers messages, friends, homes, teleport requests and server settings in one window.");
    }

    public ChatMessages messages() { checkSession(); return messages; }

    private void refreshMessagePatterns() {
        if (messages != null && incomingPatterns != null && outgoingPatterns != null)
            messages.patterns(incomingPatterns.get(), outgoingPatterns.get());
    }

    private void refreshCooldowns() {
        if (teleports != null && homeCooldown != null && tpaCooldown != null) teleports.cooldowns(homeCooldown.get(), tpaCooldown.get());
    }

    private void openPopupFromKey() {
        if (mc.screen == null) openPopup(null);
    }

    public void openPopup(String player) {
        if (!isActive() || mc.player == null || mc.getConnection() == null) return;
        checkSession();
        if (popup == null) popup = new ChatToolsScreen(this);
        if (mc.screen != popup) {
            popup.returnTo(mc.screen);
            mc.setScreen(popup);
        }
        if (player != null && ChatMessages.validName(player)) popup.openConversation(player);
    }

    @Override public WWidget getWidget(GuiTheme theme) {
        var button = theme.button("Open Chat Tools");
        button.action = () -> openPopup(null);
        return button;
    }

    private Setting<Integer> autoInt(String name, String description, int value, int min, int max) {
        return autoTpySettings.add(new IntSetting.Builder().name(name).description(description).defaultValue(value)
            .range(min,max).sliderRange(min,Math.min(max,Math.max(value*2,min))).build());
    }
    private void autoBounds(boolean minimumChanged) {
        if (autoMinimum == null || autoMaximum == null) return;
        if (autoMinimum.get() > autoMaximum.get()) {
            if (minimumChanged) autoMaximum.set(autoMinimum.get()); else autoMinimum.set(autoMaximum.get());
        }
    }
    private void syncAutoTpy() {
        if (autoTpy == null || autoBackoff == null) return;
        Set<String> friends = new HashSet<>();
        if (Friends.get() != null) for (Friend friend : Friends.get()) friends.add(friend.getName());
        refreshAutoTpyRoles();
        autoTpy.configure(new AutoTpyPolicy(Set.copyOf(autoWhitelist.get()),Set.copyOf(autoBlocklist.get()),autoWhitelistOnly.get(),
                autoAllowFriends.get(),autoToMe.get(),autoToThem.get(),autoRoleMembers,!autoRoleSelection.isEmpty()),friends,autoCapacity.get(),autoInFlight.get(),autoAttempts.get(),
                autoReplyTimeout.get(),autoBackoff.get(),autoInterval.get(),autoAdaptive.get(),autoMinimum.get(),autoMaximum.get());
        incomingRequests.capacity(autoCapacity.get());
        boolean starting = isActive() && autoTpyEnabledSetting.get() && !autoTpy.enabled();
        autoTpy.enabled(isActive() && autoTpyEnabledSetting.get());
        if (starting) for (var request : incomingRequests.pending(now())) offerAutoTpy(request);
    }
    private void offerAutoTpy(ChatTpaRequests.Request request) {
        autoTpy.offer(new AutoTpyQueue.Request(request.player(),request.toThem(),request.expires(),acceptCommand(request)),now());
    }
    public boolean autoTpyEnabled() { return autoTpyEnabledSetting.get(); }
    public void autoTpyEnabled(boolean enabled) { autoTpyEnabledSetting.set(enabled);syncAutoTpy(); }
    public boolean autoTpyAdaptive() { return autoAdaptive.get(); }
    public void toggleAutoTpyAdaptive() { autoAdaptive.set(!autoAdaptive.get());syncAutoTpy(); }
    public boolean autoTpyAllowsFriends() { return autoAllowFriends.get(); }
    public void toggleAutoTpyFriends() { autoAllowFriends.set(!autoAllowFriends.get());syncAutoTpy(); }
    public boolean autoTpyWhitelistOnly() { return autoWhitelistOnly.get(); }
    public void toggleAutoTpyWhitelistOnly() { autoWhitelistOnly.set(!autoWhitelistOnly.get());syncAutoTpy(); }
    public boolean autoTpyToMe() { return autoToMe.get(); }
    public void toggleAutoTpyToMe() { autoToMe.set(!autoToMe.get());syncAutoTpy(); }
    public boolean autoTpyToThem() { return autoToThem.get(); }
    public void toggleAutoTpyToThem() { autoToThem.set(!autoToThem.get());syncAutoTpy(); }
    /** Role access follows the live connection, so a changed guild or channel cannot keep old permissions. */
    private void refreshAutoTpyRoles() {
        Map<String, List<DiscordRoles.Role>> roster = discordRoleRoster();
        Set<String> selected = Set.copyOf(autoDiscordRoleIds.get());
        DiscordIgnSync sync = Modules.get().get(DiscordIgnSync.class);
        String guild = sync == null ? "" : sync.configuredGuildId();
        String channel = sync == null ? "" : sync.configuredChannelId();
        String source = DiscordRoles.source(guild, channel);
        boolean rosterChanged = roster != autoRoleRoster;
        if (rosterChanged) autoAvailableRoles = DiscordRoles.availableRoles(roster);
        if (rosterChanged || !selected.equals(autoRoleSelection) || !source.equals(autoRoleSource)) {
            autoRoleMembers = DiscordRoles.authorizedMembers(roster, selected, savedDiscordRoleSource.get(), guild, channel);
            autoRoleSelection = selected;
            autoRoleRoster = roster;
            autoRoleSource = source;
        }
    }
    public List<DiscordRoles.Role> autoTpyAvailableRoles() { refreshAutoTpyRoles();return autoAvailableRoles; }
    public List<String> autoTpyRoleIds() { return List.copyOf(autoDiscordRoleIds.get()); }
    public void toggleAutoTpyRole(String id) {
        if (id == null || !id.matches("[0-9]+")) return;
        List<String> ids = new ArrayList<>(autoDiscordRoleIds.get());
        if (ids.contains(id)) ids.removeIf(id::equals); else ids.add(id);
        autoDiscordRoleIds.set(ids);
        syncAutoTpy();
    }
    public void clearAutoTpyRoles() { autoDiscordRoleIds.set(List.of());syncAutoTpy(); }
    public int autoTpyRoleMemberCount() { refreshAutoTpyRoles();return autoRoleMembers.size(); }
    private Setting<Integer> autoTpyNumberSetting(String key) {
        return switch (key) {
            case "interval" -> autoInterval;
            case "min-interval" -> autoMinimum;
            case "max-interval" -> autoMaximum;
            case "buffer-limit" -> autoCapacity;
            case "in-flight" -> autoInFlight;
            case "max-attempts" -> autoAttempts;
            case "reply-timeout" -> autoReplyTimeout;
            case "retry-backoff" -> autoBackoff;
            default -> throw new IllegalArgumentException("Unknown Auto TPY setting: " + key);
        };
    }
    public int autoTpyNumber(String key) { return autoTpyNumberSetting(key).get(); }
    public void setAutoTpyNumber(String key, int value) {
        int min = switch (key) {
            case "buffer-limit" -> 64;
            case "in-flight", "max-attempts" -> 1;
            case "reply-timeout" -> 500;
            default -> 0;
        };
        int max = switch (key) {
            case "buffer-limit" -> 16384;
            case "in-flight" -> 32;
            case "max-attempts" -> 10;
            case "reply-timeout", "retry-backoff" -> 30000;
            default -> 60000;
        };
        autoTpyNumberSetting(key).set(Math.clamp(value, min, max));
        syncAutoTpy();
    }
    public List<AutoTpyQueue.Entry> autoTpyEntries() { return autoTpy.queue().entries(now()); }
    public AutoTpyQueue.Stats autoTpyStats() {
        var stats=autoTpy.queue().stats(now());
        return new AutoTpyQueue.Stats(stats.live(),stats.queued(),stats.awaiting(),stats.retries(),stats.overflow()+incomingRequests.rejected(),stats.latest());
    }
    public long autoTpyIntervalMs() { return autoTpy.pacer().intervalMs(); }
    public String autoTpySessionKey() { return homeServerKey(); }
    public void cancelAutoTpy(String player) { autoTpy.queue().cancel(player,now()); }
    public void clearAutoTpy() { autoTpy.queue().clear(now()); }
    public void resetAutoTpyLearning() { autoTpy.pacer().resetLearning(); }
    public List<String> autoTpyWhitelist() { return ChatClan.members(autoWhitelist.get()); }
    public List<String> autoTpyBlocklist() { return ChatClan.members(autoBlocklist.get()); }
    public void setAutoTpyWhitelist(List<String> names) { autoWhitelist.set(ChatClan.members(names));syncAutoTpy(); }
    public void setAutoTpyBlocklist(List<String> names) { autoBlocklist.set(ChatClan.members(names));syncAutoTpy(); }

    public boolean isFriend(String player) { return Friends.get() != null && Friends.get().get(player) != null; }

    public void toggleFriend(String player) {
        if (!ChatMessages.validName(player) || isSelf(player) || Friends.get() == null) return;
        Friend friend = Friends.get().get(player);
        if (friend != null) Friends.get().remove(friend);
        else {
            PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfoIgnoreCase(player);
            Friends.get().add(new Friend(player, info == null ? null : info.getProfile().id()));
        }
        refreshHighlights();
    }

    /** Clan members to list, in saved order, without yourself. */
    public List<String> clanMembers() {
        return ChatClan.members(clanMembers.get()).stream().filter(name -> !isSelf(name)).toList();
    }
    /** Every saved clan member, including yourself, in saved order, for whole-list rewrites. */
    public List<String> savedClanMembers() { return ChatClan.members(clanMembers.get()); }
    public DiscordRoles.Groups discordClanGroups() {
        return DiscordRoles.groups(clanMembers(), this::isFriend, this::online, discordRoleRoster());
    }
    public String discordRoleLabel(String player) { return DiscordRoles.label(discordRoleRoster(), player); }
    public String discordUsername(String player) {
        String data = savedDiscordUsernames.get();
        if (!data.equals(cachedDiscordUsernameData)) {
            cachedDiscordUsernames = DiscordIgn.decodeUsernames(data);
            cachedDiscordUsernameData = data;
        }
        return cachedDiscordUsernames.getOrDefault(player.toLowerCase(Locale.ROOT), "");
    }
    public void setDiscordUsernames(Map<String, String> usernames) {
        savedDiscordUsernames.set(DiscordIgn.encodeUsernames(usernames));
        cachedDiscordUsernameData = null;
    }
    public void setDiscordRoles(Map<String, List<DiscordRoles.Role>> roster, String source) {
        savedDiscordRoles.set(DiscordRoles.encode(roster));
        savedDiscordRoleSource.set(source == null ? "" : source);
        cachedDiscordRoleData = null;
        syncAutoTpy();
    }
    /** Re-evaluate Auto TPY admission after the Discord connection changes. */
    public void refreshAutoTpyPolicy() { syncAutoTpy(); }
    /** Saved roles describe one guild and channel; a different connection hides them until the next sync. */
    private Map<String, List<DiscordRoles.Role>> discordRoleRoster() {
        String data = savedDiscordRoles.get();
        if (!data.equals(cachedDiscordRoleData)) {
            cachedDiscordRoles = DiscordRoles.decode(data);
            cachedDiscordRoleData = data;
        }
        return savedDiscordRoleSource.get().equals(discordSource()) ? cachedDiscordRoles : Map.of();
    }
    /** Guild and channel the connection is configured for right now. */
    private String discordSource() {
        DiscordIgnSync sync = Modules.get().get(DiscordIgnSync.class);
        return sync == null ? "" : DiscordRoles.source(sync.configuredGuildId(), sync.configuredChannelId());
    }
    public ChatClan.Groups clanGroups() { return ChatClan.groups(clanMembers(), this::isFriend, this::online); }
    public boolean inClan(String player) { return ChatClan.contains(clanMembers.get(), player); }
    /** Adds the usernames not already in the clan and returns how many that was. */
    public int addToClan(List<String> names) {
        List<String> updated = ChatClan.add(clanMembers.get(), names);
        int added = updated.size() - clanMembers.get().size();
        if (added > 0) { clanMembers.set(updated); refreshHighlights(); }
        return added;
    }
    public void removeFromClan(String player) {
        if (!inClan(player)) return;
        clanMembers.set(ChatClan.remove(clanMembers.get(), player));
        refreshHighlights();
    }
    /** Replaces the whole clan list, for a sync that keeps names added by hand but removes ones it added itself. */
    public void setClanMembers(List<String> names) {
        clanMembers.set(ChatClan.members(names));
        refreshHighlights();
    }
    public void toggleClan(String player) {
        if (inClan(player)) removeFromClan(player);
        else if (ChatMessages.validName(player) && !isSelf(player)) addToClan(List.of(player));
    }

    public void rememberPlayer(String player) {
        checkSession();
        if (isSelf(player) || !syncRecentPlayers()) return;
        recentPlayers.touch(player);
        List<String> updated = ChatRecentPlayers.write(savedRecents.get(), recentKey, recentPlayers.names());
        if (!updated.equals(savedRecents.get())) savedRecents.set(updated);
    }
    public List<String> recentPlayers() { checkSession(); syncRecentPlayers(); return recentPlayers.names(); }

    /** Loads this server/account's saved recents into a fresh session; false until the account is known. */
    private boolean syncRecentPlayers() {
        if (mc.player == null) return false;
        String key = homeServerKey();
        if (!key.equals(recentKey)) {
            recentKey = key;
            recentPlayers.restore(ChatRecentPlayers.read(savedRecents.get(), key));
        }
        return true;
    }
    public List<String> onlinePlayers() {
        if (mc.getConnection() == null) return List.of();
        return mc.getConnection().getOnlinePlayers().stream().map(info -> info.getProfile().name())
            .filter(name -> ChatMessages.validName(name) && !isSelf(name))
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    public List<String> friends() {
        List<String> names = new ArrayList<>();
        if (Friends.get() != null) for (Friend friend : Friends.get()) {
            if (ChatMessages.validName(friend.getName()) && !isSelf(friend.getName())) names.add(friend.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }
    public String replyPlayer() { return replyPlayer; }
    public void requestTeleport(String player) {
        if (!ChatMessages.validName(player) || isSelf(player)) return;
        rememberPlayer(player);
        send("/tpa " + player);
    }
    public List<String> countdowns() { return ChatCountdowns.lines(timers(), requests(), now()); }

    private String homeServerKey() {
        String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip.toLowerCase(Locale.ROOT)
            : mc.getSingleplayerServer() != null ? "local:" + ((MinecraftServerAccessor) (Object) mc.getSingleplayerServer())
                .meteor$getSession().getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize() : "local";
        return server + "/" + (mc.player == null ? "" : mc.player.getGameProfile().id());
    }
    public List<String> favoriteHomes() { return ChatHomeFavorites.names(savedFavorites.get(), homeServerKey()); }
    public boolean favoriteHome(String home) { return favoriteHomes().stream().anyMatch(name -> name.equalsIgnoreCase(home)); }
    public void toggleFavoriteHome(String home) { savedFavorites.set(ChatHomeFavorites.toggle(savedFavorites.get(), homeServerKey(), home)); }
    public List<String> hotspotDetails() { return List.copyOf(hotspotDetails); }
    public void refreshHotspot() {
        if (now() < hotspotReadingUntil) return;
        hotspotDetails.clear();
        hotspotReadingUntil = now() + 3_000;
        send("/hotspot info");
    }

    public boolean onlineContact(String player) { return online(player); }
    public int friendColor() { return 0xff000000 | friendChatColor.get().getPacked(); }
    public int friendTabColor() { return 0xff000000 | friendTabColor.get().getPacked(); }
    public boolean friendHighlighting() { return highlightFriendNames.get(); }
    public void toggleFriendHighlighting() { highlightFriendNames.set(!highlightFriendNames.get()); refreshHighlights(); }
    public boolean friendTabHighlighting() { return highlightFriendsTab.get(); }
    public void toggleFriendTabHighlighting() { highlightFriendsTab.set(!highlightFriendsTab.get()); }
    public int friendRowColor() { return 0xff000000 | friendRowColor.get().getPacked(); }
    public boolean friendRowHighlighting() { return highlightFriendRows.get(); }
    public void toggleFriendRowHighlighting() { highlightFriendRows.set(!highlightFriendRows.get()); }
    public int clanColor() { return 0xff000000 | clanChatColor.get().getPacked(); }
    public int clanTabColor() { return 0xff000000 | clanTabColor.get().getPacked(); }
    public boolean clanChatHighlighting() { return highlightClanChat.get(); }
    public void toggleClanChatHighlighting() { highlightClanChat.set(!highlightClanChat.get()); refreshHighlights(); }
    public boolean clanTabHighlighting() { return highlightClanTab.get(); }
    public void toggleClanTabHighlighting() { highlightClanTab.set(!highlightClanTab.get()); }
    public int clanRowColor() { return 0xff000000 | clanRowColor.get().getPacked(); }
    public boolean clanRowHighlighting() { return highlightClanRows.get(); }
    public void toggleClanRowHighlighting() { highlightClanRows.set(!highlightClanRows.get()); }
    public void pickFriendColor(boolean tab) { pickColor(tab ? friendTabColor : friendChatColor); }
    public void pickClanColor(boolean tab) { pickColor(tab ? clanTabColor : clanChatColor); }
    public void pickFriendRowColor() { pickColor(friendRowColor); }
    public void pickClanRowColor() { pickColor(clanRowColor); }
    /** Opens Meteor's colour picker, which returns to the current screen when closed. */
    private void pickColor(Setting<SettingColor> setting) { mc.setScreen(new ColorSettingScreen(GuiThemes.get(), setting)); }
    public boolean messageNotifications() { return messageToasts.get(); }
    public void toggleMessageNotifications() { messageToasts.set(!messageToasts.get()); }
    public boolean hidingMessages() { return hideMessages.get(); }
    public void toggleHidingMessages() { hideMessages.set(!hideMessages.get()); }

    /** The length used after a confirmed teleport on this server/account, and where each candidate comes from. */
    public record CooldownLength(Long manual, Long learned, long fallback) {
        public long effective() { return manual != null ? manual : learned != null ? learned : fallback; }
    }
    public CooldownLength cooldownLength(ChatTeleportState.Kind kind) {
        checkSession();
        syncSavedCooldowns();
        return new CooldownLength(teleports.manual().get(kind), teleports.learned().get(kind),
            (kind == ChatTeleportState.Kind.Home ? homeCooldown : tpaCooldown).get() * 1_000L);
    }
    /** Pins a cooldown length for this server and account, or with null goes back to the learned one. */
    public void manualCooldown(ChatTeleportState.Kind kind, Long millis) {
        checkSession();
        syncSavedCooldowns();
        teleports.manual(kind, millis, now());
        syncSavedCooldowns();
    }

    /**
     * Outgoing DMs enter history only when the server echoes them, so rejected sends never look delivered. Until then
     * they wait in the whisper queue, which holds them back while the server's whisper delay runs.
     */
    public boolean sendPrivate(String player, String text) {
        if (!ChatMessages.validName(player) || text.isBlank() || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) return false;
        String template = messageCommand.get().strip();
        if (!template.contains("{player}") || template.indexOf('\n') >= 0 || template.indexOf('\r') >= 0) return false;
        String command = template.replace("{player}", player) + " " + text;
        if (withoutSlash(command).length() > 256) return false;
        return queueWhisper(new ChatWhisperQueue.Whisper(player, text, command));
    }

    /** Runs a command typed in the Chat Tools composer; a whisper such as /msg or /r waits its turn like a DM. */
    public boolean sendCommand(String command) {
        var whisper = ChatWhisperQueue.parse(command, messageCommand.get());
        if (whisper != null) return queueWhisper(whisper);
        send(command);
        return true;
    }
    public boolean isWhisper(String command) { return ChatWhisperQueue.parse(command, messageCommand.get()) != null; }

    private boolean queueWhisper(ChatWhisperQueue.Whisper whisper) {
        checkSession();
        applyCooldowns();
        if (mc.getConnection() == null || !whispers.add(whisper)) return false;
        if (whisper.player() != null) rememberPlayer(whisper.player());
        sendWhispers();
        return true;
    }

    /** Sends the next queued whisper once the server's delay allows it; never mid-transfer, when there is no world. */
    private void sendWhispers() {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        var next = whispers.poll(now());
        if (next != null) send(next.command());
    }

    /** Sends public chat through the queue, or straight away when queueing is off. */
    public boolean sendPublic(String text) {
        checkSession();
        if (text == null || text.isBlank() || mc.getConnection() == null) return false;
        if (!queueChat.get()) { sendChatNow(text); return true; }
        applyCooldowns();
        if (!chats.add(text)) return false;
        sendChats();
        return true;
    }

    /** Settings can change at any time; queueing and reading the queue always use the current cooldowns. */
    private void applyCooldowns() {
        whispers.minimum(whisperCooldown.get());
        chats.minimum(chatCooldown.get());
    }

    private void sendChats() {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        String next = chats.poll(now());
        if (next != null) sendChatNow(next);
    }

    private void sendChatNow(String text) {
        mc.gui.getChat().addRecentChat(text);
        mc.getConnection().sendChat(text);
    }

    /** One message waiting in either queue, for the window's send tray. */
    public record Outgoing(boolean whisper, long id, String target, String text, long eta) {}
    /** Everything still waiting to send, soonest first. */
    public List<Outgoing> outgoing() {
        checkSession();
        applyCooldowns();
        long now = now();
        List<Outgoing> result = new ArrayList<>();
        for (var entry : chats.entries(now)) result.add(new Outgoing(false, entry.id(), null, entry.text(), entry.eta()));
        for (var entry : whispers.entries(now)) result.add(new Outgoing(true, entry.id(),
            entry.whisper().player() == null ? "Reply" : entry.whisper().player(), entry.whisper().text(), entry.eta()));
        result.sort(java.util.Comparator.comparingLong(Outgoing::eta));
        return result;
    }
    public void cancelOutgoing(Outgoing outgoing) {
        if (outgoing.whisper()) whispers.cancel(outgoing.id()); else chats.cancel(outgoing.id());
    }
    public int chatCooldownMs() { return chatCooldown.get(); }
    /** Screen pixels per window unit chosen by the player, or zero to size the window from the screen. */
    public double windowScale() { return windowScale.get(); }
    public int whisperCooldownMs() { return whisperCooldown.get(); }

    /** What is waiting for the player: teleport requests to answer, unread messages and a Discord sync problem. */
    public record Waiting(int requests, int unread, String problem) {
        public int total() { return requests + unread + (problem == null ? 0 : 1); }
    }
    /** Refreshed every tick, so the in-game notification never recomputes it while drawing. */
    private Waiting waitingNow = new Waiting(0, 0, null);
    public Waiting waiting() { return waitingNow; }
    private Waiting computeWaiting() {
        DiscordIgnSync sync = Modules.get().get(DiscordIgnSync.class);
        String problem = sync != null && sync.isActive() && sync.syncFailed() ? sync.syncStatus() : null;
        return new Waiting(mc.getConnection() == null ? 0 : requests().size(), messages().unread(), problem);
    }

    @EventHandler private void onRender2D(Render2DEvent event) {
        if (!waitingNotice.get() || mc.screen != null || mc.options.hideGui || mc.player == null) return;
        var waiting = waitingNow;
        if (waiting.total() > 0) dev.tntcoating.gui.WaitingPill.draw(event.drawContext, event.screenWidth, waiting, openPopupKey.get());
    }

    /** How long queued whispers still wait for the server's delay, or 0 when none are waiting. */
    public long whisperWait() { checkSession(); return whispers.waiting(now()); }
    /** Texts of whispers to this player that are queued or not yet echoed by the server, oldest first. */
    public List<String> pendingWhispers(String player) {
        checkSession();
        return whispers.pending(player, now()).stream().map(ChatWhisperQueue.Whisper::text).toList();
    }

    @EventHandler private void onMessage(ReceiveMessageEvent event) {
        if (mc.player == null || mc.getConnection() == null) return;
        checkSession();
        String text = event.getMessage().getString();
        // The refused whisper goes again once the wait is over, so the server's refusal would only be noise.
        if (whispers.refused(text, now()) || chats.refused(text, now())) { event.cancel(); return; }
        ChatMessages.Message message = messages.parse(text, mc.player.getGameProfile().name(), now());
        if (message == null) return;
        if (!message.incoming()) whispers.delivered(message.player());
        rememberPlayer(message.player());
        if (message.incoming()) replyPlayer = message.player();
        boolean read = mc.screen == popup && popup != null && popup.reading(message.player());
        messages.add(message, read);
        if (message.incoming() && !read && messageToasts.get())
            SystemToast.addOrUpdate(mc.getToastManager(), DM_TOAST,
                Component.literal("Message from " + message.player()), Component.literal("Open Chat Tools to reply."));
        if (hideMessages.get()) event.cancel();
    }

    public String playerFor(Style style) {
        return playerFrom(style);
    }

    public boolean runsImmediately(Option option) {
        return runOnClick.get() && option.complete() && !Character.isWhitespace(option.command().charAt(option.command().length() - 1));
    }

    public void send(String command) {
        if (mc.getConnection() == null) return;
        mc.gui.getChat().addRecentChat(command);
        mc.getConnection().sendCommand(withoutSlash(command));
    }

    public List<String> homes() {
        checkSession();
        return ChatHomeNames.merge(suggestedHomes, discoveredHomes, configuredHomes.get(), favoriteHomes());
    }

    public boolean loadingHomes() { return homeRequest != 0 && now() - homeRequestedAt < 5_000; }

    public void refreshHomes() {
        checkSession();
        var listener = mc.getConnection();
        if (listener == null || loadingHomes()) return;
        homeRequest = nextHomeRequest--;
        homeRequestedAt = now();
        // Use a private transaction ID so opening Homes never replaces chat's tab-completion future.
        listener.send(new ServerboundCommandSuggestionPacket(homeRequest, "/home "));
    }

    public List<ChatTeleportState.Timer> timers() {
        checkSession();
        return teleports.timers(now());
    }

    public List<ChatTpaRequests.Request> requests() {
        checkSession();
        return incomingRequests.pending(now());
    }

    public void answer(ChatTpaRequests.Request request, boolean yes) {
        checkSession();
        if (incomingRequests.isPending(request, now())) send(yes ? acceptCommand(request) : denyCommand(request));
    }

    /** The server's own accept command from its prompt, otherwise the first one its command tree offers. */
    public String acceptCommand(ChatTpaRequests.Request request) {
        return request.verbsKnown() ? request.yesCommand() : "/" + serverVerb("tpy", "tpaccept", "tpyes") + " " + request.player();
    }

    public String denyCommand(ChatTpaRequests.Request request) {
        return request.verbsKnown() ? request.noCommand() : "/" + serverVerb("tpn", "tpdeny", "tpno") + " " + request.player();
    }

    private String serverVerb(String... candidates) {
        for (String candidate : candidates) if (hasCommand(candidate)) return candidate;
        return candidates[0];
    }

    private boolean hasCommand(String root) {
        return mc.getConnection() != null && mc.getConnection().getCommands().getRoot().getChild(root) != null;
    }

    /**
     * Whether this server offers a top-level command. Before the server sends its command tree (or if it sends an
     * empty one) nothing is known, so every server-specific control stays available rather than vanishing.
     */
    public boolean commandAvailable(String root) {
        if (mc.getConnection() == null) return true;
        var tree = mc.getConnection().getCommands().getRoot();
        return tree.getChildren().isEmpty() || tree.getChild(root) != null;
    }

    public static long now() { return System.nanoTime() / 1_000_000; }

    /** Meteor friends take the friend colour, other clan members the clan colour. */
    public Component highlightNames(Component message) {
        boolean clan = clanInChat && !clanNames.isEmpty(), friends = friendsInChat && !friendNames.isEmpty();
        if (!clan && !friends) return message;
        return ChatFriendHighlight.apply(message, name -> {
            String key = name.toLowerCase(Locale.ROOT);
            return friends && friendNames.contains(key) ? Integer.valueOf(friendColor) : clan && clanNames.contains(key) ? Integer.valueOf(clanColor) : null;
        });
    }

    /** The colour from whichever switched-on setting applies to this player, or null; as in chat, friends come first. */
    private Integer tabColor(PlayerInfo info, Setting<Boolean> friendOn, Setting<SettingColor> friend, Setting<Boolean> clanOn,
                             Setting<SettingColor> clan) {
        String player = info.getProfile().name();
        if (player == null) return null;
        String key = player.toLowerCase(Locale.ROOT);
        Setting<SettingColor> setting = friendOn.get() && friendNames.contains(key) ? friend : clanOn.get() && clanNames.contains(key) ? clan : null;
        return setting == null ? null : setting.get().getPacked();
    }

    /**
     * Colours a friend's or clan member's username inside the tab list name the server or Meteor's Better Tab produced,
     * keeping prefixes and suffixes; a name that never spells out the username, such as a nickname, is coloured whole.
     */
    public Component highlightTabName(PlayerInfo info, Component name) {
        Integer tint = tabColor(info, highlightFriendsTab, friendTabColor, highlightClanTab, clanTabColor);
        if (tint == null) return name;
        int color = tint;
        String player = info.getProfile().name();
        Component result = ChatFriendHighlight.apply(name, found -> found.equalsIgnoreCase(player) ? Integer.valueOf(color) : null);
        return result != name ? result : ChatFriendHighlight.recolor(name, color);
    }

    /** The bar to draw behind a tab list row, with its colour's own alpha, or 0 for none. */
    public int tabHighlight(PlayerInfo info) {
        Integer color = tabColor(info, highlightFriendRows, friendRowColor, highlightClanRows, clanRowColor);
        return color == null ? 0 : color;
    }

    public boolean canCancelHome() { checkSession(); return teleports.canCancel(ChatTeleportState.Kind.Home, now()); }
    public boolean canCancelTpa() { checkSession(); return teleports.canCancel(ChatTeleportState.Kind.TPA, now()); }
    public void cancelHome() { if (canCancelHome()) send("/homecancel"); }
    public void cancelTpa() { if (canCancelTpa()) send(cancelTpaCommand()); }

    public String cancelTpaCommand() {
        if (mc.getConnection() != null) {
            var root = mc.getConnection().getCommands().getRoot();
            if (root.getChild("tpacancel") == null && root.getChild("tpcancel") != null) return "/tpcancel";
        }
        return "/tpacancel";
    }

    @Override public void onActivate() { resetSession(); refreshMessagePatterns(); refreshCooldowns(); friendRefreshTicks = 0; refreshHighlights(); }
    @Override public void onDeactivate() {
        if (popup != null && mc.screen == popup) popup.onClose();
        resetSession();
        friendNames = Set.of();
        clanNames = Set.of();
        if (mc.gui != null) mc.gui.getChat().rescaleChat();
    }
    @EventHandler private void onTick(TickEvent.Pre event) {
        checkSession();
        applyCooldowns();
        waitingNow = computeWaiting();
        sendWhispers();
        sendChats();
        if (mc.player != null && mc.getConnection() != null) {
            String account = autoTpySessionKey();
            if (autoAccount != null && !autoAccount.equals(account)) { autoTpy.reset();incomingRequests.clear(); }
            autoAccount=account;
            syncAutoTpy();
            autoTick=mc.player.tickCount;
            autoTpy.tick(now(),autoTick, command -> mc.getConnection().sendCommand(withoutSlash(command)));
        }
        // Friend, clan and colour edits are rare; a one-second refresh keeps highlighting current without per-tick work.
        if (++friendRefreshTicks >= FRIEND_REFRESH_TICKS) {
            friendRefreshTicks = 0;
            refreshHighlights();
        }
    }

    /**
     * Meteor also reports leaving when a network moves the player to another of its servers, which is how 6b6t
     * completes many teleports. Conversations and timers survive that; a new connection still starts a new session.
     */
    @EventHandler private void onGameLeft(GameLeftEvent event) {
        autoTpy.reset();
        incomingRequests.clear();
        teleports.transferred(now());
        syncSavedCooldowns();
    }

    private void refreshHighlights() {
        Set<String> friends = new HashSet<>();
        if (Friends.get() != null) for (var friend : Friends.get()) friends.add(friend.getName().toLowerCase(Locale.ROOT));
        Set<String> clan = ChatClan.keys(clanMembers.get());
        int friendRgb = friendChatColor.get().getPacked() & 0xffffff, clanRgb = clanChatColor.get().getPacked() & 0xffffff;
        boolean friendsShown = highlightFriendNames.get(), clanShown = highlightClanChat.get();
        // The tab list reads these names every frame; only chat lines are wrapped once and need rebuilding.
        boolean chatChanged = friendsShown != friendsInChat || clanShown != clanInChat
            || friendsShown && (!friends.equals(friendNames) || friendRgb != friendColor)
            || clanShown && (!clan.equals(clanNames) || clanRgb != clanColor);
        friendNames = Set.copyOf(friends);
        friendColor = friendRgb;
        friendsInChat = friendsShown;
        clanNames = clan;
        clanColor = clanRgb;
        clanInChat = clanShown;
        if (chatChanged && mc.gui != null) mc.gui.getChat().rescaleChat();
    }

    private void resetSession() {
        if (popup != null && mc.screen == popup) mc.setScreen(null);
        messages.clear();
        recentPlayers.clear();
        recentKey = null;
        whispers.clear();
        chats.clear();
        replyPlayer = null;
        hotspotDetails.clear();
        hotspotReadingUntil = 0;
        popup = null;
        session = null;
        homeRequest = 0;
        discoveredHomes.clear();
        suggestedHomes.clear();
        teleports.clear();
        savedKey = null;
        incomingRequests.clear();
        autoTpy.reset();
        autoAccount=null;
        pendingHomeEdit = null;
    }

    private void checkSession() {
        var listener = mc.getConnection();
        if (listener == null || listener.getConnection() == session) return;
        resetSession();
        session = listener.getConnection();
    }

    @EventHandler private void onSent(PacketEvent.Sent event) {
        if (event.packet instanceof ServerboundChatPacket chat) {
            String text = chat.message();
            mc.execute(() -> { if (isActive()) { checkSession(); chats.sent(text, now()); } });
            return;
        }
        String command = event.packet instanceof ServerboundChatCommandPacket packet ? packet.command()
            : event.packet instanceof ServerboundChatCommandSignedPacket packet ? packet.command() : null;
        if (command == null) return;
        mc.execute(() -> {
            if (!isActive() || mc.getConnection() == null || event.connection != mc.getConnection().getConnection()) return;
            checkSession();
            String[] parts = WHITESPACE.split(command.strip());
            String verb = parts[0].substring(parts[0].lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
            if (parts.length > 1 && Set.of("msg", "w", "whisper", "tell", "tpa", "tpahere").contains(verb)) rememberPlayer(parts[1]);
            if (ChatWhisperQueue.isWhisper(command, messageCommand.get())) whispers.sent(command, now());
            syncSavedCooldowns();
            autoTpy.commandSent(command,now(),autoTick);
            teleports.sent(command, now());
            incomingRequests.sent(command, now());
            ChatHomeEdit edit = ChatHomeEdit.parse(command);
            if (edit != null) {
                pendingHomeEdit = edit;
                homeEditExpires = now() + 10_000;
            }
        });
    }

    @EventHandler private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundCommandSuggestionsPacket packet && packet.id() == homeRequest && homeRequest != 0) {
            event.cancel();
            mc.execute(() -> {
                if (!isActive() || mc.getConnection() == null || event.connection != mc.getConnection().getConnection()
                    || packet.id() != homeRequest) return;
                suggestedHomes.clear();
                suggestedHomes.addAll(ChatHomeNames.merge(packet.suggestions().stream().map(ClientboundCommandSuggestionsPacket.Entry::text).toList()));
                homeRequest = 0;
            });
        } else if (event.packet instanceof ClientboundSystemChatPacket packet) {
            mc.execute(() -> {
                if (!isActive() || mc.getConnection() == null || event.connection != mc.getConnection().getConnection()) return;
                checkSession();
                String text = packet.content().getString();
                // Whispers, including echoes to offline recipients, must never change server command state. Nor may the
                // whisper delay: "Please wait 2 seconds before..." would otherwise read as a home or TP cooldown.
                String self = mc.player == null ? null : mc.player.getGameProfile().name();
                if (messages.parse(text, self, now()) != null || ChatWhisperQueue.isRefusal(text)) return;
                // Another player's chat must never move timers, requests or homes, even if it imitates a server reply.
                if (ChatSender.fromPlayer(text, this::online)) return;
                if (now() < hotspotReadingUntil || text.toLowerCase(Locale.ROOT).contains("hotspot")) {
                    hotspotDetails.add(ChatSender.plain(text));
                    if (hotspotDetails.size() > 12) hotspotDetails.removeFirst();
                }
                syncSavedCooldowns();
                teleports.message(text, now());
                syncSavedCooldowns();
                collectRequests(packet.content());
                confirmHomeEdit(text);
                collectHomes(packet.content());
                for (String name : ChatHomeNames.fromMessage(text)) addHome(name);
            });
        } else if (event.packet instanceof ClientboundSetActionBarTextPacket packet) {
            mc.execute(() -> {
                if (!isActive() || mc.getConnection() == null || event.connection != mc.getConnection().getConnection()) return;
                checkSession();
                syncSavedCooldowns();
                teleports.message(packet.text().getString(), now());
                syncSavedCooldowns();
            });
        }
    }

    private void collectRequests(Component message) {
        List<String> commands = new ArrayList<>();
        message.visit((Style style, String text) -> {
            ClickEvent click = style.getClickEvent();
            if (click instanceof ClickEvent.RunCommand run) commands.add(run.command());
            else if (click instanceof ClickEvent.SuggestCommand suggest) commands.add(suggest.command());
            return Optional.empty();
        }, Style.EMPTY);
        String self = mc.player == null ? null : mc.player.getGameProfile().name();
        syncAutoTpy();
        autoTpy.message(message.getString(),now());
        incomingRequests.message(message.getString(), commands, self, now(), requestTimeout.get());
        for (var change : incomingRequests.drainChanges()) {
            autoTpy.lifecycle(change,now());
            if (change.kind() == ChatTpaRequests.ChangeKind.Upsert) {
                offerAutoTpy(change.request());
                rememberPlayer(change.request().player());
            }
        }
    }

    /**
     * Loads this server/account's learned and manual cooldowns into a fresh session and saves changes, so the first
     * teleport of a session already uses the real cooldown instead of the fallback estimate.
     */
    private void syncSavedCooldowns() {
        if (mc.player == null) return;
        String key = homeServerKey();
        if (!key.equals(savedKey)) {
            savedKey = key;
            saved = ChatSavedCooldowns.read(savedCooldowns.get(), key);
            teleports.learned(saved.learned());
            teleports.manual(saved.manual());
        }
        var current = new ChatSavedCooldowns.Saved(teleports.learned(), teleports.manual());
        if (current.equals(saved)) return;
        saved = current;
        savedCooldowns.set(ChatSavedCooldowns.write(savedCooldowns.get(), key, current));
    }

    private void confirmHomeEdit(String message) {
        if (pendingHomeEdit == null || now() > homeEditExpires || !pendingHomeEdit.confirmedBy(message)) return;
        ChatHomeEdit edit = pendingHomeEdit;
        pendingHomeEdit = null;
        savedFavorites.set(ChatHomeFavorites.edit(savedFavorites.get(), homeServerKey(), edit));
        List<String> updatedDiscovered = edit.apply(discoveredHomes);
        List<String> updatedSuggested = edit.apply(suggestedHomes);
        discoveredHomes.clear();
        discoveredHomes.addAll(updatedDiscovered);
        suggestedHomes.clear();
        suggestedHomes.addAll(updatedSuggested);
        // New server homes belong to this connection, not the global fallback names shared by all servers.
        if (edit.oldName() != null) {
            List<String> configured = edit.apply(configuredHomes.get());
            if (!configured.equals(configuredHomes.get())) configuredHomes.set(configured);
        }
        if (edit.newName() != null) addHome(edit.newName());
        // A reply to an earlier suggestion request must not resurrect the old name.
        homeRequest = 0;
        refreshHomes();
    }

    private void collectHomes(Component component) {
        ClickEvent click = component.getStyle().getClickEvent();
        String command = click instanceof ClickEvent.RunCommand run ? run.command()
            : click instanceof ClickEvent.SuggestCommand suggest ? suggest.command() : null;
        if (command != null && HOME_CLICK.matcher(command).matches()) {
            addHome(WHITESPACE.split(command.strip())[1]);
        }
        for (Component sibling : component.getSiblings()) collectHomes(sibling);
    }

    private void addHome(String name) {
        String valid = ChatHomeNames.valid(name);
        if (valid != null && discoveredHomes.stream().noneMatch(existing -> existing.equalsIgnoreCase(valid))) discoveredHomes.add(valid);
    }

    public List<Option> options(String player) {
        Map<String, Option> result = new LinkedHashMap<>();
        if (autoDetect.get() && mc.getConnection() != null) {
            for (CommandNode<ClientSuggestionProvider> root : mc.getConnection().getCommands().getRoot().getChildren()) {
                if (root instanceof LiteralCommandNode<ClientSuggestionProvider> literal) discover(literal, List.of(literal.getLiteral()), player, 0, result);
            }
        }
        for (String entry : configured.get()) {
            ChatCommandTemplate template = ChatCommandTemplate.parse(entry);
            if (!template.valid()) continue;
            String command = template.expand(player);
            boolean complete = !Character.isWhitespace(command.charAt(command.length() - 1));
            put(result, new Option(template.label(), command, complete, false));
        }
        return new ArrayList<>(result.values());
    }

    private void discover(CommandNode<ClientSuggestionProvider> node, List<String> path, String player, int depth, Map<String, Option> result) {
        if (depth > 2) return;
        for (CommandNode<ClientSuggestionProvider> child : node.getChildren()) {
            if (child instanceof ArgumentCommandNode<ClientSuggestionProvider, ?> argument && acceptsPlayers(argument.getType())) {
                String command = "/" + String.join(" ", path) + " " + player;
                boolean complete = argument.getCommand() != null;
                put(result, new Option("/" + String.join(" ", path), command + (complete ? "" : " "), complete, true));
            } else if (child instanceof LiteralCommandNode<ClientSuggestionProvider> literal) {
                List<String> next = new ArrayList<>(path);
                next.add(literal.getLiteral());
                discover(literal, next, player, depth + 1, result);
            }
        }
    }

    private static boolean acceptsPlayers(ArgumentType<?> type) {
        if (type instanceof GameProfileArgument) return true;
        return type instanceof EntityArgument && ((EntityArgumentAccessor) type).comet$playersOnly();
    }

    private static void put(Map<String, Option> options, Option option) {
        options.putIfAbsent(option.command().stripTrailing().toLowerCase(Locale.ROOT), option);
    }

    private String playerFrom(Style style) {
        if (mc.getConnection() == null || style == null) return null;
        String insertion = onlineName(style.getInsertion());
        if (insertion != null) return insertion;
        if (style.getHoverEvent() instanceof HoverEvent.ShowEntity show && show.entity().type == EntityType.PLAYER) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(show.entity().uuid);
            if (info != null && !isSelf(info.getProfile().name())) return info.getProfile().name();
            if (show.entity().name.isPresent()) {
                String hovered = onlineName(show.entity().name.get().getString());
                if (hovered != null) return hovered;
            }
        }
        String clicked = null;
        if (style.getClickEvent() instanceof ClickEvent.SuggestCommand suggest) clicked = suggest.command();
        else if (style.getClickEvent() instanceof ClickEvent.RunCommand run) clicked = run.command();
        if (clicked != null) {
            String[] tokens = WHITESPACE.split(clicked.strip());
            for (int i = 1; i < tokens.length; i++) {
                String name = onlineName(tokens[i]);
                if (name != null) return name;
            }
        }
        return null;
    }

    private boolean online(String name) {
        return mc.getConnection() != null && mc.getConnection().getPlayerInfoIgnoreCase(name) != null;
    }

    private String onlineName(String candidate) {
        if (candidate == null || !USERNAME.matcher(candidate).matches()) return null;
        PlayerInfo info = mc.getConnection().getPlayerInfoIgnoreCase(candidate);
        return info == null || isSelf(info.getProfile().name()) ? null : info.getProfile().name();
    }

    private boolean isSelf(String name) {
        return mc.player != null && mc.player.getGameProfile().name().equalsIgnoreCase(name);
    }

    private static String withoutSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    public record Option(String label, String command, boolean complete, boolean detected) {}
}
