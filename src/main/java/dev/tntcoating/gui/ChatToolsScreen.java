package dev.tntcoating.gui;

import dev.tntcoating.core.ChatClan;
import dev.tntcoating.core.AutoTpyWhitelistImport;
import dev.tntcoating.core.AutoTpyQueue;
import java.util.Set;
import java.io.IOException;
import dev.tntcoating.core.ChatCountdowns;
import dev.tntcoating.core.ChatMessages;
import dev.tntcoating.core.ChatPlayerSearch;
import dev.tntcoating.core.ChatSavedCooldowns;
import dev.tntcoating.core.ChatServerSettings;
import dev.tntcoating.core.ChatTeleportState;
import dev.tntcoating.core.ChatToolsLayout;
import dev.tntcoating.core.ChatTpaRequests;
import dev.tntcoating.modules.ChatTools;
import dev.tntcoating.modules.DiscordIgnSync;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.settings.ColorSettingScreen;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import static dev.tntcoating.gui.ChatToolsStyle.*;

/**
 * One movable window. The sidebar, header and composer stay fixed while pages, player actions and conversations replace
 * the body in place. Every page is built from the same parts: a toolbar, captions, grouped lists, tiles and buttons.
 * Anything that needs typed input opens a dialog, so the composer is only ever for chat.
 */
public final class ChatToolsScreen extends Screen {
    private enum Page { Home, Players, Clan, Homes, Hotspot, AutoTpy, Settings, Conversation }
    private enum Tone { Primary, Secondary, Ghost, Danger }
    /** A pending bubble is a whisper still queued or not yet echoed by the server. */
    private record Bubble(boolean incoming, boolean pending, List<FormattedCharSequence> lines, int width, int height) {}
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    /**
     * A modal question with an optional text field. {@code check} returns a problem or null, {@code preview} shows the
     * command the answer will send, and {@code done} receives the answer.
     */
    private record Dialog(String title, String description, String label, boolean input, boolean secret,
                          Function<String, String> check, Function<String, String> preview, String confirm, boolean danger,
                          Consumer<String> done) {}
    private static final Page[] TABS = {Page.Home, Page.Players, Page.Clan, Page.Homes, Page.Hotspot, Page.AutoTpy, Page.Settings};
    private static final String[] TAB_LABELS = {"Overview", "Players", "Clan", "Homes", "Hotspot", "Auto TPY", "Settings"};
    private static final Icon[] NAV_ICONS = {Icon.GRID, Icon.USERS, Icon.SHIELD, Icon.PIN, Icon.FLAME, Icon.ZAP, Icon.SETTINGS};
    private static final String[] SETTINGS_SECTIONS = {"Chat Tools", "Auto TPY", "Discord sync", "Teleport cooldowns", "Server settings"};
    private static final long NOTICE_MILLIS = 4_000;
    private static final int TRAY_ROWS = 3;
    private static final String DOT = " · ";
    private final ChatTools tools;
    private final List<Hit> hits = new ArrayList<>();
    private final List<ChatServerSettings.Entry> settingsPath = new ArrayList<>();
    private final Map<String, String> drafts = new HashMap<>();
    private Screen parent;
    private Page page = Page.Home;
    private Page conversationBack = Page.Players;
    private String recipient, selectedPlayer;
    private EditBox input, homeSearch, playerSearch, autoListField, visibleSearch, settingsSearch, dialogField;
    private EditBox discordRelay;
    private final List<EditBox> visibleFields = new ArrayList<>();
    private boolean discordFormLoaded;
    private int autoSection, clanSection, settingsSection;
    private int navigationScroll;
    private int x = -1, y, w, h, bodyTop, bodyBottom, cursor, scroll, maxScroll;
    private int cx, cw;
    /** The screen measured in window units, and how many screen pixels a unit covers. */
    private int uw, uh;
    private float ui = 2;
    private int gui = 2;
    private ChatToolsLayout.Frame layout;
    private boolean dragging, resizing, content;
    private double dragX, dragY;
    private String notice = "";
    private long noticeAt;
    private int mouseX, mouseY, guiMouseX, guiMouseY;
    private GuiGraphicsExtractor graphics;
    private List<ChatTeleportState.Timer> timers = List.of();
    private List<ChatTpaRequests.Request> requests = List.of();
    private List<ChatTools.Outgoing> outgoing = List.of();
    private List<ChatMessages.Message> cachedMessages = List.of();
    private List<String> cachedPending = List.of();
    private final List<Bubble> bubbles = new ArrayList<>();
    private boolean conversationVisible;
    private boolean autoBlocklistEditing, autoImportBusy;
    private long autoImportRevision;
    private String cachedRecipient;
    private int cachedWidth, conversationScroll;
    private float cachedScale;
    /** Module data reused across frames; it refreshes ten times a second and straight after any click or key press. */
    private final Map<String, Object> memo = new HashMap<>();
    private long memoAt;
    private static final long MEMO_NANOS = 100_000_000L;
    private Dialog dialog;
    private boolean dialogTouched, drawingDialog;
    /** Where the open dialog's card was last drawn, so a click outside it can dismiss it. */
    private ChatToolsLayout.Rect dialogCard;
    private KeybindSetting capturing;

    public ChatToolsScreen(ChatTools tools) { super(Component.literal("Chat Tools")); this.tools = tools; }
    public void returnTo(Screen parent) { this.parent = parent; }

    @Override protected void init() {
        if (input == null) {
            input = field("Message public chat", 256);
            homeSearch = field("Find a player", 16);
            playerSearch = field("Search players", 16);
            autoListField = field("Search or add usernames", 1024);
            autoListField.setResponder(ignored -> { scroll = 0; hits.clear(); });
            settingsSearch = field("Search every setting", 64);
            settingsSearch.setResponder(ignored -> { scroll = 0; hits.clear(); });
            discordRelay = field("https://comet-ign-relay.example.workers.dev", 256);
            dialogField = field("", 256);
            dialogField.setResponder(ignored -> dialogTouched = true);
            homeSearch.setResponder(ignored -> hits.clear());
            playerSearch.setResponder(ignored -> { scroll = 0; hits.clear(); selectedPlayer = null; });
        }
        measure();
        if (x < 0) {
            w = Math.min(860, uw - 24);
            h = Math.min(540, uh - 24);
            x = (uw - w) / 2;
            y = (uh - h) / 2;
        }
        fit();
        focus(input);
    }

    private EditBox field(String hint, int max) {
        var box = new EditBox(font(), 0, 0, 100, 10, Component.literal(hint));
        box.setMaxLength(max);
        box.setHint(Component.literal(hint).withColor(MUTED));
        box.setBordered(false);
        box.setTextColor(TEXT);
        return box;
    }

    /** Sizes the window from the real screen, so it stays modestly sized and sharp whatever the GUI scale is. */
    private void measure() {
        var window = Minecraft.getInstance().getWindow();
        gui = Math.max(1, window.getGuiScale());
        double chosen = tools.windowScale();
        ui = chosen > 0 ? (float) chosen : Math.clamp(Math.round(window.getHeight() / 540f * 4) / 4f, 1f, gui);
        pixelsPerUnit(ui);
        uw = Math.max(1, (int) (width * gui / ui));
        uh = Math.max(1, (int) (height * gui / ui));
    }

    private int trayHeight() {
        if (outgoing.isEmpty()) return 0;
        return 8 + 22 + Math.min(TRAY_ROWS, outgoing.size()) * 20 + (outgoing.size() > TRAY_ROWS ? 16 : 0) + 4;
    }

    private void fit() {
        w = Math.clamp(w, Math.min(340, uw), uw);
        h = Math.clamp(h, Math.min(260, uh), uh);
        x = Math.clamp(x, 0, Math.max(0, uw - w));
        y = Math.clamp(y, 0, Math.max(0, uh - h));
        layout = ChatToolsLayout.frame(x, y, w, h, trayHeight());
        cx = layout.content().x(); cw = layout.content().width();
        bodyTop = layout.content().y(); bodyBottom = layout.content().bottom();
        if (input != null) {
            var box = layout.composer();
            int chip = targetChipWidth();
            input.setX(box.x() + chip + 14);
            input.setY(box.y() + (box.height() - 8) / 2);
            input.setWidth(Math.max(1, box.width() - chip - 14 - 32));
        }
    }

    private String draftKey() { return page == Page.Conversation && recipient != null ? recipient.toLowerCase(Locale.ROOT) : ""; }
    private void saveDraft() { if (input != null) drafts.put(draftKey(), input.getValue()); }
    private void notice(String text) { notice = text; noticeAt = ChatTools.now(); }
    private void navigate(Page next) {
        refresh();
        saveDraft();
        page = next;
        autoImportRevision++;
        scroll = maxScroll = 0;
        settingsPath.clear();
        notice = "";
        visibleSearch = null;
        hits.clear();
        input.setValue(drafts.getOrDefault(draftKey(), ""));
        input.setHint(Component.literal(page == Page.Conversation ? "Whisper " + recipient : "Message public chat").withColor(MUTED));
        focus(input);
        if (next == Page.Clan && !discordFormLoaded) loadDiscordForm();
        if (next == Page.Homes) tools.refreshHomes();
        if (next == Page.Hotspot && tools.commandAvailable("hotspot")) tools.refreshHotspot();
    }
    private void openDiscordConnection() { clanSection = 1; navigate(Page.Clan); }

    public void openConversation(String player) {
        if (!ChatMessages.validName(player)) return;
        saveDraft();
        if (page != Page.Conversation) conversationBack = page;
        recipient = player;
        notice = "";
        page = Page.Conversation;
        conversationScroll = 0;
        tools.rememberPlayer(player);
        tools.messages().read(player);
        hits.clear(); visibleSearch = null;
        input.setValue(drafts.getOrDefault(draftKey(), ""));
        input.setHint(Component.literal("Whisper " + recipient).withColor(MUTED));
        focus(input);
    }

    public boolean reading(String player) {
        return page == Page.Conversation && conversationVisible && player.equalsIgnoreCase(recipient) && conversationScroll == 0
            && Minecraft.getInstance().isWindowActive();
    }

    private void findPlayer(String text) {
        String name = text.strip();
        navigate(Page.Players);
        playerSearch.setValue(name);
        var matches = matchingPlayers(name);
        selectedPlayer = matches.stream().filter(n -> n.equalsIgnoreCase(name)).findFirst()
            .orElse(matches.isEmpty() && ChatMessages.validName(name) ? name : null);
        focus(playerSearch);
    }

    private List<String> matchingPlayers(String query) {
        return ChatPlayerSearch.matching(query, memo("recent", tools::recentPlayers), memo("friends", tools::friends), memo("online", tools::onlinePlayers),
            requests.stream().map(ChatTpaRequests.Request::player).toList(), memo("clan", tools::clanMembers));
    }

    private void sendInput() {
        String text = input.getValue();
        var mc = Minecraft.getInstance();
        if (text.isBlank() || mc.getConnection() == null) return;
        boolean sent;
        if (text.startsWith("/")) sent = text.length() > 1 && tools.sendCommand(text);
        else if (page == Page.Conversation) sent = tools.sendPrivate(recipient, text);
        else sent = tools.sendPublic(text);
        if (sent) {
            input.setValue(""); drafts.put(draftKey(), "");
            conversationScroll = 0;
        } else notice("Could not send. The queue may be full, or the message is too long.");
        focus(input);
    }

    private void command(String value) { tools.send(value); notice("Sent " + value); }

    @SuppressWarnings("unchecked")
    private <T> T memo(String key, Supplier<T> source) {
        long now = System.nanoTime();
        if (now - memoAt > MEMO_NANOS) { memo.clear(); memoAt = now; }
        Object value = memo.get(key);
        if (value == null && !memo.containsKey(key)) { value = source.get(); memo.put(key, value); }
        return (T) value;
    }
    private void refresh() { memo.clear(); }
    private boolean friend(String name) { return memo("friend:" + name, () -> tools.isFriend(name)); }
    private boolean clanMember(String name) { return memo("clan:" + name, () -> tools.inClan(name)); }
    private boolean onlineNow(String name) { return memo("online:" + name, () -> tools.onlineContact(name)); }
    private String roleLabel(String name) { return memo("roles:" + name, () -> tools.discordRoleLabel(name)); }
    private String discordName(String name) { return memo("discord:" + name, () -> tools.discordUsername(name)); }

    // Dialogs.

    private void ask(Dialog next, String value) {
        dialog = next;
        dialogTouched = false;
        dialogField.setValue(value == null ? "" : value);
        dialogField.moveCursorToEnd(false);
        dialogField.setHighlightPos(0);
        dialogTouched = false;
        focus(next.input() ? dialogField : null);
    }
    private void closeDialog() { dialog = null; focus(input); }
    private String dialogProblem() {
        return dialog == null || dialog.check() == null ? null : dialog.check().apply(dialogField.getValue().strip());
    }
    private void confirmDialog() {
        if (dialog == null || dialogProblem() != null) { dialogTouched = true; return; }
        Dialog done = dialog;
        String value = dialogField.getValue().strip();
        closeDialog();
        done.done().accept(value);
    }
    private void confirm(String title, String description, String confirm, String commandText, Runnable action) {
        ask(new Dialog(title, description, null, false, false, null, ignored -> commandText, confirm, true, ignored -> action.run()), "");
    }
    private void request(String title, String label, String value, String commandPrefix, Function<String, String> check, String confirm) {
        ask(new Dialog(title, null, label, true, false, check, answer -> commandPrefix + (answer.isEmpty() ? "…" : answer), confirm,
            false, answer -> { tools.sendCommand(commandPrefix + answer); notice("Sent " + commandPrefix + answer); }), value);
    }
    private static String needsText(String value) { return value.isEmpty() ? "Type something first." : null; }

    private String homeNameProblem(String name, String except) {
        if (name.isEmpty()) return "Enter a name.";
        if (name.chars().anyMatch(Character::isWhitespace)) return "Home names can't contain spaces.";
        if (name.length() > 32) return "Use 32 characters or fewer.";
        if (except != null && name.equals(except)) return "Choose a different name.";
        if (memo("homes", tools::homes).stream().anyMatch(home -> home.equalsIgnoreCase(name) && !home.equals(except)))
            return "You already have a home called " + name + ".";
        return null;
    }
    private void newHome() {
        ask(new Dialog("New home", "Sets a home where you're standing now.", "Home name", true, false,
            value -> homeNameProblem(value, null), value -> "/sethome " + (value.isEmpty() ? "…" : value), "Set home", false,
            value -> command("/sethome " + value)), "");
    }
    private void renameHome(String name) {
        ask(new Dialog("Rename " + name, null, "New name", true, false, value -> homeNameProblem(value, name),
            value -> "/homerename " + name + " " + (value.isEmpty() ? "…" : value), "Rename", false,
            value -> command("/homerename " + name + " " + value)), name);
    }
    private void deleteHome(String name) {
        confirm("Delete " + name + "?", "The server removes this home. You can't undo this.", "Delete home", "/delhome " + name,
            () -> command("/delhome " + name));
    }
    private void reply() {
        if (tools.replyPlayer() != null) { openConversation(tools.replyPlayer()); return; }
        String verb = tools.commandAvailable("r") ? "/r " : "/reply ";
        request("Reply to your last message", "Message", "", verb, ChatToolsScreen::needsText, "Send");
    }

