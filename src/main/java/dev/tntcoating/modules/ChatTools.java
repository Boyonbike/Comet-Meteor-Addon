package dev.tntcoating.modules;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.tntcoating.TntCoatingAddon;
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
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.mixin.MinecraftServerAccessor;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.config.Config;
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
        .description("Highlight whole Meteor friend names in chat using Meteor's friend colour.").defaultValue(true).build());
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
    private final Setting<Integer> requestTimeout = general.add(new IntSetting.Builder().name("tpa-request-timeout")
        .description("Seconds to retain incoming requests when the server does not report their expiry.")
        .defaultValue(120).range(5, 3600).sliderRange(30, 300).build());
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

    private final ChatMessages messages = new ChatMessages();
    private final ChatRecentPlayers recentPlayers = new ChatRecentPlayers();
    private String replyPlayer;
    private final List<String> hotspotDetails = new ArrayList<>();
    private long hotspotReadingUntil;
    private ChatToolsScreen popup;
    private final ChatTeleportState teleports = new ChatTeleportState();
    private final ChatTpaRequests incomingRequests = new ChatTpaRequests();
    private final List<String> discoveredHomes = new ArrayList<>();
    private final List<String> suggestedHomes = new ArrayList<>();
    private Connection session;
    private volatile int homeRequest;
    private int nextHomeRequest = -1_400_000_000;
    private long homeRequestedAt;
    private ChatHomeEdit pendingHomeEdit;
    private long homeEditExpires;
    private Set<String> friendNames = Set.of();
    private int friendColor;
    private int friendRefreshTicks;
    private String savedKey;
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

    public boolean isFriend(String player) { return Friends.get() != null && Friends.get().get(player) != null; }

    public void toggleFriend(String player) {
        if (!ChatMessages.validName(player) || isSelf(player) || Friends.get() == null) return;
        Friend friend = Friends.get().get(player);
        if (friend != null) Friends.get().remove(friend);
        else {
            PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfoIgnoreCase(player);
            Friends.get().add(new Friend(player, info == null ? null : info.getProfile().id()));
        }
        refreshFriendHighlights();
    }

    public void rememberPlayer(String player) { if (!isSelf(player)) recentPlayers.touch(player); }
    public List<String> recentPlayers() { checkSession(); return recentPlayers.names(); }
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
                .meteor$getStorageSource().getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize() : "local";
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
    public int friendColor() { return 0xff000000 | friendColor; }
    public boolean friendHighlighting() { return highlightFriendNames.get(); }
    public void toggleFriendHighlighting() { highlightFriendNames.set(!highlightFriendNames.get()); refreshFriendHighlights(); }
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

    /** Outgoing DMs enter history only when the server echoes them, so rejected sends never look delivered. */
    public boolean sendPrivate(String player, String text) {
        if (!ChatMessages.validName(player) || text.isBlank() || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) return false;
        String template = messageCommand.get().strip();
        if (!template.contains("{player}") || template.indexOf('\n') >= 0 || template.indexOf('\r') >= 0) return false;
        String command = template.replace("{player}", player) + " " + text;
        if (withoutSlash(command).length() > 256 || mc.getConnection() == null) return false;
        send(command);
        return true;
    }

    @EventHandler private void onMessage(ReceiveMessageEvent event) {
        if (mc.player == null || mc.getConnection() == null) return;
        checkSession();
        ChatMessages.Message message = messages.parse(event.getMessage().getString(), mc.player.getGameProfile().name(), now());
        if (message == null) return;
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

    public Component highlightFriends(Component message) {
        if (!highlightFriendNames.get() || friendNames.isEmpty()) return message;
        return ChatFriendHighlight.apply(message, name -> friendNames.contains(name.toLowerCase(Locale.ROOT)), friendColor);
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

    @Override public void onActivate() { resetSession(); refreshMessagePatterns(); refreshCooldowns(); friendRefreshTicks = 0; refreshFriendHighlights(); }
    @Override public void onDeactivate() {
        if (popup != null && mc.screen == popup) popup.onClose();
        resetSession();
        friendNames = Set.of();
        if (mc.gui != null) mc.gui.getChat().rescaleChat();
    }
    @EventHandler private void onTick(TickEvent.Pre event) {
        checkSession();
        // Friend list and colour edits are rare; a one-second refresh keeps highlighting current without per-tick work.
        if (++friendRefreshTicks >= FRIEND_REFRESH_TICKS) {
            friendRefreshTicks = 0;
            refreshFriendHighlights();
        }
    }

    /**
     * Meteor also reports leaving when a network moves the player to another of its servers, which is how 6b6t
     * completes many teleports. Conversations and timers survive that; a new connection still starts a new session.
     */
    @EventHandler private void onGameLeft(GameLeftEvent event) {
        teleports.transferred(now());
        syncSavedCooldowns();
    }

    private void refreshFriendHighlights() {
        Set<String> names = new HashSet<>();
        if (highlightFriendNames.get() && Friends.get() != null) {
            for (var friend : Friends.get()) names.add(friend.getName().toLowerCase(Locale.ROOT));
        }
        int color = Config.get() == null ? 0x00ffb4 : Config.get().friendColor.get().getPacked() & 0xffffff;
        if (names.equals(friendNames) && color == friendColor) return;
        friendNames = Set.copyOf(names);
        friendColor = color;
        if (mc.gui != null) mc.gui.getChat().rescaleChat();
    }

    private void resetSession() {
        if (popup != null && mc.screen == popup) mc.setScreen(null);
        messages.clear();
        recentPlayers.clear();
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
        pendingHomeEdit = null;
    }

    private void checkSession() {
        var listener = mc.getConnection();
        if (listener == null || listener.getConnection() == session) return;
        resetSession();
        session = listener.getConnection();
    }

    @EventHandler private void onSent(PacketEvent.Sent event) {
        String command = event.packet instanceof ServerboundChatCommandPacket packet ? packet.command()
            : event.packet instanceof ServerboundChatCommandSignedPacket packet ? packet.command() : null;
        if (command == null) return;
        mc.execute(() -> {
            if (!isActive() || mc.getConnection() == null || event.connection != mc.getConnection().getConnection()) return;
            checkSession();
            String[] parts = WHITESPACE.split(command.strip());
            String verb = parts[0].substring(parts[0].lastIndexOf(':') + 1).toLowerCase(Locale.ROOT);
            if (parts.length > 1 && Set.of("msg", "w", "whisper", "tell", "tpa", "tpahere").contains(verb)) rememberPlayer(parts[1]);
            syncSavedCooldowns();
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
                // Whispers, including echoes to offline recipients, must never change server command state.
                String self = mc.player == null ? null : mc.player.getGameProfile().name();
                if (messages.parse(text, self, now()) != null) return;
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
        var before = incomingRequests.pending(now());
        incomingRequests.message(message.getString(), commands, self, now(), requestTimeout.get());
        for (var request : incomingRequests.pending(now())) if (!before.contains(request)) rememberPlayer(request.player());
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
