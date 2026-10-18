package dev.tntcoating.modules;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.tntcoating.TntCoatingAddon;
import dev.tntcoating.core.ChatCommandTemplate;
import dev.tntcoating.gui.ChatToolsScreen;
import dev.tntcoating.mixin.EntityArgumentAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.EntityType;

public final class ChatTools extends Module {
    private final SettingGroup general = settings.getDefaultGroup();
    private final Setting<Boolean> autoDetect = general.add(new BoolSetting.Builder().name("auto-detect")
        .description("Adds commands from the server command tree whose next argument accepts a player name.").defaultValue(true).build());
    private final Setting<Boolean> runOnClick = general.add(new BoolSetting.Builder().name("run-complete-commands")
        .description("Runs commands that need no more arguments immediately. Off opens every choice in chat for confirmation.").defaultValue(false).build());
    private final Setting<List<String>> configured = general.add(new StringListSetting.Builder().name("command-options")
        .description("Extra choices: 'Label | /command {player}'. A trailing space leaves the command open for more text. %player% also works.")
        .defaultValue("Message | /msg {player} ", "Whisper | /w {player} ", "Teleport Request | /tpa {player}").build());

    public ChatTools() {
        super(TntCoatingAddon.CATEGORY, "chat-tools", "Click a player's name in chat to choose a command to use with them.");
    }

    public boolean openFor(Style style) {
        String player = playerFrom(style);
        if (player == null) return false;
        List<Option> options = options(player);
        if (options.isEmpty()) return false;
        mc.setScreen(new ChatToolsScreen(GuiThemes.get(), player, options, this::choose));
        return true;
    }

    private void choose(Option option) {
        String command = option.command();
        if (runOnClick.get() && option.complete() && !Character.isWhitespace(command.charAt(command.length() - 1))) {
            if (mc.getConnection() != null) mc.getConnection().sendCommand(withoutSlash(command));
            mc.setScreen(null);
        } else mc.setScreen(new ChatScreen(command, true));
    }

    private List<Option> options(String player) {
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
            String[] tokens = clicked.strip().split("\\s+");
            for (int i = 1; i < tokens.length; i++) {
                String name = onlineName(tokens[i]);
                if (name != null) return name;
            }
        }
        return null;
    }

    private String onlineName(String candidate) {
        if (candidate == null || !candidate.matches("[A-Za-z0-9_]{1,16}")) return null;
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
