package dev.tntcoating.gui;

import dev.tntcoating.core.ChatHudPages;
import dev.tntcoating.core.ChatHudBounds;
import dev.tntcoating.core.ChatTeleportState;
import dev.tntcoating.core.ChatTpaRequests;
import dev.tntcoating.core.ChatServerSettings;
import dev.tntcoating.modules.ChatTools;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.ChatComponent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.BetterChat;

/** Owned by the existing ChatScreen: no new screen, focus change, or draft replacement. */
public final class ChatToolsHud {
    private enum Panel { Home, Player, Homes, HomeActions, Hotspot, Timers, Requests, Settings }
    private record Choice(String label, String tooltip, Runnable action, Runnable rightAction) {
        Choice(String label, String tooltip, Runnable action) { this(label, tooltip, action, null); }
    }
    private record Hit(int left, int right, Choice choice) {}
    private final List<Hit> hits = new ArrayList<>();
    private final List<ChatServerSettings.Entry> settingsPath = new ArrayList<>();
    private List<ChatTpaRequests.Request> displayedRequests = List.of();
    private Panel panel = Panel.Home;
    private String player;
    private String homeSelection;
    private List<ChatTools.Option> playerOptions = List.of();
    private int page;
    private int pages = 1;
    private int top;
    private int right;

    public void selectPlayer(String name, ChatTools tools) {
        player = name;
        playerOptions = tools.options(name);
        panel = Panel.Player;
        page = 0;
        hits.clear();
    }

    public boolean back() {
        if (panel == Panel.Home) return false;
        if (panel == Panel.Settings && !settingsPath.isEmpty()) settingsPath.removeLast();
        else panel = panel == Panel.HomeActions ? Panel.Homes : Panel.Home;
        player = null;
        page = 0;
        hits.clear();
        return true;
    }

    private void closeTab() {
        panel = Panel.Home;
        player = null;
        homeSelection = null;
        playerOptions = List.of();
        settingsPath.clear();
        page = 0;
        hits.clear();
    }

    private Runnable complete(Runnable action) {
        return () -> {
            action.run();
            closeTab();
        };
    }

    private String tabTitle() {
        return switch (panel) {
            case Player -> player;
            case Homes -> "Home";
            case HomeActions -> homeSelection;
            case Settings -> settingsPath.isEmpty() ? "Settings" : settingsPath.getLast().label();
            default -> panel.name();
        };
    }

    private int separator(GuiGraphicsExtractor graphics, int x) {
        graphics.fill(x + 2, top + 2, x + 3, top + ChatHudStyle.HEIGHT - 2, ChatHudStyle.accent());
        return x + 6;
    }

