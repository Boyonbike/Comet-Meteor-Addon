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
import dev.tntcoating.gui.ChatToolsHud;
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
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.config.Config;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.world.entity.EntityType;

public final class ChatTools extends Module {
    private static final Pattern HOME_CLICK = Pattern.compile("^/(?:[a-z0-9_.-]+:)?home\\s+\\S+\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int FRIEND_REFRESH_TICKS = 20;
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Boolean> autoDetect = general.add(new BoolSetting.Builder().name("auto-detect")
        .description("Adds commands from the server command tree whose next argument accepts a player name.").defaultValue(true).build());
    private final Setting<Boolean> highlightFriendNames = general.add(new BoolSetting.Builder().name("highlight-friends")
        .description("Highlights whole Meteor friend names in chat using Meteor's friend colour.").defaultValue(true).build());
    private final Setting<Boolean> runOnClick = general.add(new BoolSetting.Builder().name("run-complete-commands")
        .description("Runs commands that need no more arguments immediately. Off opens every choice in chat for confirmation.").defaultValue(false).build());
    private final Setting<List<String>> configured = general.add(new StringListSetting.Builder().name("command-options")
        .description("Extra choices: 'Label | /command {player}'. A trailing space leaves the command open for more text. %player% also works.")
        .defaultValue("Message | /msg {player} ", "Whisper | /w {player} ", "Teleport Request | /tpa {player}").build());
    private final Setting<List<String>> configuredHomes = general.add(new StringListSetting.Builder().name("homes")
        .description("Extra home names for servers that do not provide /home tab suggestions or clickable /homes output.")
        .defaultValue().build());
    private final Setting<Integer> requestTimeout = general.add(new IntSetting.Builder().name("tpa-request-timeout")
        .description("Seconds to retain incoming requests when the server does not report their expiry.")
        .defaultValue(120).range(5, 3600).sliderRange(30, 300).build());

    private final ChatTeleportState teleports = new ChatTeleportState();
    private final ChatTpaRequests incomingRequests = new ChatTpaRequests();
    private final List<String> discoveredHomes = new ArrayList<>();
    private final List<String> suggestedHomes = new ArrayList<>();
    private ClientPacketListener session;
    private volatile int homeRequest;
    private int nextHomeRequest = -1_400_000_000;
    private long homeRequestedAt;
    private ChatHomeEdit pendingHomeEdit;
    private long homeEditExpires;
    private Set<String> friendNames = Set.of();
    private int friendColor;
    private int friendRefreshTicks;

    public ChatTools() {
        super(TntCoatingAddon.CATEGORY, "chat-tools", "An interactive chat HUD for player commands, homes and teleport countdowns.");
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
        return ChatHomeNames.merge(suggestedHomes, discoveredHomes, configuredHomes.get());
    }

    public boolean loadingHomes() { return homeRequest != 0 && now() - homeRequestedAt < 5_000; }

    public void refreshHomes() {
        checkSession();
        if (session == null || loadingHomes()) return;
        homeRequest = nextHomeRequest--;
        homeRequestedAt = now();
        // Use a private transaction ID so opening Homes never replaces chat's tab-completion future.
        session.send(new ServerboundCommandSuggestionPacket(homeRequest, "/home "));
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

    @Override public void onActivate() { resetSession(); friendRefreshTicks = 0; refreshFriendHighlights(); }
    @Override public void onDeactivate() {
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

    @EventHandler private void onRender(Render2DEvent event) {
        if (mc.player == null || mc.level == null || mc.screen != null || mc.options.hideGui) return;
        ChatToolsHud.renderClosedStatus(event.graphics, timers(), requests());
    }

    private void resetSession() {
        session = null;
        homeRequest = 0;
        discoveredHomes.clear();
        suggestedHomes.clear();
        teleports.clear();
        incomingRequests.clear();
        pendingHomeEdit = null;
    }

    private void checkSession() {
        if (session == mc.getConnection()) return;
        resetSession();
        session = mc.getConnection();
    }

    @EventHandler private void onSent(PacketEvent.Sent event) {
        String command = event.packet instanceof ServerboundChatCommandPacket packet ? packet.command()
            : event.packet instanceof ServerboundChatCommandSignedPacket packet ? packet.command() : null;
        if (command == null) return;
        mc.execute(() -> {
            if (!isActive() || mc.getConnection() == null || event.connection != mc.getConnection().getConnection()) return;
            checkSession();
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
                // Another player's chat must never move timers, requests or homes, even if it imitates a server reply.
                if (ChatSender.fromPlayer(text, this::online)) return;
                teleports.message(text, now());
                collectRequests(packet.content());
                confirmHomeEdit(text);
                collectHomes(packet.content());
                for (String name : ChatHomeNames.fromMessage(text)) addHome(name);
            });
        } else if (event.packet instanceof ClientboundSetActionBarTextPacket packet) {
            mc.execute(() -> {
                if (!isActive() || mc.getConnection() == null || event.connection != mc.getConnection().getConnection()) return;
                checkSession();
                teleports.message(packet.text().getString(), now());
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
        incomingRequests.message(message.getString(), commands, self, now(), requestTimeout.get());
    }

    private void confirmHomeEdit(String message) {
        if (pendingHomeEdit == null || now() > homeEditExpires || !pendingHomeEdit.confirmedBy(message)) return;
        ChatHomeEdit edit = pendingHomeEdit;
        pendingHomeEdit = null;
        List<String> updatedDiscovered = edit.apply(discoveredHomes);
        List<String> updatedSuggested = edit.apply(suggestedHomes);
        discoveredHomes.clear();
        discoveredHomes.addAll(updatedDiscovered);
        suggestedHomes.clear();
        suggestedHomes.addAll(updatedSuggested);
        List<String> configured = edit.apply(configuredHomes.get());
        if (!configured.equals(configuredHomes.get())) configuredHomes.set(configured);
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