    // Rendering.

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        measure();
        timers = memo("timers", tools::timers); requests = memo("requests", tools::requests); outgoing = memo("outgoing", tools::outgoing);
        fit();
        graphics = g; guiMouseX = mx; guiMouseY = my;
        mouseX = (int) (mx * gui / ui); mouseY = (int) (my * gui / ui);
        hits.clear(); visibleSearch = null; visibleFields.clear(); content = false; conversationVisible = false;
        g.pose().pushMatrix();
        g.pose().scale(ui / gui);
        g.fill(0, 0, uw, uh, 0x66050709);
        panel(g, x, y, w, h, RADIUS + 6, BORDER, BACKGROUND);
        navigation();
        header();
        content = true;
        cursor = bodyTop + PAD + 2 - scroll;
        g.enableScissor(cx - 4, bodyTop, cx + cw + 4, bodyBottom);
        switch (page) {
            case Home -> overview(delta);
            case Players -> players(delta);
            case Clan -> clan(delta);
            case AutoTpy -> autoTpy(delta);
            case Homes -> homes();
            case Hotspot -> hotspot();
            case Settings -> settings(delta);
            case Conversation -> conversation();
        }
        g.disableScissor();
        content = false;
        if (page != Page.Conversation) {
            int contentHeight = cursor + scroll - bodyTop;
            int viewport = layout.content().height();
            maxScroll = Math.max(0, contentHeight - viewport);
            scroll = Math.clamp(scroll, 0, maxScroll);
            if (maxScroll > 0) {
                int track = viewport - 8;
                int thumb = Math.min(track, Math.max(16, track * viewport / Math.max(1, contentHeight)));
                round(g, x + w - 6, bodyTop + 4 + (track - thumb) * scroll / maxScroll, 3, thumb, 2, BORDER);
            }
        }
        footer(delta);
        toast();
        if (dialog != null) dialog(delta);
        g.pose().popMatrix();
    }

    // Window chrome.

    private DiscordIgnSync discordSync() { return Modules.get().get(DiscordIgnSync.class); }

    private String pageTitle() {
        return switch (page) {
            case Home -> "Overview"; case Players -> "Players"; case Clan -> "Clan"; case Homes -> "Homes";
            case Hotspot -> "Hotspot"; case AutoTpy -> "Auto TPY"; case Settings -> "Settings"; case Conversation -> recipient;
        };
    }
    private String pageSubtitle() {
        return switch (page) {
            case Home -> "What needs you, your homes and recent players";
            case Players -> "Recent contacts, Meteor friends and who is online";
            case Clan -> "Members and roles from your Discord";
            case Homes -> "Teleport to, rename and organise your homes";
            case Hotspot -> "Your hotspot on this server";
            case AutoTpy -> "Accept teleport requests automatically, at the server's pace";
            case Settings -> "Every Chat Tools, Auto TPY and Discord setting in one place";
            case Conversation -> onlineNow(recipient) ? "Online" + DOT + "private messages" : "Offline" + DOT + "private messages";
        };
    }

    private void header() {
        var bar = layout.header();
        var side = layout.sidebar();
        round(graphics, x + 1, y + 1, side.width(), bar.height() - 1, RADIUS + 5, CHROME, true, false);
        divider(graphics, side.right(), bar.bottom() - 1, w - side.width() - 1, DIVIDER);
        rule(graphics, side.right(), y + 1, bar.height(), DIVIDER);
        boolean compact = side.width() < 100;
        int logo = 26, lx = compact ? x + (side.width() - logo) / 2 + 1 : x + 14, ly = y + (bar.height() - logo) / 2;
        round(graphics, lx, ly, logo, logo, 8, accent());
        icon(graphics, Icon.MESSAGE, lx, ly, logo, logo, onAccent(), 13);
        if (!compact) {
            bold(graphics, "Chat Tools", lx + logo + 9, ly + 3, side.width() - logo - 30, TEXT, bodyScale());
            small(graphics, "Comet", lx + logo + 9, ly + 15, side.width() - logo - 30, MUTED);
        }
        int right = x + w - 12;
        iconButton(Icon.CLOSE, "Close (Esc)", right - CONTROL, y + (bar.height() - CONTROL) / 2, SECONDARY, this::onClose);
        right -= CONTROL + 4;
        var waiting = memo("waiting", tools::waiting);
        int bx = right - CONTROL, by = y + (bar.height() - CONTROL) / 2;
        iconButton(Icon.BELL, waiting.total() == 0 ? "Nothing is waiting for you" : WaitingPill.summary(waiting), bx, by,
            waiting.total() > 0 ? TEXT : SECONDARY, () -> navigate(Page.Home));
        if (waiting.total() > 0) badge(String.valueOf(waiting.total()), bx + CONTROL - 9, by + 5, accent(), onAccent());
        right = bx - GAP;
        int tx = cx;
        if (page == Page.Conversation) {
            iconButton(Icon.BACK, "Back (Esc)", cx - 2, y + (bar.height() - CONTROL) / 2, SECONDARY, () -> navigate(conversationBack));
            tx = cx + CONTROL + 4;
        }
        bold(graphics, pageTitle(), tx, y + 12, Math.max(1, right - tx), TEXT, 1f);
        small(graphics, pageSubtitle(), tx, y + 27, Math.max(1, right - tx), MUTED);
    }

    private void navigation() {
        var side = layout.sidebar();
        round(graphics, side.x(), side.y(), side.width(), side.height(), RADIUS + 5, CHROME, false, true);
        rule(graphics, side.right(), side.y(), side.height(), DIVIDER);
        navigationScroll = Math.clamp(navigationScroll, 0, ChatToolsLayout.navigationScrollMax(layout, TABS.length));
        boolean compact = side.width() < 100;
        int top = side.y() + 6, bottom = side.bottom() - 34;
        graphics.enableScissor(side.x(), top, side.right(), bottom);
        var sync = discordSync();
        var stats = memo("stats", tools::autoTpyStats);
        for (int i = 0; i < TABS.length; i++) {
            var item = ChatToolsLayout.navigationItem(layout, i, navigationScroll);
            if (item.bottom() > bottom || item.y() < top) continue;
            Page tab = TABS[i];
            boolean selected = page == tab || tab == Page.Players && page == Page.Conversation;
            boolean hover = over(item.x(), item.y(), item.width(), item.height());
            if (selected || hover) round(graphics, item.x(), item.y(), item.width(), item.height(), 7, selected ? accentSurface() : RAISED);
            int iconBox = compact ? item.width() : 28;
            icon(graphics, NAV_ICONS[i], item.x() + (compact ? 0 : 4), item.y(), iconBox, item.height(), selected ? accentText() : SECONDARY, 10);
            if (!compact) type(graphics, TAB_LABELS[i], item.x() + 34, item.y() + (item.height() - capHeight(bodyScale())) / 2,
                item.width() - 62, selected ? TEXT : SECONDARY, bodyScale());
            String count = null;
            int countColor = accent(), countText = onAccent();
            int unread = memo("unread", () -> tools.messages().unread());
            if (tab == Page.Players && unread > 0) count = String.valueOf(unread);
            if (tab == Page.AutoTpy && tools.autoTpyEnabled() && stats.live() > 0) { count = String.valueOf(stats.live()); countColor = RAISED; countText = SECONDARY; }
            if (count != null) {
                if (compact) round(graphics, item.right() - 10, item.y() + 5, 6, 6, 3, countColor == RAISED ? SECONDARY : countColor);
                else badge(count, item.right() - badgeWidth(count) - 6, item.y() + item.height() / 2, countColor, countText);
            }
            if (tab == Page.Clan && sync != null) {
                int dot = syncColor(sync);
                if (compact) round(graphics, item.right() - 10, item.y() + 5, 6, 6, 3, dot);
                else round(graphics, item.right() - 13, item.y() + item.height() / 2 - 3, 6, 6, 3, dot);
            }
            register(item.x(), item.y(), item.width(), item.height(), compact ? TAB_LABELS[i] : null, true, () -> navigate(tab));
        }
        graphics.disableScissor();
        boolean online = Minecraft.getInstance().getConnection() != null;
        var server = Minecraft.getInstance().getCurrentServer();
        int fy = side.bottom() - 28;
        if (!compact) round(graphics, side.x() + 8, fy, side.width() - 16, 22, 7, mix(CHROME, RAISED, 0.5));
        round(graphics, side.x() + (compact ? side.width() / 2 - 3 : 16), fy + 8, 6, 6, 3, online ? GREEN : MUTED);
        if (!compact) small(graphics, online ? server == null ? "Singleplayer" : server.ip : "Not connected", side.x() + 28, fy + 7,
            side.width() - 42, SECONDARY);
        if (ChatToolsLayout.navigationScrollMax(layout, TABS.length) > 0)
            register(side.x(), side.bottom() - 34, side.width(), 34, "Scroll here to reach all pages", false, null);
    }

    private int syncColor(DiscordIgnSync sync) {
        return sync.syncBusy() ? accentText() : sync.isActive() && sync.syncFailed() ? AMBER : sync.lastSuccessfulSync() > 0 ? GREEN : MUTED;
    }

    private int targetChipWidth() {
        String label = page == Page.Conversation && recipient != null ? recipient : "Public";
        return Math.min(120, width(label, smallScale()) + 30);
    }

    private void footer(float delta) {
        var bar = layout.footer();
        divider(graphics, bar.x(), bar.y(), bar.width() - 1, DIVIDER);
        if (!outgoing.isEmpty()) tray();
        var box = layout.composer();
        boolean focused = input.isFocused();
        panel(graphics, box.x(), box.y(), box.width(), box.height(), 8, focused ? mix(BORDER, accent(), 0.75) : BORDER, mix(BACKGROUND, CHROME, 0.5));
        boolean whisper = page == Page.Conversation;
        int chip = targetChipWidth();
        round(graphics, box.x() + 3, box.y() + 3, chip, box.height() - 6, 6, RAISED);
        icon(graphics, whisper ? Icon.AT : Icon.GLOBE, box.x() + 5, box.y() + 3, 18, box.height() - 6, SECONDARY, 8);
        small(graphics, whisper ? recipient : "Public", box.x() + 23, box.y() + (box.height() - capHeight(smallScale())) / 2, chip - 26, TEXT);
        register(box.x() + 3, box.y() + 3, chip, box.height() - 6, whisper ? "Whispering " + recipient : "Sending to public chat", false, null);
        input.extractRenderState(graphics, mouseX, mouseY, delta);
        // Minecraft hides a field's hint while it is focused; the composer keeps a quiet one so its purpose stays clear.
        if (focused && input.getValue().isEmpty()) small(graphics, whisper ? "Whisper " + recipient + " · Enter queues it, keep typing"
            : "Message public chat · Enter queues it, keep typing", input.getX() + 8, box.y() + (box.height() - capHeight(smallScale())) / 2,
            input.getWidth() - 8, MUTED);
        int sx = box.right() - 26, sy = box.y() + 3;
        boolean ready = !input.getValue().isBlank();
        boolean hover = ready && over(sx, sy, 23, box.height() - 6);
        round(graphics, sx, sy, 23, box.height() - 6, 6, ready ? hover ? mix(accent(), TEXT, 0.15) : accent() : RAISED);
        icon(graphics, Icon.SEND, sx, sy, 23, box.height() - 6, ready ? onAccent() : MUTED, 9);
        register(sx, sy, 23, box.height() - 6, whisper ? "Whisper " + recipient + " (Enter)" : "Send (Enter)", ready, this::sendInput);
        int gx = x + w - 5, gy = y + h - 5;
        for (int i = 0; i < 3; i++) round(graphics, gx - i * 4, gy - 1, 2, 2, 1, MUTED);
        round(graphics, gx, gy - 5, 2, 2, 1, MUTED);
        round(graphics, gx, gy - 9, 2, 2, 1, MUTED);
        round(graphics, gx - 4, gy - 5, 2, 2, 1, MUTED);
    }

    /** Messages waiting for the chat or whisper cooldown, soonest first, each with its countdown and a cancel button. */
    private void tray() {
        var box = layout.tray();
        if (box.height() <= 0) return;
        panel(graphics, box.x(), box.y(), box.width(), box.height(), 8, BORDER, SURFACE);
        icon(graphics, Icon.CLOCK, box.x() + 8, box.y() + 5, 12, 12, MUTED, 8);
        bold(graphics, outgoing.size() == 1 ? "1 message waiting" : outgoing.size() + " messages waiting", box.x() + 24, box.y() + 7,
            box.width() / 2, SECONDARY, smallScale());
        String pace = "Public every " + seconds(tools.chatCooldownMs()) + DOT + "whispers every " + seconds(tools.whisperCooldownMs());
        int pw = Math.min(width(pace, smallScale()), box.width() / 2 - 10);
        small(graphics, pace, box.right() - pw - 10, box.y() + 7, pw, MUTED);
        divider(graphics, box.x() + 1, box.y() + 21, box.width() - 2, DIVIDER);
        int ry = box.y() + 24;
        for (int i = 0; i < Math.min(TRAY_ROWS, outgoing.size()); i++) {
            var item = outgoing.get(i);
            int lane = 96;
            icon(graphics, item.whisper() ? Icon.AT : Icon.GLOBE, box.x() + 8, ry, 12, 18, MUTED, 8);
            small(graphics, item.whisper() ? item.target() : "Public chat", box.x() + 24, ry + 6, lane - 26, MUTED);
            String eta = item.eta() < 100 ? "now" : String.format(Locale.ROOT, "%.1f s", item.eta() / 1000.0);
            int ew = width(eta, smallScale()) + 12;
            int ex = box.right() - 30 - ew;
            round(graphics, ex, ry + 2, ew, 14, 7, i == 0 ? accentSurface() : RAISED);
            small(graphics, eta, ex + 6, ry + 6, ew - 6, i == 0 ? accentText() : SECONDARY);
            type(graphics, item.text(), box.x() + lane, ry + 5, Math.max(1, ex - box.x() - lane - 8), TEXT, smallScale() + 0.04f);
            iconButton(Icon.CLOSE, "Cancel this message", box.right() - 26, ry, 20, 18, MUTED, () -> tools.cancelOutgoing(item));
            ry += 20;
        }
        if (outgoing.size() > TRAY_ROWS) small(graphics, "+" + (outgoing.size() - TRAY_ROWS) + " more", box.x() + 24, ry + 3, box.width() - 30, MUTED);
    }
    private static String seconds(int millis) {
        return millis % 1000 == 0 ? millis / 1000 + " s" : String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    /** Short-lived confirmation above the footer, so the body never shifts when an action reports back. */
    private void toast() {
        if (notice.isEmpty()) return;
        if (ChatTools.now() - noticeAt > NOTICE_MILLIS) { notice = ""; return; }
        float scale = smallScale();
        int tw = Math.min(cw, width(notice, scale) + 24);
        int th = capHeight(scale) + 12;
        int tx = cx + (cw - tw) / 2, ty = layout.footer().y() - th - 8;
        round(graphics, tx, ty + 1, tw, th, th / 2, 0x40000000);
        panel(graphics, tx, ty, tw, th, th / 2, mix(BORDER, TEXT, 0.12), RAISED);
        small(graphics, notice, tx + 12, ty + 6, tw - 24, TEXT);
    }

    private void dialog(float delta) {
        hits.clear();
        Dialog d = dialog;
        round(graphics, x + 1, y + 1, w - 2, h - 2, RADIUS + 5, 0xa0050609);
        int dw = Math.min(380, w - 40);
        var description = d.description() == null ? List.<FormattedCharSequence>of() : wrap(d.description(), dw - 40, smallScale());
        String problem = dialogProblem();
        String preview = d.preview() == null ? null : d.preview().apply(dialogField.getValue().strip());
        int dh = 20 + 16 + description.size() * lineHeight(smallScale()) + (description.isEmpty() ? 0 : 8)
            + (d.input() ? 14 + CONTROL + 4 + 16 : 0) + (preview == null ? 0 : 14) + 12 + CONTROL + 18;
        int dx = x + (w - dw) / 2, dy = y + Math.max(20, (h - dh) / 2 - 10);
        dialogCard = new ChatToolsLayout.Rect(dx, dy, dw, dh);
        drawingDialog = true;
        round(graphics, dx - 2, dy + 6, dw + 4, dh + 4, 16, 0x50000000);
        panel(graphics, dx, dy, dw, dh, 12, mix(BORDER, TEXT, 0.06), mix(SURFACE, RAISED, 0.35));
        int cy = dy + 20;
        bold(graphics, d.title(), dx + 20, cy, dw - 40, TEXT, 1f);
        cy += 16;
        for (var line : description) { line(line, dx + 20, cy, SECONDARY, smallScale()); cy += lineHeight(smallScale()); }
        if (!description.isEmpty()) cy += 8;
        if (d.input()) {
            bold(graphics, d.label(), dx + 20, cy, dw - 40, SECONDARY, smallScale());
            cy += 14;
            boolean bad = dialogTouched && problem != null;
            panel(graphics, dx + 20, cy, dw - 40, CONTROL + 4, 7, bad ? RED : dialogField.isFocused() ? accent() : BORDER, mix(BACKGROUND, CHROME, 0.5));
            dialogField.setX(dx + 30); dialogField.setY(cy + (CONTROL + 4 - 8) / 2); dialogField.setWidth(dw - 60);
            dialogField.extractRenderState(graphics, mouseX, mouseY, delta);
            hits.add(new Hit(dx + 20, cy, dw - 40, CONTROL + 4, () -> focus(dialogField)));
            cy += CONTROL + 4 + 4;
            if (bad) small(graphics, problem, dx + 20, cy + 2, dw - 40, RED);
            cy += 16;
        }
        if (preview != null) {
            small(graphics, "Sends " + preview, dx + 20, cy, dw - 40, MUTED);
            cy += 14;
        }
        cy += 12;
        var actions = new Actions(dx + dw - 20, cy);
        actions.add(d.confirm(), null, d.danger() ? Tone.Danger : Tone.Primary, problem == null, this::confirmDialog);
        actions.add("Cancel", "Esc", Tone.Ghost, true, this::closeDialog);
        drawingDialog = false;
    }

    // Pages.

    private void overview(float delta) {
        var actions = new Actions(cx + cw, cursor).add("Reply", Icon.REPLY,
            tools.replyPlayer() != null ? "Reply to " + tools.replyPlayer() : "Reply to your last private message", Tone.Secondary, true, this::reply);
        if (tools.commandAvailable("hotspot")) actions.add("Hotspot", Icon.FLAME, "/hotspot", Tone.Secondary, true, () -> command("/hotspot"));
        search(homeSearch, delta, cx, cursor, actions.left() - GAP - cx);
        cursor += CONTROL + 4 + PAD;
        waitingCard();
        timerList();
        statusTiles();

        caption("Favourite homes", null, "Manage", "Open Homes", () -> navigate(Page.Homes));
        var favorites = memo("favourites", tools::favoriteHomes);
        if (favorites.isEmpty()) emptyRow("No favourite homes yet", "Star a home in Homes to pin it here.");
        else {
            var tiles = ChatToolsLayout.favoriteTiles(cx, cursor, cw, favorites.size());
            for (int i = 0; i < tiles.size(); i++) homeTile(favorites.get(i), tiles.get(i));
            cursor = tiles.getLast().bottom() + PAD + 4;
        }

        boolean searching = !homeSearch.getValue().isBlank();
        caption(searching ? "Matching players" : "Recent players", null, "View all", "Open Players", () -> navigate(Page.Players));
        List<String> recent = new ArrayList<>(memo("recent", tools::recentPlayers));
        for (var request : requests) if (recent.stream().noneMatch(n -> n.equalsIgnoreCase(request.player()))) recent.add(request.player());
        if (searching) recent = matchingPlayers(homeSearch.getValue());
        if (recent.isEmpty()) emptyRow(searching ? "No matching players" : "No recent players yet",
            searching ? "Try another name, or press Enter to look up a full username." : "People you message or teleport to appear here.");
        else playerList(recent);
    }

    /** Everything that needs the player, pinned to the top of Overview until it is dealt with. */
    private void waitingCard() {
        var unread = memo("conversations", () -> tools.messages().recent()).stream().filter(p -> tools.messages().unread(p) > 0).toList();
        var sync = discordSync();
        String problem = memo("waiting", tools::waiting).problem();
        int items = requests.size() + unread.size() + (problem == null ? 0 : 1);
        if (items == 0) return;
        int itemHeight = 44, height = 30 + items * itemHeight + 4;
        int top = cursor;
        cursor += height + PAD;
        if (!visible(top, height)) return;
        panel(graphics, cx, top, cw, height, RADIUS + 2, mix(BORDER, accent(), 0.55), mix(SURFACE, accent(), 0.07));
        long pulse = ChatTools.now() % 2000;
        round(graphics, cx + 14, top + 13, 6, 6, 3, accent());
        if (pulse < 1000) round(graphics, cx + 14 - (int) (pulse / 250), top + 13 - (int) (pulse / 250), 6 + (int) (pulse / 125),
            6 + (int) (pulse / 125), 3 + (int) (pulse / 250), alpha(accent(), 0.35 * (1 - pulse / 1000.0)));
        bold(graphics, "Waiting for you", cx + 26, top + 12, cw - 70, TEXT, bodyScale());
        badge(String.valueOf(items), cx + 26 + boldWidth("Waiting for you", bodyScale()) + 8, top + 15, accent(), onAccent());
        int ry = top + 30;
        for (var request : requests) {
            divider(graphics, cx + 12, ry, cw - 24, mix(DIVIDER, accent(), 0.2));
            avatar(request.player(), cx + 12, ry + 7, 30, nameColor(request.player(), accent()), onlineNow(request.player()), SURFACE);
            var answer = new Actions(cx + cw - 12, ry + 10)
                .add("Accept", null, tools.acceptCommand(request), Tone.Primary, true, () -> tools.answer(request, true))
                .add("Decline", null, tools.denyCommand(request), Tone.Ghost, true, () -> tools.answer(request, false));
            int tx = cx + 52, tw = answer.left() - GAP - tx;
            bold(graphics, request.player(), tx, ry + 10, tw, TEXT, bodyScale());
            small(graphics, (request.toThem() ? "Asks you to teleport to them" : "Wants to teleport to you") + DOT + "expires in "
                + ChatTeleportState.remaining(request.expires() - ChatTools.now()), tx, ry + 24, tw, AMBER);
            ry += itemHeight;
        }
        for (String player : unread) {
            divider(graphics, cx + 12, ry, cw - 24, mix(DIVIDER, accent(), 0.2));
            avatar(player, cx + 12, ry + 7, 30, nameColor(player, accent()), onlineNow(player), SURFACE);
            var open = new Actions(cx + cw - 12, ry + 10).add("Open", Icon.MESSAGE, "Open the conversation", Tone.Secondary, true, () -> openConversation(player));
            int count = tools.messages().unread(player);
            var messages = tools.messages().conversation(player).messages();
            String last = messages.isEmpty() ? "" : "“" + messages.getLast().text() + "”";
            int tx = cx + 52, tw = open.left() - GAP - tx;
            bold(graphics, player + DOT + count + " new message" + (count == 1 ? "" : "s"), tx, ry + 10, tw, TEXT, bodyScale());
            small(graphics, last, tx, ry + 24, tw, SECONDARY);
            ry += itemHeight;
        }
        if (problem != null) {
            divider(graphics, cx + 12, ry, cw - 24, mix(DIVIDER, accent(), 0.2));
            round(graphics, cx + 12, ry + 7, 30, 30, 8, mix(SURFACE, AMBER, 0.15));
            icon(graphics, Icon.ALERT, cx + 12, ry + 7, 30, 30, AMBER, 12);
            var fix = new Actions(cx + cw - 12, ry + 10).add("Fix", null, "Open the Discord connection", Tone.Secondary, true, this::openDiscordConnection);
            int tx = cx + 52, tw = fix.left() - GAP - tx;
            bold(graphics, "Discord sync needs attention", tx, ry + 10, tw, TEXT, bodyScale());
            small(graphics, sync == null ? problem : sync.syncStatus(), tx, ry + 24, tw, SECONDARY);
        }
    }

    private void statusTiles() {
        int gap = 8, tileWidth = (cw - gap) / 2, height = 44, top = cursor;
        cursor += height + PAD + 4;
        if (!visible(top, height)) return;
        var stats = memo("stats", tools::autoTpyStats);
        statusTile(cx, top, tileWidth, height, Icon.ZAP, "Auto TPY",
            tools.autoTpyEnabled() ? "On" + DOT + stats.live() + " buffered" : "Off", () -> navigate(Page.AutoTpy));
        var sync = discordSync();
        String clanState = sync == null ? "Unavailable" : sync.syncBusy() ? "Syncing…" : sync.isActive() && sync.syncFailed() ? "Last sync failed"
            : sync.lastSuccessfulSync() > 0 ? sync.syncedAccounts() + " linked" + DOT + "synced " + ago(sync.lastSuccessfulSync()) : "Not synced yet";
        statusTile(cx + tileWidth + gap, top, cw - tileWidth - gap, height, Icon.SHIELD, "Clan", clanState, () -> navigate(Page.Clan));
    }
    private void statusTile(int tx, int ty, int tw, int th, Icon icon, String title, String detail, Runnable action) {
        boolean hover = over(tx, ty, tw, th);
        panel(graphics, tx, ty, tw, th, RADIUS + 2, hover ? mix(BORDER, accent(), 0.55) : BORDER, hover ? mix(SURFACE, RAISED, 0.5) : SURFACE);
        round(graphics, tx + 8, ty + 7, 30, 30, 8, accentSurface());
        icon(graphics, icon, tx + 8, ty + 7, 30, 30, accentText(), 12);
        bold(graphics, title, tx + 46, ty + 10, tw - 70, TEXT, bodyScale());
        small(graphics, detail, tx + 46, ty + 24, tw - 70, SECONDARY);
        icon(graphics, Icon.NEXT, tx + tw - 22, ty, 14, th, MUTED, 8);
        register(tx, ty, tw, th, null, true, action);
    }
    private static String ago(long since) { return ChatTeleportState.remaining(Math.max(0, ChatTools.now() - since)) + " ago"; }

    private void players(float delta) {
        search(playerSearch, delta, cx, cursor, cw);
        cursor += CONTROL + 4 + PAD;
        String query = playerSearch.getValue().strip().toLowerCase(Locale.ROOT);
        var recent = ChatPlayerSearch.matching(query, memo("recent", tools::recentPlayers), requests.stream().map(ChatTpaRequests.Request::player).toList());
        var friends = ChatPlayerSearch.matching(query, memo("friends", tools::friends));
        caption("Recent", String.valueOf(recent.size()), null, null, null);
        if (recent.isEmpty()) emptyRow(query.isEmpty() ? "Nothing here yet" : "No matching recent players", "Search for a player to start a conversation.");
        else playerList(recent);
        caption("Meteor friends", String.valueOf(friends.size()), null, null, null);
        if (friends.isEmpty()) emptyRow(query.isEmpty() ? "No friends yet" : "No matching friends", "Add a friend from their conversation or player actions.");
        else playerList(friends);
        if (!query.isEmpty()) {
            var online = ChatPlayerSearch.matching(query, memo("online", tools::onlinePlayers)).stream()
                .filter(name -> recent.stream().noneMatch(name::equalsIgnoreCase) && friends.stream().noneMatch(name::equalsIgnoreCase)).toList();
            if (!online.isEmpty()) {
                caption("Online", String.valueOf(online.size()), null, null, null);
                playerList(online);
            }
        }
        if (selectedPlayer != null && selectedPlayer.toLowerCase(Locale.ROOT).equals(query)) {
            caption("Actions for " + selectedPlayer, null, null, null, null);
            var options = tools.options(selectedPlayer);
            String player = selectedPlayer;
            menuRow(friend(player) ? "Remove Meteor friend" : "Add Meteor friend", "Meteor friends", "Update Meteor's shared Friends list",
                true, options.isEmpty(), false, () -> tools.toggleFriend(player));
            for (int i = 0; i < options.size(); i++) {
                var option = options.get(i);
                menuRow(option.label(), option.command().strip(), option.command(), false, i == options.size() - 1, false, () -> {
                    if (tools.runsImmediately(option) || !Character.isWhitespace(option.command().charAt(option.command().length() - 1)))
                        tools.sendCommand(option.command().strip());
                    else request(option.label() + DOT + player, "Text to add", "", option.command(), ChatToolsScreen::needsText, "Send");
                });
            }
            cursor += PAD;
        }
    }

    // Clan: the Discord roster and the connection that maintains it, in one place.

    private void clan(float delta) {
        var sync = discordSync();
        syncBar(sync);
        segmented(cx, cursor, new String[] {"Members", "Discord connection"},
            new String[] {"Members and roles from Discord", "The clan relay link used for the sync"},
            clanSection, choice -> { clanSection = choice; scroll = 0; hits.clear(); if (choice == 1 && !discordFormLoaded) loadDiscordForm(); });
        cursor += CONTROL + PAD;
        if (clanSection == 1) { discord(delta); return; }
        var groups = memo("clanGroups", tools::discordClanGroups);
        if (groups.friends().isEmpty() && groups.others().isEmpty()) {
            emptyRow("No Discord members yet", "Set up the Discord connection and press Sync now to load members and roles.");
            return;
        }
        clanGroup("Meteor friends", groups.friends());
        clanGroup("Members by role", groups.others());
    }

    private void syncBar(DiscordIgnSync sync) {
        int top = cursor, height = 48;
        cursor += height + PAD;
        if (!visible(top, height)) return;
        panel(graphics, cx, top, cw, height, RADIUS + 2, BORDER, SURFACE);
        if (sync == null) {
            text(graphics, "Discord sync is unavailable.", cx + 14, top + 20, cw - 28, SECONDARY);
            return;
        }
        int dot = syncColor(sync);
        round(graphics, cx + 14, top + 21, 7, 7, 3, dot);
        boolean failed = sync.isActive() && sync.syncFailed();
        String title = sync.syncBusy() ? "Reading Discord members and #ign…" : failed ? "Last sync failed"
            : sync.lastSuccessfulSync() > 0 ? "Synced " + ago(sync.lastSuccessfulSync()) : sync.hasRelay() ? "Ready to sync" : "Not set up yet";
        String detail = sync.lastSuccessfulSync() > 0 && !failed && !sync.syncBusy()
            ? sync.syncedAccounts() + " linked accounts" + (sync.isActive() && sync.intervalHours() > 0 ? DOT + "syncs every " + sync.intervalHours() + " h" : DOT + "syncs at game start")
            : sync.syncStatus();
        var actions = new Actions(cx + cw - 10, top + 12)
            .add(sync.syncBusy() ? "Syncing…" : "Sync now", Icon.REFRESH, "Read Discord and update members and roles", Tone.Primary, !sync.syncBusy(), this::syncDiscordNow);
        if ((failed || !sync.hasRelay()) && clanSection != 1) actions.add("Fix connection", null, "Open the Discord connection", Tone.Ghost, true,
            () -> { clanSection = 1; scroll = 0; if (!discordFormLoaded) loadDiscordForm(); });
        int tw = actions.left() - GAP - cx - 30;
        bold(graphics, title, cx + 30, top + 11, tw, TEXT, bodyScale());
        small(graphics, detail, cx + 30, top + 26, tw, failed ? AMBER : SECONDARY);
    }

    private void clanGroup(String label, List<String> names) {
        if (names.isEmpty()) return;
        caption(label, String.valueOf(names.size()), null, null, null);
        playerList(names, true);
    }

    private void loadDiscordForm() {
        var sync = discordSync();
        if (sync != null) {
            discordRelay.setValue(sync.configuredRelay());
            discordFormLoaded = true;
        }
    }

    private boolean saveDiscordForm() {
        var sync = discordSync();
        if (sync == null) { notice("Discord sync is unavailable."); return false; }
        String problem = sync.configureConnection(discordRelay.getValue());
        if (problem != null) { notice(problem); return false; }
        return true;
    }
    private void saveDiscordConnection() { if (saveDiscordForm()) notice("Discord connection saved. Press Sync now to read members."); }

    private void syncDiscordNow() {
        if (!discordFormLoaded) loadDiscordForm();
        if (saveDiscordForm()) discordSync().syncNow();
    }

    private void toggleDiscordSync() {
        var sync = discordSync();
        if (sync != null && (sync.isActive() || saveDiscordForm())) sync.toggle();
    }

    private void discord(float delta) {
        var sync = discordSync();
        if (sync == null) { emptyRow("Discord sync unavailable", "The Discord IGN Sync module could not be found."); return; }
        if (!discordFormLoaded) loadDiscordForm();
        caption("Connection", sync.hasRelay() ? "Relay saved" : "Setup needed", null, null, null);
        formField("Clan relay URL", discordRelay, delta);
        new Actions(cx + cw, cursor)
            .add("Save and sync", Icon.REFRESH, "Save the relay URL and read Discord now", Tone.Primary, !sync.syncBusy(), this::syncDiscordNow)
            .add("Save connection", null, "Save the relay URL", Tone.Secondary, true, this::saveDiscordConnection);
        cursor += CONTROL + PAD + 4;
        caption("Keeping in sync", null, null, null, null);
        toggleRow("Sync automatically", "Read Discord once when the game starts and on the interval below", sync.isActive(), this::toggleDiscordSync, true, false);
        numberRow("Sync every", "hours (0 syncs only at game start and when you press Sync now)", sync.intervalHours(), 1, 0, 24, sync::setIntervalHours, false, false);
        toggleRow("Keep the clan list in step", "Members who leave Discord are removed; names you added stay", sync.appliesToClan(), () -> sync.appliesToClan(!sync.appliesToClan()), false, false);
        toggleRow("Keep the Auto TPY whitelist in step", "Linked accounts are added to the whitelist; the blocklist still wins", sync.appliesToAutoTpy(), () -> sync.appliesToAutoTpy(!sync.appliesToAutoTpy()), false, true);
        cursor += PAD;
        noteRow("Discord setup", "The bot needs Server Members Intent, Message Content Intent, and View Channel plus Read Message History on #ign.");
    }

    private void formField(String label, EditBox field, float delta) {
        bold(graphics, label, cx + 2, cursor, cw, SECONDARY, smallScale());
        cursor += 14;
        int py = cursor;
        if (visible(py, CONTROL + 4)) {
            panel(graphics, cx, py, cw, CONTROL + 4, 7, field.isFocused() ? accent() : BORDER, mix(BACKGROUND, CHROME, 0.5));
            field.setX(cx + 10); field.setY(py + (CONTROL + 4 - 8) / 2); field.setWidth(Math.max(1, cw - 20));
            visibleFields.add(field);
            field.extractRenderState(graphics, mouseX, mouseY, delta);
        } else field.setFocused(false);
        cursor += CONTROL + 4 + PAD;
    }

    private void noteRow(String title, String description) {
        var lines = wrap(description, Math.max(1, cw - 52), smallScale());
        int height = 28 + lines.size() * lineHeight(smallScale()) + 8;
        int py = cursor;
        if (visible(py, height)) {
            panel(graphics, cx, py, cw, height, RADIUS + 2, DIVIDER, mix(SURFACE, BACKGROUND, 0.4));
            icon(graphics, Icon.INFO, cx + 10, py + 9, 16, 16, MUTED, 9);
            bold(graphics, title, cx + 32, py + 11, cw - 44, SECONDARY, bodyScale());
            int ly = py + 26;
            for (var part : lines) { line(part, cx + 32, ly, MUTED, smallScale()); ly += lineHeight(smallScale()); }
        }
        cursor += height + PAD;
    }

    private void numberRow(String label, String unit, int value, int step, int min, int max, IntConsumer change, boolean first, boolean last) {
        numberRow(label, unit, String.valueOf(value), value > min, value < max, () -> change.accept(Math.max(min, value - step)),
            () -> change.accept(Math.min(max, value + step)), null, first, last);
    }
    /** A value between a minus and a plus button; clicking the value can open a dialog to type it exactly. */
    private void numberRow(String label, String unit, String value, boolean canLower, boolean canRaise, Runnable lower, Runnable raise,
                           Runnable edit, boolean first, boolean last) {
        int py = cursor;
        cursor += ROW;
        if (!visible(py, ROW)) return;
        row(py, ROW, first, last, false);
        int bx = cx + cw - 10 - 92, by = py + (ROW - CONTROL) / 2;
        panel(graphics, bx, by, 92, CONTROL, 7, BORDER, mix(BACKGROUND, CHROME, 0.5));
        stepButton("−", "Decrease " + label, bx, by, canLower, lower);
        stepButton("+", "Increase " + label, bx + 92 - CONTROL, by, canRaise, raise);
        int vw = 92 - CONTROL * 2;
        float scale = bodyScale();
        boolean hover = edit != null && over(bx + CONTROL, by, vw, CONTROL);
        if (hover) round(graphics, bx + CONTROL, by + 2, vw, CONTROL - 4, 4, RAISED);
        type(graphics, value, bx + CONTROL + Math.max(2, (vw - width(value, scale)) / 2), by + (CONTROL - capHeight(scale)) / 2, vw - 2, TEXT, scale);
        if (edit != null) register(bx + CONTROL, by, vw, CONTROL, "Type an exact value", true, edit);
        int tw = bx - GAP - cx - 14;
        text(graphics, label, cx + 14, py + 9, tw, TEXT);
        small(graphics, unit, cx + 14, py + 23, tw, MUTED);
    }
    private void stepButton(String symbol, String tooltip, int bx, int by, boolean enabled, Runnable action) {
        boolean hover = enabled && over(bx, by, CONTROL, CONTROL);
        if (hover) round(graphics, bx + 2, by + 2, CONTROL - 4, CONTROL - 4, 5, RAISED);
        float scale = 1f;
        type(graphics, symbol, bx + (CONTROL - width(symbol, scale)) / 2, by + (CONTROL - capHeight(scale)) / 2 - 1, CONTROL, enabled ? TEXT : MUTED, scale);
        register(bx, by, CONTROL, CONTROL, tooltip, enabled, action);
    }

    // Auto TPY.

    private void autoRoleControls() {
        var selected = tools.autoTpyRoleIds();
        var roles = tools.autoTpyAvailableRoles();
        caption("Discord roles", selected.isEmpty() ? "No filter" : selected.size() + " selected", selected.isEmpty() ? null : "Clear", "Return to the saved whitelist and friend rules", tools::clearAutoTpyRoles);
        noteRow(selected.isEmpty() ? "Whitelist rules active" : tools.autoTpyRoleMemberCount() + " accounts match",
            selected.isEmpty() ? "Choose one or more Discord roles to accept requests from matching synced accounts. Blocklists and direction restrictions always apply."
                : "Only synced players holding at least one selected role are eligible. Manual whitelist entries and Meteor friends cannot bypass this filter.");
        if (roles.isEmpty()) menuRow("Load Discord roles", "Open the Discord connection", "Sync Discord first to choose roles", true, true, true, this::openDiscordConnection);
        for (int i = 0; i < roles.size(); i++) {
            var role = roles.get(i);
            toggleRow(role.name(), "Discord role " + role.id(), selected.contains(role.id()), () -> tools.toggleAutoTpyRole(role.id()), i == 0, i == roles.size() - 1);
        }
        long missing = selected.stream().filter(id -> roles.stream().noneMatch(role -> role.id().equals(id))).count();
        if (missing > 0) noteRow("Unavailable selected roles", missing + " selected roles are no longer in the synced roster. They grant no access; clear the filter or sync Discord again.");
        cursor += PAD;
    }

    private void autoTpy(float delta) {
        autoHero();
        segmented(cx, cursor, new String[] {"Queue", "Roles", "Lists", "Timing"},
            new String[] {"Live requests and who they come from", "Choose eligible Discord roles", "Manage the whitelist and blocklist", "Adjust pacing, retries and buffer limits"},
            autoSection, choice -> { autoSection = choice; scroll = 0; autoImportRevision++; hits.clear(); });
        cursor += CONTROL + PAD;
        if (autoSection == 1) { autoRoleControls(); return; }
        if (autoSection == 2) { autoListControls(delta); return; }
        if (autoSection == 3) { autoTimingControls(); return; }
        toggleRow("Requests to teleport to you", "Let accepted players arrive at your position", tools.autoTpyToMe(), tools::toggleAutoTpyToMe, true, false);
        toggleRow("Requests to teleport you to them", "Let accepted players move you to them", tools.autoTpyToThem(), tools::toggleAutoTpyToThem, false, true);
        cursor += PAD;
        menuRow("Discord role filter", tools.autoTpyRoleIds().isEmpty() ? "No filter" : tools.autoTpyRoleIds().size() + " roles selected",
            "Choose which Discord roles may use Auto TPY", true, true, true, () -> { autoSection = 1; scroll = 0; });
        cursor += PAD;
        var stats = memo("stats", tools::autoTpyStats);
        caption("Live requests", stats.overflow() > 0 ? stats.overflow() + " overflowed" : null, stats.live() > 0 ? "Clear all" : null, "Cancel every buffered request", tools::clearAutoTpy);
        var entries = memo("entries", tools::autoTpyEntries);
        int shown = 0;
        var live = entries.stream().filter(entry -> entry.state() == AutoTpyQueue.State.Queued || entry.state() == AutoTpyQueue.State.AwaitingReply
            || entry.state() == AutoTpyQueue.State.RetryWaiting).limit(64).toList();
        if (live.isEmpty()) emptyRow("No requests waiting", stats.latest().isEmpty() ? "Eligible requests appear here while they are accepted." : stats.latest());
        for (var entry : live) {
            shown++;
            String state = switch (entry.state()) {
                case AwaitingReply -> "Awaiting reply";
                case RetryWaiting -> "Retrying";
                default -> "Queued";
            };
            menuRow(entry.player() + (entry.friend() ? DOT + "Friend" : ""), state + DOT + "attempt " + entry.attempts(),
                entry.reason() + ". Click to cancel this request.", shown == 1, shown == live.size(), false, () -> tools.cancelAutoTpy(entry.player()));
        }
        if (stats.live() > 64) emptyRow("Showing the first 64 live requests", stats.live() + " requests remain tracked in the buffer.");
        cursor += PAD;
    }

    /** The on/off switch and live numbers, always at the top of Auto TPY. */
    private void autoHero() {
        int top = cursor, height = 52;
        var stats = memo("stats", tools::autoTpyStats);
        boolean on = tools.autoTpyEnabled();
        cursor += height + 8;
        if (visible(top, height)) {
            boolean hover = over(cx, top, cw, height);
            panel(graphics, cx, top, cw, height, RADIUS + 2, on ? mix(BORDER, accent(), 0.5) : BORDER, hover ? mix(SURFACE, RAISED, 0.4) : SURFACE);
            round(graphics, cx + 12, top + 11, 30, 30, 8, on ? accentSurface() : RAISED);
            icon(graphics, Icon.ZAP, cx + 12, top + 11, 30, 30, on ? accentText() : SECONDARY, 12);
            bold(graphics, "Automatic acceptance", cx + 52, top + 14, cw - 120, TEXT, 1f);
            String rule = !on ? "Off" + DOT + "requests wait for you in Waiting for you" : !tools.autoTpyRoleIds().isEmpty()
                ? "On" + DOT + "accepting synced players with " + tools.autoTpyRoleIds().size() + " selected role" + (tools.autoTpyRoleIds().size() == 1 ? "" : "s")
                : tools.autoTpyWhitelistOnly() ? "On" + DOT + "accepting the whitelist" + (tools.autoTpyAllowsFriends() ? " and Meteor friends" : "")
                : "On" + DOT + "accepting anyone who isn't blocklisted";
            small(graphics, rule, cx + 52, top + 30, cw - 120, SECONDARY);
            toggle(cx + cw - 50, top + (height - 20) / 2, 38, 20, on);
            register(cx, top, cw, height, null, true, () -> tools.autoTpyEnabled(!tools.autoTpyEnabled()));
        }
        int gap = 8, kw = (cw - gap * 3) / 4, ky = cursor, kh = 40;
        cursor += kh + PAD;
        if (!visible(ky, kh)) return;
        String[] values = {String.valueOf(stats.queued()), String.valueOf(stats.awaiting()), String.valueOf(stats.retries()), tools.autoTpyIntervalMs() + " ms"};
        String[] labels = {"Queued", "Awaiting reply", "Retries", "Between accepts"};
        for (int i = 0; i < 4; i++) {
            int kx = cx + i * (kw + gap);
            panel(graphics, kx, ky, i == 3 ? cw - 3 * (kw + gap) : kw, kh, RADIUS + 2, BORDER, SURFACE);
            bold(graphics, values[i], kx + 12, ky + 8, kw - 20, TEXT, 1.15f);
            small(graphics, labels[i], kx + 12, ky + 25, kw - 20, MUTED);
        }
    }

    private void autoTimingControls() {
        toggleRow("Adapt to the server", "Learn from confirmed replies; rate limits pause sending", tools.autoTpyAdaptive(), tools::toggleAutoTpyAdaptive, true, true);
        cursor += PAD;
        String[] keys = {"interval", "min-interval", "max-interval", "buffer-limit", "in-flight", "max-attempts", "reply-timeout", "retry-backoff"};
        String[] labels = {"Time between accepts", "Fastest pace", "Slowest pace", "Buffer limit", "In flight", "Attempts per request", "Reply timeout", "Retry delay"};
        int[] steps = {100, 50, 250, 64, 1, 1, 500, 250};
        int[] mins = {0, 0, 0, 64, 1, 1, 500, 0};
        int[] maxs = {60000, 60000, 60000, 16384, 32, 10, 30000, 30000};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            numberRow(labels[i], i < 3 || i > 5 ? "milliseconds" : "", tools.autoTpyNumber(key), steps[i], mins[i], maxs[i], value -> tools.setAutoTpyNumber(key, value), i == 0, i == keys.length - 1);
        }
        cursor += PAD;
        menuRow("Reset learned speed", "Restore send interval", "Restore the configured starting interval", true, true, false, tools::resetAutoTpyLearning);
    }

    private void autoListControls(float delta) {
        if (!tools.autoTpyRoleIds().isEmpty()) noteRow("Role filter active", "These whitelist and friend preferences resume when the role filter is cleared. Blocklisted players are always denied.");
        toggleRow("Whitelist only", "Off accepts anyone who isn't blocklisted", tools.autoTpyWhitelistOnly(), tools::toggleAutoTpyWhitelistOnly, true, false);
        toggleRow("Always accept Meteor friends", "Friends skip the whitelist; the blocklist always wins", tools.autoTpyAllowsFriends(), tools::toggleAutoTpyFriends, false, true);
        cursor += PAD;
        segmented(cx, cursor, new String[] {"Whitelist" + DOT + tools.autoTpyWhitelist().size(), "Blocklist" + DOT + tools.autoTpyBlocklist().size()},
            new String[] {"Players Auto TPY may accept", "Players Auto TPY never accepts"}, autoBlocklistEditing ? 1 : 0,
            choice -> { autoBlocklistEditing = choice == 1; autoImportRevision++; autoListField.setValue(""); scroll = 0; });
        cursor += CONTROL + PAD;
        String typed = autoListField.getValue().strip();
        var selected = autoBlocklistEditing ? tools.autoTpyBlocklist() : tools.autoTpyWhitelist();
        var actions = new Actions(cx + cw, cursor).add("Add", Icon.PLUS, "Add the typed usernames", Tone.Primary,
            !ChatClan.typed(typed).names().isEmpty(), this::addAutoTpyNames);
        if (!autoBlocklistEditing) actions.add("Import CSV", Icon.UPLOAD, autoImportBusy ? "Import in progress" : "Append names from a CSV; dropping files also works",
            Tone.Secondary, !autoImportBusy, () -> importAutoTpy(null));
        search(autoListField, delta, cx, cursor, Math.max(24, actions.left() - GAP - cx));
        cursor += CONTROL + 4 + PAD;
        var matches = selected.stream().filter(n -> n.toLowerCase(Locale.ROOT).contains(typed.toLowerCase(Locale.ROOT))).limit(100).toList();
        if (matches.isEmpty()) emptyRow(selected.isEmpty() ? "This list is empty" : "No matching names", "Type usernames to add. Click an existing name to remove it.");
        for (int i = 0; i < matches.size(); i++) {
            String name = matches.get(i);
            menuRow(name, "Remove", "Remove " + name + " from this list", i == 0, i == matches.size() - 1, false, () -> {
                if (autoBlocklistEditing) tools.setAutoTpyBlocklist(ChatClan.remove(tools.autoTpyBlocklist(), name));
                else tools.setAutoTpyWhitelist(ChatClan.remove(tools.autoTpyWhitelist(), name));
                autoImportRevision++;
            });
        }
        if (selected.size() > 100) emptyRow(selected.size() + " names saved", "Search to find names beyond the first 100 matches.");
    }

    private void addAutoTpyNames() {
        var typed = ChatClan.typed(autoListField.getValue());
        var old = autoBlocklistEditing ? tools.autoTpyBlocklist() : tools.autoTpyWhitelist();
        var updated = ChatClan.add(old, typed.names());
        if (autoBlocklistEditing) tools.setAutoTpyBlocklist(updated); else tools.setAutoTpyWhitelist(updated);
        autoImportRevision++; autoListField.setValue(""); focus(autoListField);
        notice("Added " + (updated.size() - old.size()) + " names; skipped " + typed.invalid() + " invalid names.");
    }

    /** The ticket prevents a late picker/worker callback from overwriting list edits or another server's UI. */
    private void importAutoTpy(List<Path> paths) {
        if (autoImportBusy || autoBlocklistEditing) return;
        autoImportBusy = true;
        var client = Minecraft.getInstance();
        var ticket = new AutoTpyWhitelistImport.Ticket(++autoImportRevision, tools.autoTpySessionKey(), tools.autoTpyWhitelist());
        Set<String> blocked = Set.copyOf(tools.autoTpyBlocklist());
        Thread worker = new Thread(() -> {
            AutoTpyWhitelistImport.Result result = null;
            String error = null;
            try {
                var chosen = paths == null ? AutoTpyFiles.choose() : List.copyOf(paths);
                if (!chosen.isEmpty()) result = AutoTpyWhitelistImport.parse(ticket.original(), blocked, AutoTpyFiles.read(chosen));
            } catch (IOException | RuntimeException | LinkageError e) {
                error = e.getMessage() == null ? "Could not import the whitelist file." : e.getMessage();
            }
            var imported = result; String problem = error;
            client.execute(() -> {
                autoImportBusy = false;
                if (client.screen != this || page != Page.AutoTpy || autoSection != 2 || autoBlocklistEditing) return;
                if (!ticket.canApply(autoImportRevision, tools.autoTpySessionKey(), tools.autoTpyWhitelist())) {
                    notice("Import discarded because the list, page or session changed."); return;
                }
                if (problem != null) { notice(problem); return; }
                if (imported == null) return;
                tools.setAutoTpyWhitelist(imported.merged());
                long overlaps = imported.merged().stream().filter(n -> ChatClan.contains(tools.autoTpyBlocklist(), n)).count();
                notice("Whitelist: added " + imported.added() + ", " + imported.duplicates() + " duplicates, " + imported.invalid() + " invalid; " + overlaps + " saved names remain blocked.");
            });
        }, "Comet Auto TPY whitelist import");
        worker.setDaemon(true); worker.start();
    }

    // Homes and hotspot.

    private void homes() {
        var actions = new Actions(cx + cw, cursor);
        actions.add("New home", Icon.PLUS, "Set a home where you're standing", Tone.Primary, true, this::newHome)
            .add("Refresh", Icon.REFRESH, "Refresh home suggestions", Tone.Ghost, !tools.loadingHomes(), tools::refreshHomes)
            .add("Server list", null, "/homes", Tone.Ghost, true, () -> command("/homes"));
        cursor += CONTROL + PAD;
        timerList();
        var homes = memo("homes", tools::homes);
        caption("Your homes", homes.isEmpty() ? null : String.valueOf(homes.size()), null, null, null);
        if (homes.isEmpty()) emptyRow(tools.loadingHomes() ? "Finding your homes…" : "No homes found", "Create a home, or ask the server for its list.");
        for (int i = 0; i < homes.size(); i++) homeRow(homes.get(i), i == 0, i == homes.size() - 1);
        if (!homes.isEmpty()) {
            cursor += 6;
            if (visible(cursor, 12)) small(graphics, "Star a home to pin it to Overview.", cx + 2, cursor, cw, MUTED);
            cursor += 12 + PAD;
        }
    }

    private void hotspot() {
        if (settingsPath.isEmpty()) {
            new Actions(cx + cw, cursor)
                .add("End hotspot", null, "/hotspot kill", Tone.Danger, true,
                    () -> confirm("End your hotspot?", "Everyone visiting is sent away. You can't undo this.", "End hotspot", "/hotspot kill", () -> command("/hotspot kill")))
                .add("Refresh", Icon.REFRESH, "/hotspot info", Tone.Ghost, true, tools::refreshHotspot);
            button("Go to hotspot", Icon.FLAME, "/hotspot", cx, cursor, buttonWidth("Go to hotspot", Icon.FLAME), Tone.Primary, true, () -> command("/hotspot"));
            cursor += CONTROL + PAD;
            caption("Details", null, null, null, null);
            var details = tools.hotspotDetails();
            if (details.isEmpty()) emptyRow("Waiting for details", "Refresh to ask the server for the latest hotspot information.");
            else {
                float scale = bodyScale();
                List<FormattedCharSequence> lines = new ArrayList<>();
                for (String line : details) lines.addAll(wrap(line, cw - 28, scale));
                int lineHeight = lineHeight(scale) + 3;
                int height = (lines.size() - 1) * lineHeight + capHeight(scale) + 24;
                if (visible(cursor, height)) {
                    panel(graphics, cx, cursor, cw, height, RADIUS + 2, BORDER, SURFACE);
                    for (int i = 0; i < lines.size(); i++) line(lines.get(i), cx + 14, cursor + 12 + i * lineHeight, SECONDARY, scale);
                }
                cursor += height + PAD;
            }
            caption("Manage", null, null, null, null);
        }
        var group = ChatServerSettings.groups().stream().filter(e -> e.label().equals("Hotspot")).findFirst();
        entries(group.map(e -> e.children().stream().filter(child -> !"/hotspot info".equals(child.command())).toList()).orElse(List.of()));
    }

    // Settings: every Chat Tools, Auto TPY and Discord setting, plus the server's own controls.

    private void settings(float delta) {
        if (settingsPath.isEmpty()) {
            search(settingsSearch, delta, cx, cursor, cw);
            cursor += CONTROL + 4 + PAD;
        }
        String query = settingsSearch.getValue().strip().toLowerCase(Locale.ROOT);
        if (!query.isEmpty() && settingsPath.isEmpty()) {
            int before = cursor;
            moduleSettings(tools, query, null);
            var sync = discordSync();
            if (sync != null) moduleSettings(sync, query, null);
            if (cursor == before) emptyRow("No settings match", "Try another word, such as cooldown, colour or relay.");
            return;
        }
        if (settingsPath.isEmpty()) {
            chips(SETTINGS_SECTIONS, settingsSection, choice -> { settingsSection = choice; scroll = 0; hits.clear(); });
            cursor += PAD;
        }
        switch (settingsPath.isEmpty() ? settingsSection : 4) {
            case 0 -> moduleSettings(tools, "", group -> !group.name.equals("Auto TPY"));
            case 1 -> moduleSettings(tools, "", group -> group.name.equals("Auto TPY"));
            case 2 -> {
                var sync = discordSync();
                if (sync == null) { emptyRow("Discord sync unavailable", "The Discord IGN Sync module could not be found."); return; }
                caption("Module", null, "Connection", "Open the Discord connection on the Clan page", this::openDiscordConnection);
                toggleRow("Discord IGN Sync", "Sync on activation and repeat at the chosen interval", sync.isActive(), this::toggleDiscordSync, true, true);
                cursor += PAD;
                moduleSettings(sync, "", null);
            }
            case 3 -> {
                caption("Teleport cooldowns", "This account on this server", null, null, null);
                cooldownRow("Home cooldown", ChatTeleportState.Kind.Home, true, false);
                cooldownRow("TP cooldown", ChatTeleportState.Kind.TPA, false, true);
                cursor += PAD;
            }
            default -> entries(ChatServerSettings.groups());
        }
    }

    /** Draws every visible setting of a module as native controls, grouped like Meteor's own screen. */
    private void moduleSettings(Module module, String query, java.util.function.Predicate<SettingGroup> groups) {
        for (SettingGroup group : module.settings) {
            if (groups != null && !groups.test(group)) continue;
            List<Setting<?>> shown = new ArrayList<>();
            for (Setting<?> setting : group) {
                if (!setting.isVisible()) continue;
                if (!query.isEmpty() && !(setting.title + " " + setting.description + " " + setting.name).toLowerCase(Locale.ROOT).contains(query)) continue;
                shown.add(setting);
            }
            if (shown.isEmpty()) continue;
            String name = module instanceof DiscordIgnSync ? "Discord" + DOT + group.name : module == tools && group.name.equals("Auto TPY") ? "Auto TPY" : group.name;
            caption(name, String.valueOf(shown.size()), null, null, null);
            for (int i = 0; i < shown.size(); i++) settingRow(module, shown.get(i), i == 0, i == shown.size() - 1);
            cursor += PAD;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void settingRow(Module module, Setting<?> setting, boolean first, boolean last) {
        String label = setting.title, description = setting.description;
        if (setting instanceof BoolSetting bool) {
            toggleRow(label, description, bool.get(), () -> bool.set(!bool.get()), first, last);
        } else if (setting instanceof IntSetting number) {
            int value = number.get(), step = step(number.sliderMax - number.sliderMin);
            numberRow(label, description, String.valueOf(value), value > number.min, value < number.max,
                () -> number.set(Math.max(number.min, value - step)), () -> number.set(Math.min(number.max, value + step)),
                () -> ask(new Dialog(label, description, "Value from " + number.min + " to " + number.max, true, false,
                    text -> parseInt(text, number.min, number.max) == null ? "Enter a whole number from " + number.min + " to " + number.max + "." : null,
                    null, "Save", false, text -> number.set(parseInt(text, number.min, number.max))), String.valueOf(value)), first, last);
        } else if (setting instanceof DoubleSetting number) {
            double value = number.get(), step = Math.max(Math.pow(10, -number.decimalPlaces), (number.sliderMax - number.sliderMin) / 20);
            String format = "%." + number.decimalPlaces + "f";
            numberRow(label, description, String.format(Locale.ROOT, format, value), value > number.min, value < number.max,
                () -> number.set(Math.max(number.min, value - step)), () -> number.set(Math.min(number.max, value + step)),
                () -> ask(new Dialog(label, description, "Value from " + number.min + " to " + number.max, true, false,
                    text -> parseDouble(text, number.min, number.max) == null ? "Enter a number from " + number.min + " to " + number.max + "." : null,
                    null, "Save", false, text -> number.set(parseDouble(text, number.min, number.max))), String.format(Locale.ROOT, format, value)), first, last);
        } else if (setting instanceof ColorSetting color) {
            int packed = color.get().getPacked() | 0xff000000;
            valueRow(label, description, null, packed, "Change", () -> Minecraft.getInstance().setScreen(new ColorSettingScreen(GuiThemes.get(), color)), first, last);
        } else if (setting instanceof KeybindSetting key) {
            valueRow(label, description, capturing == key ? "Press a key…" : key.get().isSet() ? key.get().toString() : "None", 0,
                capturing == key ? "Cancel" : "Change", () -> capturing = capturing == key ? null : key, first, last);
        } else if (setting instanceof EnumSetting choice) {
            Enum<?> current = (Enum<?>) choice.get();
            Object[] values = current.getDeclaringClass().getEnumConstants();
            valueRow(label, description, current.toString(), 0, "Next", () -> choice.set((Enum) values[(current.ordinal() + 1) % values.length]), first, last);
        } else if (setting instanceof StringSetting text) {
            boolean secret = setting.name.contains("token") || setting.name.contains("password");
            String value = text.get();
            String shown = secret ? value.isBlank() ? "Not set" : "Saved" : value.isBlank() ? "Empty" : value;
            valueRow(label, description, shown, 0, "Edit", () -> ask(new Dialog(label, description, secret ? "New value (leave blank to keep the saved one)" : "Value",
                true, secret, null, null, "Save", false, answer -> { if (!secret || !answer.isEmpty()) text.set(answer); }), secret ? "" : value), first, last);
        } else {
            Object value = setting.get();
            String shown = value instanceof List<?> list ? list.size() + (list.size() == 1 ? " entry" : " entries") : "";
            valueRow(label, description, shown, 0, "Edit in Meteor", () -> Minecraft.getInstance().setScreen(GuiThemes.get().moduleScreen(module)), first, last);
        }
    }
    private static int step(int range) {
        for (int candidate : new int[] {1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000}) if (range / candidate <= 30) return candidate;
        return 10000;
    }
    private static Integer parseInt(String text, int min, int max) {
        try { int value = Integer.parseInt(text.strip()); return value < min || value > max ? null : value; } catch (NumberFormatException e) { return null; }
    }
    private static Double parseDouble(String text, double min, double max) {
        try { double value = Double.parseDouble(text.strip()); return value < min || value > max || !Double.isFinite(value) ? null : value; } catch (NumberFormatException e) { return null; }
    }

    /** A setting showing its current value, an optional colour swatch and a button. */
    private void valueRow(String label, String description, String value, int swatch, String button, Runnable action, boolean first, boolean last) {
        int py = cursor;
        cursor += ROW;
        if (!visible(py, ROW)) return;
        boolean hover = over(cx, py, cw, ROW);
        row(py, ROW, first, last, hover);
        var actions = new Actions(cx + cw - 10, py + (ROW - CONTROL) / 2).add(button, null, null, Tone.Secondary, true, action);
        int right = actions.left();
        if (swatch != 0) {
            right -= CONTROL;
            round(graphics, right, py + (ROW - 20) / 2, 20, 20, 6, swatch);
            right -= GAP;
        } else if (value != null && !value.isEmpty()) {
            int vw = Math.min(width(value, smallScale()) + 2, Math.max(0, (right - cx) / 3));
            right -= vw;
            small(graphics, value, right, py + (ROW - capHeight(smallScale())) / 2, vw, SECONDARY);
            right -= GAP + 4;
        }
        int tw = right - cx - 14;
        text(graphics, label, cx + 14, py + 9, tw, TEXT);
        small(graphics, description, cx + 14, py + 23, tw, MUTED);
        register(cx, py, cw, ROW, null, true, action);
    }

    /** Choices that wrap onto new lines when the window is narrow. */
    private void chips(String[] labels, int selected, IntConsumer action) {
        float scale = bodyScale();
        int lx = cx, ly = cursor, height = 26;
        for (int i = 0; i < labels.length; i++) {
            int lw = width(labels[i], scale) + 22;
            if (lx > cx && lx + lw > cx + cw) { lx = cx; ly += height + 6; }
            if (visible(ly, height)) {
                boolean on = i == selected, hover = !on && over(lx, ly, lw, height);
                panel(graphics, lx, ly, lw, height, height / 2, on ? mix(BORDER, accent(), 0.7) : BORDER, on ? accentSurface() : hover ? RAISED : SURFACE);
                type(graphics, labels[i], lx + 11, ly + (height - capHeight(scale)) / 2, lw - 14, on ? TEXT : SECONDARY, scale);
                int index = i;
                register(lx, ly, lw, height, null, !on, () -> action.accept(index));
            }
            lx += lw + 6;
        }
        cursor = ly + height;
    }

    private void entries(List<ChatServerSettings.Entry> roots) {
        var available = settingsPath.isEmpty() ? ChatServerSettings.available(roots,
            root -> root.equals("tpacancel") ? tools.commandAvailable(root) || tools.commandAvailable("tpcancel") : tools.commandAvailable(root))
            : settingsPath.getLast().children();
        if (!settingsPath.isEmpty()) {
            iconButton(Icon.BACK, "Back (Esc)", cx, cursor, SECONDARY, this::back);
            List<String> trail = new ArrayList<>();
            for (var entry : settingsPath) trail.add(entry.label());
            bold(graphics, String.join(" / ", trail), cx + CONTROL + GAP, cursor + (CONTROL - capHeight(bodyScale())) / 2, cw - CONTROL - GAP, TEXT, bodyScale());
            cursor += CONTROL + PAD;
        } else caption("Server settings", null, null, null, null);
        var shown = available.stream().filter(entry -> !("/homecancel".equals(entry.command()) && !tools.canCancelHome())
            && !("/tpacancel".equals(entry.command()) && !tools.canCancelTpa())).toList();
        if (shown.isEmpty()) emptyRow("No controls available", "This server hasn't offered settings for this category.");
        for (int i = 0; i < shown.size(); i++) {
            var entry = shown.get(i);
            menuRow(entry.label(), entry.menu() ? null : entry.command().strip(), entry.menu() ? null : entry.command(), i == 0, i == shown.size() - 1,
                entry.menu(), () -> {
                    if (entry.menu()) { settingsPath.add(entry); scroll = 0; hits.clear(); }
                    else if (entry.needsInput()) request(entry.label(), "Value", "", entry.command(), ChatToolsScreen::needsText, "Send");
                    else if (entry.command().equals("/tpacancel")) tools.cancelTpa();
                    else if (entry.command().equals("/homecancel")) tools.cancelHome();
                    else command(entry.command());
                });
        }
        if (!shown.isEmpty()) cursor += PAD;
    }

    private void back() { settingsPath.removeLast(); scroll = 0; hits.clear(); }

    private void conversation() {
        cursor = bodyTop + PAD;
        int top = cursor;
        boolean friend = friend(recipient), online = onlineNow(recipient);
        int tint = nameColor(recipient, accent());
        avatar(recipient, cx, top, 30, tint, online, BACKGROUND);
        var actions = new Actions(cx + cw, top + 3)
            .add(friend ? "Unfriend" : "Add friend", friend ? Icon.USER_MINUS : Icon.USER_PLUS, "Update Meteor friendship", Tone.Secondary, true, () -> tools.toggleFriend(recipient))
            .add("TP", Icon.TELEPORT, "/tpa " + recipient, Tone.Secondary, true, () -> { tools.requestTeleport(recipient); notice("Teleport request sent to " + recipient); });
        int tx = cx + 40;
        // The header already names the player; this row says where they are and what is running.
        bold(graphics, status(recipient, online, false), tx, top + 4, actions.left() - GAP - tx, online ? TEXT : SECONDARY, bodyScale());
        String countdown = ChatCountdowns.player(recipient, timers, List.of(), ChatTools.now());
        small(graphics, countdown.isEmpty() ? "Private messages for this session" : countdown,
            tx, top + 18, actions.left() - GAP - tx, countdown.isEmpty() ? MUTED : AMBER);
        cursor += 30 + PAD;
        divider(graphics, cx, cursor - PAD / 2, cw, DIVIDER);
        for (var request : requests) if (request.player().equalsIgnoreCase(recipient)) requestStrip(request);

        int transcriptTop = cursor - 4, transcriptBottom = bodyBottom - 8;
        int transcriptHeight = Math.max(0, transcriptBottom - transcriptTop);
        float scale = bodyScale();
        conversationVisible = transcriptHeight >= capHeight(scale) + 16;
        var messages = tools.messages().conversation(recipient).messages();
        var pending = tools.pendingWhispers(recipient);
        if (messages != cachedMessages || !pending.equals(cachedPending) || cw != cachedWidth || scale != cachedScale
            || !recipient.equals(cachedRecipient)) {
            int previousHeight = transcriptHeight(bubbles);
            boolean keepReadingPosition = conversationScroll > 0 && recipient.equals(cachedRecipient) && cw == cachedWidth;
            bubbles.clear();
            for (var message : messages) bubbles.add(bubble(message.text(), message.incoming(), false, scale));
            for (String text : pending) bubbles.add(bubble(text, false, true, scale));
            if (keepReadingPosition) conversationScroll += Math.max(0, transcriptHeight(bubbles) - previousHeight);
            cachedMessages = messages; cachedPending = pending; cachedWidth = cw; cachedScale = scale; cachedRecipient = recipient;
        }
        int total = transcriptHeight(bubbles);
        conversationScroll = Math.clamp(conversationScroll, 0, Math.max(0, total - transcriptHeight));
        if (transcriptHeight <= 0) return;
        graphics.enableScissor(cx, transcriptTop, cx + cw, bodyBottom);
        if (bubbles.isEmpty()) {
            int my = transcriptTop + transcriptHeight / 2 - 10;
            String heading = "No messages with " + recipient + " yet";
            bold(graphics, heading, cx + (cw - boldWidth(heading, scale)) / 2, my, cw, SECONDARY, scale);
            String hint = "Type below to send a private message.";
            small(graphics, hint, cx + (cw - width(hint, smallScale())) / 2, my + 15, cw, MUTED);
        }
        // Latest bubble sits on the bottom edge; scrolling up reveals older messages.
        int by = transcriptBottom - total + conversationScroll;
        int outgoingColor = accent(), queued = mix(BACKGROUND, accent(), 0.14);
        long wait = tools.whisperWait();
        String pendingTip = wait > 0 ? "Waiting for the whisper cooldown" + DOT + "sends in " + ChatTeleportState.remaining(wait)
            : "Waiting for the server to confirm";
        for (int i = 0; i < bubbles.size(); i++) {
            Bubble bubble = bubbles.get(i);
            if (i > 0) by += bubbles.get(i - 1).incoming() == bubble.incoming() ? 3 : 10;
            int bx = bubble.incoming() ? cx : cx + cw - bubble.width();
            if (by + bubble.height() > transcriptTop && by < bodyBottom) {
                if (bubble.pending()) panel(graphics, bx, by, bubble.width(), bubble.height(), 10, mix(queued, accent(), 0.5), queued);
                else round(graphics, bx, by, bubble.width(), bubble.height(), 10, bubble.incoming() ? RAISED : outgoingColor);
                int ly = by + 8;
                int color = bubble.pending() ? accentText() : bubble.incoming() ? TEXT : onAccent();
                for (var text : bubble.lines()) { line(text, bx + 11, ly, color, scale); ly += lineHeight(scale); }
                if (bubble.pending() && by >= transcriptTop) register(bx, by, bubble.width(), bubble.height(), pendingTip, false, null);
            }
            by += bubble.height();
        }
        graphics.disableScissor();
        if (reading(recipient)) tools.messages().read(recipient);
    }

    private Bubble bubble(String text, boolean incoming, boolean pending, float scale) {
        var lines = wrap(text, Math.max(60, cw * 3 / 4) - 22, scale);
        int textWidth = (int) Math.ceil(lines.stream().mapToInt(font()::width).max().orElse(10) * scale);
        return new Bubble(incoming, pending, lines, Math.min(cw, Math.max(32, textWidth + 22)),
            (lines.size() - 1) * lineHeight(scale) + capHeight(scale) + 16);
    }

    private static int transcriptHeight(List<Bubble> bubbles) {
        int total = 0;
        for (int i = 0; i < bubbles.size(); i++) {
            total += bubbles.get(i).height();
            if (i > 0) total += bubbles.get(i - 1).incoming() == bubbles.get(i).incoming() ? 3 : 10;
        }
        return total;
    }

    // Shared parts.

    private void search(EditBox field, float delta, int sx, int sy, int sw) {
        int height = CONTROL + 4;
        field.setX(sx + 28); field.setY(sy + (height - 8) / 2); field.setWidth(Math.max(1, sw - 36));
        if (!visible(sy, height)) { field.setFocused(false); return; }
        panel(graphics, sx, sy, sw, height, 8, field.isFocused() ? mix(BORDER, accent(), 0.75) : BORDER, mix(BACKGROUND, CHROME, 0.5));
        icon(graphics, Icon.SEARCH, sx + 8, sy, 14, height, field.isFocused() ? TEXT : MUTED, 9);
        visibleSearch = field;
        visibleFields.add(field);
        field.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    /** A section label, with an optional count or note on the right, or a link. */
    private void caption(String label, String detail, String link, String tooltip, Runnable action) {
        float scale = smallScale();
        int ty = cursor + 3;
        if (visible(cursor, 16)) {
            int lw = boldWidth(label, scale);
            bold(graphics, label, cx + 2, ty, Math.min(lw + 2, cw * 2 / 3), SECONDARY, scale);
            if (link != null) {
                int linkWidth = width(link, scale), lx = cx + cw - linkWidth - 2;
                boolean hover = over(lx - 4, cursor, linkWidth + 8, 16);
                small(graphics, link, lx, ty, linkWidth + 1, hover ? TEXT : accentText());
                register(lx - 4, cursor, linkWidth + 8, 16, tooltip, true, action);
            } else if (detail != null) {
                int dw = Math.min(width(detail, scale), cw / 3);
                small(graphics, detail, cx + Math.min(lw + 2, cw * 2 / 3) + 8, ty, dw, MUTED);
            }
        }
        cursor += 20;
    }

    /** One row of a grouped list: consecutive rows join into a single card separated by inset dividers. */
    private void row(int ry, int height, boolean first, boolean last, boolean hover) {
        panel(graphics, cx, ry, cw, height, RADIUS + 2, BORDER, hover ? mix(SURFACE, RAISED, 0.55) : SURFACE, first, last);
        if (!first) divider(graphics, cx + 12, ry, cw - 24, DIVIDER);
    }

    private void emptyRow(String heading, String hint) {
        if (visible(cursor, ROW + 4)) {
            panel(graphics, cx, cursor, cw, ROW + 4, RADIUS + 2, DIVIDER, mix(SURFACE, BACKGROUND, 0.4));
            text(graphics, heading, cx + 14, cursor + 10, cw - 28, SECONDARY);
            small(graphics, hint, cx + 14, cursor + 24, cw - 28, MUTED);
        }
        cursor += ROW + 4 + PAD;
    }

    private void playerList(List<String> names) { playerList(names, false); }
    /** Rows on the Clan page also get a button that removes the player from the clan. */
    private void playerList(List<String> names, boolean clanPage) {
        for (int i = 0; i < names.size(); i++) playerRow(names.get(i), i == 0, i == names.size() - 1, clanPage);
        cursor += PAD + 4;
    }

    /** Meteor friends show in the friend colour and other clan members in the clan colour. */
    private int nameColor(String name, int otherwise) {
        return friend(name) ? tools.friendColor() : clanMember(name) ? tools.clanColor() : otherwise;
    }
    private String status(String name, boolean online, boolean clanPage) {
        return (online ? "Online" : "Offline") + (friend(name) ? DOT + "Friend" : "") + (!clanPage && clanMember(name) ? DOT + "Clan" : "");
    }

    private void playerRow(String name, boolean first, boolean last, boolean clanPage) {
        var incoming = requests.stream().filter(r -> r.player().equalsIgnoreCase(name)).findFirst().orElse(null);
        int unread = tools.messages().unread(name);
        String unreadText = String.valueOf(unread);
        int badge = unread > 0 ? badgeWidth(unreadText) + 6 : 0;
        float scale = bodyScale();
        boolean compactActions = cw < 380;
        int actionWidth = (clanPage ? 2 : 1) * (CONTROL + GAP)
            + (compactActions ? 2 * (CONTROL + GAP) : buttonWidth("TP", Icon.TELEPORT) + GAP + buttonWidth("Message", Icon.MESSAGE) + GAP);
        int textWidth = Math.max(1, cw - 52 - GAP - actionWidth);
        String roles = roleLabel(name);
        String discordUsername = discordName(name);
        String subtitle = clanPage && (!discordUsername.isEmpty() || !roles.isEmpty())
            ? (discordUsername.isEmpty() ? "" : "@" + discordUsername) + (roles.isEmpty() ? "" : (discordUsername.isEmpty() ? "" : DOT) + roles) : null;
        var subtitleLines = subtitle == null ? List.<FormattedCharSequence>of() : wrap(subtitle, textWidth, smallScale());
        int baseHeight = Math.max(ROW + 8, 12 + capHeight(scale) + 6 + Math.max(1, subtitleLines.size()) * lineHeight(smallScale()) + 10);
        int ry = cursor, height = baseHeight + (incoming == null ? 0 : 30);
        cursor += height;
        if (!visible(ry, height)) return;
        row(ry, height, first, last, false);
        boolean online = onlineNow(name);
        avatar(name, cx + 10, ry + (baseHeight - 30) / 2, 30, nameColor(name, accent()), online, SURFACE);
        var actions = new Actions(cx + cw - 10, ry + (baseHeight - CONTROL) / 2);
        if (clanPage) actions.icon(Icon.CLOSE, "Remove " + name + " from the clan list here (a later sync may add them back)", () -> {
            tools.removeFromClan(name);
            notice("Removed " + name + " from the clan list.");
        });
        actions.icon(Icon.MORE, "More actions for " + name, () -> findPlayer(name));
        if (compactActions) {
            actions.icon(Icon.TELEPORT, "/tpa " + name, () -> { tools.requestTeleport(name); notice("Teleport request sent to " + name); })
                .icon(Icon.MESSAGE, "Message " + name, () -> openConversation(name));
        } else actions.add("Message", Icon.MESSAGE, "Message " + name, Tone.Secondary, true, () -> openConversation(name))
            .add("TP", Icon.TELEPORT, "/tpa " + name, Tone.Ghost, true, () -> { tools.requestTeleport(name); notice("Teleport request sent to " + name); });
        int tx = cx + 50, titleY = ry + 12;
        bold(graphics, name, tx, titleY, textWidth - badge, nameColor(name, TEXT), scale);
        if (badge > 0) badge(unreadText, Math.min(tx + boldWidth(name, scale) + 6, tx + textWidth - badge + 6), titleY + capHeight(scale) / 2, accent(), onAccent());
        int sy = titleY + capHeight(scale) + 6;
        if (subtitle != null) {
            for (var part : subtitleLines) { line(part, tx, sy, MUTED, smallScale()); sy += lineHeight(smallScale()); }
        } else {
            String countdown = ChatCountdowns.player(name, timers, List.of(), ChatTools.now());
            small(graphics, countdown.isEmpty() ? status(name, online, clanPage) : countdown, tx, sy, textWidth, countdown.isEmpty() ? MUTED : AMBER);
        }
        if (incoming != null) {
            int iy = ry + baseHeight - 4;
            var answer = new Actions(cx + cw - 10, iy)
                .add("Accept", null, tools.acceptCommand(incoming), Tone.Primary, true, () -> tools.answer(incoming, true))
                .add("Decline", null, tools.denyCommand(incoming), Tone.Ghost, true, () -> tools.answer(incoming, false));
            small(graphics, (incoming.toThem() ? "Asks you to teleport to them" : "Wants to teleport to you") + DOT
                + ChatTeleportState.remaining(incoming.expires() - ChatTools.now()),
                tx, iy + (CONTROL - capHeight(smallScale())) / 2, answer.left() - GAP - tx, AMBER);
        }
    }

    private void requestStrip(ChatTpaRequests.Request request) {
        int ry = cursor, height = CONTROL + 14;
        panel(graphics, cx, ry, cw, height, RADIUS + 2, mix(BORDER, AMBER, 0.35), mix(SURFACE, AMBER, 0.07));
        var answer = new Actions(cx + cw - 7, ry + 7)
            .add("Accept", null, tools.acceptCommand(request), Tone.Primary, true, () -> tools.answer(request, true))
            .add("Decline", null, tools.denyCommand(request), Tone.Ghost, true, () -> tools.answer(request, false));
        icon(graphics, Icon.TELEPORT, cx + 8, ry, 16, height, AMBER, 9);
        text(graphics, (request.toThem() ? "Asks you to teleport to them" : "Wants to teleport to you") + DOT
            + ChatTeleportState.remaining(request.expires() - ChatTools.now()),
            cx + 30, ry + (height - capHeight(bodyScale())) / 2, answer.left() - GAP - cx - 30, AMBER);
        cursor += height + PAD;
    }

    private void homeTile(String name, ChatToolsLayout.Rect tile) {
        if (!visible(tile.y(), tile.height())) return;
        boolean hover = over(tile.x(), tile.y(), tile.width(), tile.height());
        panel(graphics, tile.x(), tile.y(), tile.width(), tile.height(), RADIUS + 2, hover ? mix(BORDER, accent(), 0.6) : BORDER, hover ? mix(SURFACE, RAISED, 0.6) : SURFACE);
        round(graphics, tile.x() + 7, tile.y() + 7, 26, 26, 7, accentSurface());
        icon(graphics, Icon.PIN, tile.x() + 7, tile.y() + 7, 26, 26, accentText(), 11);
        bold(graphics, name, tile.x() + 41, tile.y() + 9, tile.width() - 48, TEXT, bodyScale());
        String countdown = ChatCountdowns.home(name, timers, ChatTools.now());
        small(graphics, countdown.isEmpty() ? hover ? "Teleport now" : "Home" : countdown, tile.x() + 41, tile.y() + 23, tile.width() - 48,
            countdown.isEmpty() ? hover ? accentText() : MUTED : AMBER);
        register(tile.x(), tile.y(), tile.width(), tile.height(), "/home " + name, true, () -> command("/home " + name));
    }

    private void homeRow(String name, boolean first, boolean last) {
        int ry = cursor, height = ROW + 4;
        cursor += height;
        if (!visible(ry, height)) return;
        row(ry, height, first, last, false);
        boolean favorite = tools.favoriteHome(name);
        iconButton(Icon.STAR, favorite ? "Unpin from Overview" : "Pin to Overview", cx + 8, ry + (height - CONTROL) / 2, CONTROL, CONTROL,
            favorite ? AMBER : MUTED, () -> tools.toggleFavoriteHome(name));
        var actions = new Actions(cx + cw - 10, ry + (height - CONTROL) / 2);
        actions.add("Go", null, "/home " + name, Tone.Primary, true, () -> command("/home " + name))
            .icon(Icon.TRASH, "Delete " + name, RED, () -> deleteHome(name))
            .icon(Icon.PENCIL, "Rename " + name, SECONDARY, () -> renameHome(name));
        int tx = cx + 40, textWidth = actions.left() - GAP - tx;
        bold(graphics, name, tx, ry + 10, textWidth, TEXT, bodyScale());
        String countdown = ChatCountdowns.home(name, timers, ChatTools.now());
        small(graphics, countdown.isEmpty() ? favorite ? "Pinned to Overview" : "Saved home" : countdown, tx, ry + 24, textWidth,
            countdown.isEmpty() ? MUTED : AMBER);
    }

    /** Active warmups, requests and cooldowns, each with its own Cancel while the server still allows one. */
    private void timerList() {
        long now = ChatTools.now();
        for (int i = 0; i < timers.size(); i++) {
            var timer = timers.get(i);
            int ry = cursor, height = CONTROL + 14;
            cursor += height;
            if (!visible(ry, height)) continue;
            panel(graphics, cx, ry, cw, height, RADIUS + 2, mix(BORDER, AMBER, 0.35), mix(SURFACE, AMBER, 0.07), i == 0, i == timers.size() - 1);
            if (i > 0) divider(graphics, cx + 12, ry, cw - 24, mix(DIVIDER, AMBER, 0.2));
            icon(graphics, Icon.CLOCK, cx + 8, ry, 18, height, AMBER, 9);
            var actions = new Actions(cx + cw - 7, ry + 7);
            boolean home = timer.kind() == ChatTeleportState.Kind.Home;
            if (home ? tools.canCancelHome() : tools.canCancelTpa())
                actions.add("Cancel", null, home ? "/homecancel" : tools.cancelTpaCommand(), Tone.Secondary, true, () -> {
                    if (home) tools.cancelHome(); else tools.cancelTpa();
                    notice(home ? "Home cancellation sent." : "Teleport cancellation sent.");
                });
            String target = timer.target().isEmpty() ? "" : " " + timer.target();
            text(graphics, (home ? "Home" : "Teleport to") + target + DOT + timer.status(now), cx + 30, ry + (height - capHeight(bodyScale())) / 2,
                actions.left() - GAP - cx - 30, AMBER);
        }
        if (!timers.isEmpty()) cursor += PAD;
    }

    private void menuRow(String label, String detail, String tooltip, boolean first, boolean last, boolean submenu, Runnable action) {
        int ry = cursor, height = 32;
        cursor += height;
        if (!visible(ry, height)) return;
        boolean hover = over(cx, ry, cw, height);
        row(ry, height, first, last, hover);
        float scale = bodyScale();
        int labelWidth = width(label, scale);
        text(graphics, label, cx + 14, ry + (height - capHeight(scale)) / 2, cw - 40, TEXT);
        if (submenu) icon(graphics, Icon.NEXT, cx + cw - 24, ry, 14, height, hover ? TEXT : MUTED, 8);
        else if (detail != null) {
            int space = cw - labelWidth - 48;
            if (space > 40) {
                int dw = Math.min(width(detail, smallScale()), space);
                small(graphics, detail, cx + cw - 14 - dw, ry + (height - capHeight(smallScale())) / 2, dw, MUTED);
            }
        }
        register(cx, ry, cw, height, tooltip, true, action);
    }

    private void toggleRow(String label, String description, boolean checked, Runnable action, boolean first, boolean last) {
        int ry = cursor;
        cursor += ROW;
        if (!visible(ry, ROW)) return;
        boolean hover = over(cx, ry, cw, ROW);
        row(ry, ROW, first, last, hover);
        int sx = cx + cw - 44, textWidth = cw - 70;
        text(graphics, label, cx + 14, ry + 9, textWidth, TEXT);
        small(graphics, description, cx + 14, ry + 23, textWidth, MUTED);
        toggle(sx, ry + (ROW - 18) / 2, 32, 18, checked);
        register(cx, ry, cw, ROW, null, true, action);
    }
    private void toggle(int sx, int sy, int sw, int sh, boolean checked) {
        round(graphics, sx, sy, sw, sh, sh / 2, checked ? accent() : mix(RAISED, TEXT, 0.12));
        int knob = sh - 4;
        round(graphics, checked ? sx + sw - knob - 2 : sx + 2, sy + 2, knob, knob, knob / 2, checked ? onAccent() : mix(TEXT, SECONDARY, 0.4));
    }

    /**
     * The length assumed after a teleport. Auto follows what the server reports for this account (or the fallback until
     * it does); Manual pins the player's own length. Pressing a stepper switches to Manual from the current value.
     */
    private void cooldownRow(String label, ChatTeleportState.Kind kind, boolean first, boolean last) {
        var length = tools.cooldownLength(kind);
        boolean manual = length.manual() != null, compact = cw < 460;
        int ry = cursor, height = compact ? ROW + 30 : ROW + 4;
        cursor += height;
        if (!visible(ry, height)) return;
        row(ry, height, first, last, false);
        long seconds = length.effective() / 1_000;
        var actions = new Actions(cx + cw - 10, ry + (ROW + 4 - CONTROL) / 2).add("+", null, "Longer", Tone.Secondary, seconds < 86_400,
            () -> tools.manualCooldown(kind, ChatSavedCooldowns.step(seconds, true) * 1_000));
        int valueX = actions.reserve(48);
        actions.add("−", null, "Shorter", Tone.Secondary, seconds > 0, () -> tools.manualCooldown(kind, ChatSavedCooldowns.step(seconds, false) * 1_000));
        (compact ? new Actions(cx + cw - 10, ry + ROW) : actions).choice(new String[] {"Auto", "Manual"},
            new String[] {"Use the cooldown the server reports for this account", "Set your own cooldown for this account"},
            manual ? 1 : 0, choice -> tools.manualCooldown(kind, choice == 1 ? length.effective() : null));
        String value = seconds == 0 ? "Off" : ChatTeleportState.remaining(length.effective());
        float scale = bodyScale();
        text(graphics, value, valueX + (48 - width(value, scale)) / 2, ry + (ROW + 4 - capHeight(scale)) / 2, 48,
            seconds == 0 ? MUTED : manual ? TEXT : SECONDARY);
        String source = manual ? "Your setting" + (length.learned() == null ? "" : DOT + "learned " + ChatTeleportState.remaining(length.learned()))
            : length.learned() != null ? "Learned from the server"
            : length.fallback() > 0 ? "Fallback until the server reports one" : "Off until the server reports one";
        int textWidth = (compact ? cx + cw - 10 : actions.left()) - GAP - cx - 14;
        text(graphics, label, cx + 14, ry + 10, textWidth, TEXT);
        small(graphics, source, cx + 14, ry + 24, textWidth, MUTED);
    }

    /** Joined choices; the selected one is raised like a physical switch. */
    private void segmented(int bx, int by, String[] labels, String[] tooltips, int selected, IntConsumer action) {
        if (!visible(by, CONTROL)) return;
        float scale = bodyScale();
        panel(graphics, bx, by, segmentedWidth(labels), CONTROL, 8, DIVIDER, mix(BACKGROUND, CHROME, 0.6));
        int sx = bx + 3;
        for (int i = 0; i < labels.length; i++) {
            int sw = width(labels[i], scale) + 20, index = i;
            boolean hover = i != selected && over(sx, by, sw, CONTROL);
            if (i == selected) panel(graphics, sx, by + 3, sw, CONTROL - 6, 6, mix(RAISED, TEXT, 0.08), RAISED);
            type(graphics, labels[i], sx + 10, by + (CONTROL - capHeight(scale)) / 2, sw - 12, i == selected || hover ? TEXT : SECONDARY, scale);
            register(sx, by, sw, CONTROL, tooltips[i], i != selected, () -> action.accept(index));
            sx += sw;
        }
    }
    private static int segmentedWidth(String[] labels) {
        int total = 6;
        for (String label : labels) total += width(label, bodyScale()) + 20;
        return total;
    }

    private void avatar(String name, int ax, int ay, int size, int tint, boolean online, int ring) {
        round(graphics, ax, ay, size, size, size / 2, mix(SURFACE, tint, 0.3));
        String initial = name.substring(0, 1).toUpperCase(Locale.ROOT);
        float scale = size >= 28 ? bodyScale() : smallScale();
        bold(graphics, initial, ax + (size - boldWidth(initial, scale)) / 2, ay + (size - capHeight(scale)) / 2, size, mix(TEXT, tint, 0.4), scale);
        int dot = Math.max(8, size / 4);
        round(graphics, ax + size - dot + 1, ay + size - dot + 1, dot, dot, dot / 2, ring);
        round(graphics, ax + size - dot + 2, ay + size - dot + 2, dot - 2, dot - 2, (dot - 2) / 2, online ? GREEN : MUTED);
    }

    private static int badgeWidth(String text) { return Math.max(14, boldWidth(text, smallScale()) + 9); }
    /** A small pill vertically centred on {@code centerY}. */
    private void badge(String text, int bx, int centerY, int fill, int color) {
        float scale = smallScale();
        int bh = 14, bw = badgeWidth(text);
        round(graphics, bx, centerY - bh / 2, bw, bh, bh / 2, fill);
        bold(graphics, text, bx + (bw - boldWidth(text, scale)) / 2, centerY - capHeight(scale) / 2, bw, color, scale);
    }

    private List<FormattedCharSequence> wrap(String text, int width, float scale) {
        return font().split(Component.literal(text), Math.max(1, (int) (width / scale)));
    }
    private static int lineHeight(float scale) { return Math.round(11 * scale); }
    private void line(FormattedCharSequence text, int lx, int ly, int color, float scale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(Math.round(lx * ui) / ui, Math.round(ly * ui) / ui);
        graphics.pose().scale(scale);
        graphics.text(font(), text, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    private boolean visible(int by, int height) { return !content || by + height > bodyTop && by < bodyBottom; }
    private boolean over(int bx, int by, int width, int height) {
        return (dialog == null || drawingDialog) && mouseX >= bx && mouseX < bx + width && mouseY >= by && mouseY < by + height
            && (!content || mouseY >= bodyTop && mouseY < bodyBottom && mouseX >= cx - 4 && mouseX < cx + cw + 4);
    }
    private void register(int bx, int by, int width, int height, String tooltip, boolean enabled, Runnable action) {
        if (!visible(by, height)) return;
        int left = content ? Math.max(cx - 4, bx) : bx;
        int right = content ? Math.min(cx + cw + 4, bx + width) : bx + width;
        int top = content ? Math.max(bodyTop, by) : by;
        int bottom = content ? Math.min(bodyBottom, by + height) : by + height;
        if (right <= left || bottom <= top) return;
        Hit hit = new Hit(left, top, right - left, bottom - top, action);
        if (tooltip != null && (dialog == null || drawingDialog) && hit.contains(mouseX, mouseY))
            graphics.setTooltipForNextFrame(Component.literal(tooltip), guiMouseX, guiMouseY);
        if (enabled) hits.add(hit);
    }

    private static int buttonWidth(String label) { return buttonWidth(label, null); }
    private static int buttonWidth(String label, Icon icon) { return Math.max(CONTROL, width(label, bodyScale()) + 20 + (icon == null ? 0 : 14)); }
    private void button(String label, Icon icon, String tooltip, int bx, int by, int bw, Tone tone, boolean enabled, Runnable action) {
        if (!visible(by, CONTROL)) return;
        boolean hover = enabled && over(bx, by, bw, CONTROL);
        int radius = 7;
        if (!enabled && tone != Tone.Ghost) panel(graphics, bx, by, bw, CONTROL, radius, DIVIDER, SURFACE);
        else switch (tone) {
            case Primary -> round(graphics, bx, by, bw, CONTROL, radius, hover ? mix(accent(), TEXT, 0.14) : accent());
            case Secondary -> panel(graphics, bx, by, bw, CONTROL, radius, hover ? mix(BORDER, TEXT, 0.18) : BORDER, hover ? mix(RAISED, TEXT, 0.05) : RAISED);
            case Danger -> panel(graphics, bx, by, bw, CONTROL, radius, hover ? RED : mix(BORDER, RED, 0.45), hover ? mix(SURFACE, RED, 0.25) : mix(SURFACE, RED, 0.1));
            case Ghost -> { if (hover) round(graphics, bx, by, bw, CONTROL, radius, RAISED); }
        }
        int color = !enabled ? MUTED : switch (tone) {
            case Primary -> onAccent();
            case Danger -> hover ? TEXT : RED;
            case Ghost -> hover ? TEXT : SECONDARY;
            case Secondary -> TEXT;
        };
        float scale = bodyScale();
        int contentWidth = width(label, scale) + (icon == null ? 0 : 14);
        int lx = bx + Math.max(4, (bw - contentWidth) / 2);
        if (icon != null) { icon(graphics, icon, lx, by, 10, CONTROL, color, 8.5f); lx += 14; }
        type(graphics, label, lx, by + (CONTROL - capHeight(scale)) / 2, bw - (lx - bx) - 4, color, scale);
        register(bx, by, bw, CONTROL, tooltip, enabled, action);
    }
    private void iconButton(Icon icon, String tooltip, int bx, int by, int color, Runnable action) {
        iconButton(icon, tooltip, bx, by, CONTROL, CONTROL, color, action);
    }
    private void iconButton(Icon icon, String tooltip, int bx, int by, int bw, int bh, int color, Runnable action) {
        if (!visible(by, bh)) return;
        boolean hover = over(bx, by, bw, bh);
        if (hover) round(graphics, bx, by, bw, bh, 6, RAISED);
        icon(graphics, icon, bx, by, bw, bh, hover && (color == SECONDARY || color == MUTED) ? TEXT : color, Math.min(10, bh - 6));
        register(bx, by, bw, bh, tooltip, true, action);
    }

    /** Places a row's buttons from its right edge leftwards, so text beside them can stop at {@link #left()}. */
    private final class Actions {
        private final int by;
        private int right;
        Actions(int right, int by) { this.right = right; this.by = by; }
        Actions add(String label, String tooltip, Tone tone, boolean enabled, Runnable action) { return add(label, null, tooltip, tone, enabled, action); }
        Actions add(String label, Icon icon, String tooltip, Tone tone, boolean enabled, Runnable action) {
            int bw = buttonWidth(label, icon);
            right -= bw;
            button(label, icon, tooltip, right, by, bw, tone, enabled, action);
            right -= GAP;
            return this;
        }
        Actions choice(String[] labels, String[] tooltips, int selected, IntConsumer action) {
            right -= segmentedWidth(labels);
            segmented(right, by, labels, tooltips, selected, action);
            right -= GAP;
            return this;
        }
        Actions icon(Icon icon, String tooltip, Runnable action) { return icon(icon, tooltip, SECONDARY, action); }
        Actions icon(Icon icon, String tooltip, int color, Runnable action) {
            right -= CONTROL;
            iconButton(icon, tooltip, right, by, color, action);
            right -= 2;
            return this;
        }
        /** Leaves a gap for something the caller draws itself, such as a value between two buttons. */
        int reserve(int width) { right -= width; int start = right; right -= GAP; return start; }
        int left() { return right + GAP; }
    }

    // Input.

    private void focus(EditBox box) {
        for (EditBox field : List.of(input, homeSearch, playerSearch, autoListField, settingsSearch, dialogField, discordRelay))
            field.setFocused(field == box);
    }
    private EditBox focusedField() {
        if (dialog != null) return dialog.input() ? dialogField : null;
        if (input.isFocused()) return input;
        return visibleFields.stream().filter(EditBox::isFocused).findFirst().orElse(null);
    }
    private MouseButtonEvent scaled(MouseButtonEvent event) {
        return new MouseButtonEvent(event.x() * gui / ui, event.y() * gui / ui, event.buttonInfo());
    }
    /** Minecraft blurs the world behind screens with several full-screen passes; Chat Tools only dims it. */
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        refresh();
        if (event.button() != 0) return true;
        var at = scaled(event);
        double ex = at.x(), ey = at.y();
        if (dialog != null) {
            for (Hit hit : List.copyOf(hits)) if (hit.contains(ex, ey)) {
                if (dialog.input() && dialogField.mouseClicked(at, doubled)) focus(dialogField);
                else hit.action().run();
                return true;
            }
            if (dialogCard == null || !dialogCard.contains(ex, ey)) closeDialog();
            return true;
        }
        if (ex < x || ex >= x + w || ey < y || ey >= y + h) { focus(null); return true; }
        for (Hit hit : List.copyOf(hits)) if (hit.contains(ex, ey)) { hit.action().run(); hits.clear(); return true; }
        if (ex >= x + w - 12 && ey >= y + h - 12) { resizing = true; dragX = ex; dragY = ey; return true; }
        if (ey < layout.header().bottom()) { dragging = true; dragX = ex; dragY = ey; return true; }
        if (input.mouseClicked(at, doubled)) focus(input);
        else {
            EditBox clicked = null;
            if (ey >= bodyTop && ey < bodyBottom)
                for (EditBox field : visibleFields) if (field.mouseClicked(at, doubled)) { clicked = field; break; }
            focus(clicked);
        }
        return true;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging && !resizing) return false;
        var at = scaled(event);
        int ddx = (int) (at.x() - dragX), ddy = (int) (at.y() - dragY);
        if (resizing) { w += ddx; h += ddy; } else { x += ddx; y += ddy; }
        fit(); hits.clear(); dragX += ddx; dragY += ddy;
        return true;
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) { dragging = resizing = false; return true; }
    @Override public boolean mouseScrolled(double gx, double gy, double horizontal, double vertical) {
        if (dialog != null) return true;
        double mx = gx * gui / ui, my = gy * gui / ui;
        double amount = vertical != 0 ? vertical : horizontal;
        if (layout.sidebar().contains(mx, my)) {
            navigationScroll = Math.clamp(navigationScroll - (int) Math.signum(amount) * 32, 0, ChatToolsLayout.navigationScrollMax(layout, TABS.length));
            hits.clear(); return true;
        }
        if (mx < cx - 4 || mx >= cx + cw + 4 || my < bodyTop || my >= bodyBottom) return true;
        if (page == Page.Conversation) conversationScroll = Math.max(0, conversationScroll + (int) Math.signum(amount) * 30);
        else scroll = Math.clamp(scroll - (int) Math.signum(amount) * 30, 0, maxScroll);
        hits.clear();
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        refresh();
        if (capturing != null) {
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) capturing.set(Keybind.none());
            else if (event.key() != GLFW.GLFW_KEY_ESCAPE) capturing.set(Keybind.fromKey(event.key()));
            capturing = null;
            return true;
        }
        boolean enter = event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER;
        if (dialog != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) closeDialog();
            else if (enter) confirmDialog();
            else if (dialog.input()) dialogField.keyPressed(event);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (!settingsPath.isEmpty()) back();
            else if (page == Page.Conversation) navigate(conversationBack);
            else if (page != Page.Home) navigate(Page.Home);
            else onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            List<EditBox> fields = new ArrayList<>(visibleFields); fields.add(input);
            int current = fields.indexOf(focusedField());
            focus(fields.get((current + 1) % fields.size())); return true;
        }
        if (enter) {
            EditBox focused = focusedField();
            if (page == Page.Clan && clanSection == 1 && focused == discordRelay) saveDiscordConnection();
            else if (visibleSearch == autoListField && autoListField.isFocused()) addAutoTpyNames();
            else if (focused == settingsSearch) focus(input);
            else if (visibleSearch != null && visibleSearch.isFocused()) findPlayer(visibleSearch.getValue());
            else sendInput();
            return true;
        }
        if (input.isFocused()) return input.keyPressed(event);
        EditBox field = focusedField();
        return field != null && field.keyPressed(event);
    }
    @Override public boolean charTyped(CharacterEvent event) {
        if (capturing != null) return true;
        if (dialog != null) return dialog.input() && dialogField.charTyped(event);
        if (input.isFocused()) return input.charTyped(event);
        EditBox field = focusedField();
        return field != null && field.charTyped(event);
    }
    @Override public void onFilesDrop(List<Path> files) {
        if (page == Page.AutoTpy) { if (autoBlocklistEditing) notice("Switch to Whitelist to import a CSV."); else { autoSection = 2; scroll = 0; autoImportRevision++; importAutoTpy(files); } }
        else notice("Drop CSV files on the Auto TPY page to import a whitelist.");
    }
    @Override public void tick() { if (!tools.isActive() || Minecraft.getInstance().getConnection() == null) Minecraft.getInstance().setScreen(null); }
    @Override public void removed() {
        autoImportRevision++; saveDraft(); dragging = resizing = false; capturing = null; dialog = null;
        if (input != null) focus(null);
    }
    @Override public void onClose() { Minecraft.getInstance().setScreen(Minecraft.getInstance().level == null ? null : parent); }
    @Override public boolean isPauseScreen() { return false; }
}