    public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                       ChatTools tools, Consumer<String> fillInput) {
        var bounds = bounds(graphics);
        top = bounds.top();
        right = bounds.right();
        hits.clear();
        if (right < 32 || top < 0) return;
        graphics.fill(0, top, right, top + ChatHudStyle.HEIGHT, ChatHudStyle.background(false));
        int x = 1;
        int end = right - 1;
        List<Choice> choices = new ArrayList<>();
        List<ChatTpaRequests.Request> requests = tools.requests();
        if (!requests.equals(displayedRequests)) {
            displayedRequests = List.copyOf(requests);
            page = 0;
        }
        // Server-specific controls only appear when this server's command tree offers them.
        List<ChatServerSettings.Entry> settingsGroups = ChatServerSettings.available(ChatServerSettings.groups(),
            root -> "tpacancel".equals(root) ? tools.commandAvailable("tpacancel") || tools.commandAvailable("tpcancel")
                : tools.commandAvailable(root));
        // Only one tab is shown while a menu is open. Its title handles back/close.
        if (panel == Panel.Home) {
            if (tools.commandAvailable("home")) choices.add(new Choice("Home", "Choose a home", () -> {
                panel = Panel.Homes;
                player = null;
                page = 0;
                tools.refreshHomes();
            }));
            String reply = tools.commandAvailable("r") ? "/r " : tools.commandAvailable("reply") ? "/reply " : null;
            if (reply != null) choices.add(new Choice("Reply", "Reply to the last private message: " + reply.strip(),
                complete(() -> fillInput.accept(reply))));
            if (tools.commandAvailable("hotspot")) choices.add(new Choice("Hotspot", "Hotspot actions", () -> {
                panel = Panel.Hotspot;
                page = 0;
            }));
            if (!settingsGroups.isEmpty()) choices.add(new Choice("Settings", "Server settings", () -> {
                panel = Panel.Settings;
                settingsPath.clear();
                page = 0;
            }));
        } else {
            int titleWidth = Math.min(ChatHudStyle.width(tabTitle()), Math.max(1, Math.min(90, (end - x) / 3)));
            x = button(graphics, x, titleWidth,
                new Choice(tabTitle(), "Back / close tab (Esc)", this::back), mouseX, mouseY);
            x = separator(graphics, x);
        }
        if (!requests.isEmpty() && panel != Panel.Requests) {
            var request = requests.getFirst();
            choices.add(new Choice((request.toThem() ? "To " : "") + request.player(), request.description() + ". Show pending requests.", () -> {
                panel = Panel.Requests;
                page = 0;
            }));
            choices.add(new Choice("Yes", tools.acceptCommand(request) + " - " + request.description(), complete(() -> tools.answer(request, true))));
            choices.add(new Choice("No", tools.denyCommand(request) + " - Decline " + request.player(), complete(() -> tools.answer(request, false))));
            if (requests.size() > 1) choices.add(new Choice("+" + (requests.size() - 1), "All pending requests", () -> {
                panel = Panel.Requests;
                page = 0;
            }));
        }
        List<ChatTeleportState.Timer> timers = tools.timers();
        if (!timers.isEmpty() && panel != Panel.Timers) {
            String summary = timers.getFirst().summary(ChatTools.now());
            if (timers.size() > 1) summary += " (+" + (timers.size() - 1) + ")";
            choices.add(new Choice(summary, "Teleport timers: " + timers.getFirst().label(ChatTools.now()), () -> {
                panel = Panel.Timers;
                page = 0;
            }));
        }
        String empty = "Click a player name";
        if (panel == Panel.Player) {
            empty = player + ": no commands configured";
            for (ChatTools.Option option : playerOptions) choices.add(new Choice(option.label() + (option.detected() ? " *" : ""),
                player + " - " + option.command(), complete(() -> {
                    if (tools.runsImmediately(option)) tools.send(option.command());
                    else fillInput.accept(option.command());
                })));
        } else if (panel == Panel.Homes) {
            choices.add(new Choice("R", "Refresh home suggestions", tools::refreshHomes));
            for (String home : tools.homes()) choices.add(new Choice(home, "Left click: /home " + home + "; right click: Delete / Rename", complete(() -> tools.send("/home " + home)), () -> {
                homeSelection = home;
                panel = Panel.HomeActions;
                page = 0;
            }));
            if (tools.commandAvailable("homes"))
                choices.add(new Choice("List homes", "Send /homes to read the server's home list", complete(() -> tools.send("/homes"))));
        } else if (panel == Panel.HomeActions) {
            if (tools.commandAvailable("delhome"))
                choices.add(new Choice("Delete", "Send /delhome " + homeSelection, complete(() -> tools.send("/delhome " + homeSelection))));
            if (tools.commandAvailable("homerename")) choices.add(new Choice("Rename", "Enter a new name after /homerename " + homeSelection,
                complete(() -> fillInput.accept("/homerename " + homeSelection + " "))));
        } else if (panel == Panel.Hotspot) {
            choices.add(new Choice("TPA to hotspot", "Send /hotspot", complete(() -> tools.send("/hotspot"))));
            choices.add(new Choice("Kill hotspot", "Send /hotspot kill", complete(() -> tools.send("/hotspot kill"))));
        } else if (panel == Panel.Timers) {
            empty = "No active home or TPA timers";
            for (ChatTeleportState.Timer timer : timers) {
                choices.add(new Choice(timer.summary(ChatTools.now()) + (timer.target().isEmpty() ? "" : " - " + timer.target()),
                    timer.label(ChatTools.now()), () -> {}));
            }
        } else if (panel == Panel.Requests) {
            for (var request : requests) {
                choices.add(new Choice((request.toThem() ? "To " : "") + request.player() + " Yes", request.description() + " - " + tools.acceptCommand(request), complete(() -> tools.answer(request, true))));
                choices.add(new Choice(request.player() + " No", "Decline " + request.player() + " - " + tools.denyCommand(request), complete(() -> tools.answer(request, false))));
            }
        } else if (panel == Panel.Settings) {
            List<ChatServerSettings.Entry> entries = settingsPath.isEmpty() ? settingsGroups : settingsPath.getLast().children();
            for (ChatServerSettings.Entry entry : entries) {
                if ("/homecancel".equals(entry.command()) && !tools.canCancelHome()) continue;
                if ("/tpacancel".equals(entry.command()) && !tools.canCancelTpa()) continue;
                String tooltip = entry.menu() ? "Open " + entry.label() : entry.command() + (entry.needsInput() ? " - enter a value in chat" : "");
                choices.add(new Choice(entry.label(), tooltip, () -> {
                    if (entry.menu()) {
                        settingsPath.add(entry);
                        page = 0;
                    } else {
                        if (entry.needsInput()) fillInput.accept(entry.command());
                        else if (entry.command().equals("/tpacancel")) tools.cancelTpa();
                        else if (entry.command().equals("/homecancel")) tools.cancelHome();
                        else tools.send(entry.command());
                        closeTab();
                    }
                }));
            }
        }
        if (tools.canCancelTpa()) choices.add(new Choice("Cancel TPA", "Send " + tools.cancelTpaCommand(), complete(tools::cancelTpa)));
        if (tools.canCancelHome()) choices.add(new Choice("Cancel Home", "Send /homecancel", complete(tools::cancelHome)));

        var widths = choices.stream().map(choice -> ChatHudStyle.width(choice.label())).toList();
        var packed = ChatHudPages.pack(widths, end - x);
        int navigationWidth = right < 80 ? 12 : 24;
        if (packed.size() > 1) packed = ChatHudPages.pack(widths, end - x - navigationWidth);
        pages = packed.size();
        page = Math.min(page, pages - 1);
        for (ChatHudPages.Item item : packed.get(page)) {
            x = button(graphics, x, item.width(), choices.get(item.index()), mouseX, mouseY);
        }
        if (panel == Panel.Home && timers.isEmpty() && requests.isEmpty() && pages == 1) {
            ChatHudStyle.text(graphics, empty, x + 2, top + 3, end - x - 2, ChatHudStyle.textColor());
        }
        if (pages > 1) {
            if (navigationWidth == 24) button(graphics, end - 22, 10,
                new Choice("<", "Previous page (" + (page + 1) + "/" + pages + ")", () -> page = (page + pages - 1) % pages), mouseX, mouseY);
            button(graphics, end - 10, 10, new Choice(">", "Next page (" + (page + 1) + "/" + pages + "). Right click for previous.",
                () -> page = (page + 1) % pages, () -> page = (page + pages - 1) % pages), mouseX, mouseY);
        }
    }

    private static ChatHudBounds bounds(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.options.chatScale().get();
        int width = ChatComponent.getWidth(mc.options.chatWidth().get());
        BetterChat betterChat = Modules.get() == null ? null : Modules.get().get(BetterChat.class);
        int extra = betterChat == null ? 0 : betterChat.modifyChatWidth(0);
        return ChatHudBounds.of(width, scale, graphics.guiWidth(), graphics.guiHeight(), extra);
    }

    public static void renderClosedStatus(GuiGraphicsExtractor graphics, List<ChatTeleportState.Timer> timers, List<ChatTpaRequests.Request> requests) {
        if (timers.isEmpty() && requests.isEmpty()) return;
        var bounds = bounds(graphics);
        int y = bounds.top();
        long now = ChatTools.now();
        List<String> lines = new ArrayList<>();
        if (!timers.isEmpty()) {
            String summaries = String.join(" | ", timers.stream().map(timer -> timer.summary(now)).toList());
            String targets = String.join(", ", timers.stream().map(ChatTeleportState.Timer::target).filter(target -> !target.isEmpty()).toList());
            lines.add(summaries + (targets.isEmpty() ? "" : " - " + targets));
        }
        if (!requests.isEmpty()) lines.add("TPA " + (requests.getFirst().toThem() ? "to " : "from ") + requests.getFirst().player() + " - Yes / No: open chat"
            + (requests.size() > 1 ? " (+" + (requests.size() - 1) + ")" : ""));
        for (String label : lines) {
            graphics.fill(bounds.left(), y, bounds.right(), y + ChatHudStyle.HEIGHT, ChatHudStyle.background(false));
            graphics.fill(bounds.left(), y, bounds.left() + 1, y + ChatHudStyle.HEIGHT, ChatHudStyle.accent());
            ChatHudStyle.text(graphics, label, bounds.left() + 4, y + 3, bounds.width() - 8, ChatHudStyle.textColor());
            y += ChatHudStyle.HEIGHT + 1;
        }
    }

    private int button(GuiGraphicsExtractor graphics, int x, int width, Choice choice, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < top + ChatHudStyle.HEIGHT;
        graphics.fill(x, top, x + width, top + ChatHudStyle.HEIGHT, ChatHudStyle.outline(hover));
        if (width > 2) {
            graphics.fill(x + 1, top + 1, x + width - 1, top + ChatHudStyle.HEIGHT - 1, ChatHudStyle.background(hover));
            if (hover) graphics.fill(x + 1, top + ChatHudStyle.HEIGHT - 2, x + width - 1, top + ChatHudStyle.HEIGHT - 1, ChatHudStyle.accent());
        }
        int padding = width <= 12 ? 1 : 3;
        ChatHudStyle.text(graphics, choice.label(), x + padding, top + 3, width - padding * 2, ChatHudStyle.textColor());
        if (hover) graphics.setTooltipForNextFrame(Component.literal(choice.tooltip()), mouseX, mouseY);
        hits.add(new Hit(x, x + width, choice));
        return x + width + 2;
    }

    public boolean click(double x, double y, int button) {
        if (button != 0 && button != 1) return false;
        if (hits.isEmpty() || x < 0 || x >= right || y < top || y >= top + ChatHudStyle.HEIGHT) return false;
        for (Hit hit : List.copyOf(hits)) if (x >= hit.left() && x < hit.right()) {
            Runnable action = button == 1 ? hit.choice().rightAction() : hit.choice().action();
            if (action != null) action.run();
            hits.clear();
            break;
        }
        return true;
    }

    public boolean scroll(double x, double y, double amount) {
        if (hits.isEmpty() || x < 0 || x >= right || y < top || y >= top + ChatHudStyle.HEIGHT) return false;
        if (pages > 1 && amount != 0) page = Math.floorMod(page + (amount > 0 ? -1 : 1), pages);
        hits.clear();
        return true;
    }
}
