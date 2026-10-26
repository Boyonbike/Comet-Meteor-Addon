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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * One movable window. The header tabs and the composer stay fixed while pages, player actions and conversations replace
 * the body in place. Every page is built from the same parts: a toolbar, captions, grouped lists, tiles and buttons.
 */
public final class ChatToolsScreen extends Screen {
    private enum Page { Home, Players, Clan, Homes, Hotspot, Settings, AutoTpy, Conversation }
    private enum Tone { Primary, Secondary, Ghost, Danger }
    /** A pending bubble is a whisper still queued or not yet echoed by the server. */
    private record Bubble(boolean incoming, boolean pending, List<FormattedCharSequence> lines, int width, int height) {}
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private static final Page[] TABS = {Page.Home, Page.Players, Page.Clan, Page.Homes, Page.Hotspot, Page.Settings};
    private static final String[] TAB_LABELS = {"Overview", "Players", "Clan", "Homes", "Hotspot", "Settings"};
    private static final long NOTICE_MILLIS = 4_000;
    private static final String DOT = " · ";
    private final ChatTools tools;
    private final ChatToolsFiles files = new ChatToolsFiles();
    private final List<Hit> hits = new ArrayList<>();
    private final List<ChatServerSettings.Entry> settingsPath = new ArrayList<>();
    private final Map<String, String> drafts = new HashMap<>();
    private Screen parent;
    private Page page = Page.Home;
    private Page conversationBack = Page.Players;
    private String recipient, selectedPlayer;
    private EditBox input, homeSearch, playerSearch, clanField, autoListField, visibleSearch;
    private int x = -1, y, w, h, bodyTop, bodyBottom, cursor, scroll, maxScroll;
    private int cx, cw;
    private ChatToolsLayout.Frame layout;
    private boolean dragging, resizing, content;
    private double dragX, dragY;
    private String notice = "";
    private long noticeAt;
    private int mouseX, mouseY;
    private GuiGraphicsExtractor graphics;
    private List<ChatTeleportState.Timer> timers = List.of();
    private List<ChatTpaRequests.Request> requests = List.of();
    private List<ChatMessages.Message> cachedMessages = List.of();
    private List<String> cachedPending = List.of();
    private final List<Bubble> bubbles = new ArrayList<>();
    private boolean conversationVisible;
    private boolean autoBlocklistEditing, autoImportBusy;
    private long autoImportRevision;
    private String cachedRecipient;
    private int cachedWidth, conversationScroll;
    private float cachedScale;

    public ChatToolsScreen(ChatTools tools) { super(Component.literal("Chat Tools")); this.tools = tools; }
    public void returnTo(Screen parent) { this.parent = parent; }

    @Override protected void init() {
        if (input == null) {
            input = field("Chat or /command", 256);
            homeSearch = field("Search players", 16);
            playerSearch = field("Search players", 16);
            clanField = field("Search or add members", 256);
            autoListField = field("Search or add usernames", 1024);
            autoListField.setResponder(ignored -> { scroll=0;hits.clear(); });
            homeSearch.setResponder(ignored -> hits.clear());
            playerSearch.setResponder(ignored -> { scroll = 0; hits.clear(); selectedPlayer = null; });
            clanField.setResponder(ignored -> { scroll = 0; hits.clear(); });
        }
        if (x < 0) {
            w = Math.min(660, width - 16);
            h = Math.min(420, height - 16);
            x = (width - w) / 2;
            y = (height - h) / 2;
        }
        fit();
        focus(input);
    }

    private EditBox field(String hint, int max) {
        var box = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 18, Component.literal(hint));
        box.setMaxLength(max);
        box.setHint(Component.literal(hint));
        box.setBordered(false);
        box.setTextColor(TEXT);
        return box;
    }

    private void fit() {
        w = Math.clamp(w, Math.min(320, width), width);
        h = Math.clamp(h, Math.min(220, height), height);
        x = Math.clamp(x, 0, Math.max(0, width - w));
        y = Math.clamp(y, 0, Math.max(0, height - h));
        layout = ChatToolsLayout.frame(x, y, w, h);
        cx = layout.content().x(); cw = layout.content().width();
        bodyTop = layout.content().y(); bodyBottom = layout.content().bottom();
        if (input != null) {
            var box = layout.composer();
            input.setX(box.x() + 8);
            input.setY(box.y() + (box.height() - 8) / 2);
            input.setWidth(box.width() - buttonWidth("Send") - GAP - 16);
        }
    }

    private String draftKey() { return page == Page.Conversation && recipient != null ? recipient.toLowerCase(Locale.ROOT) : ""; }
    private void saveDraft() { if (input != null) drafts.put(draftKey(), input.getValue()); }
    private void notice(String text) { notice = text; noticeAt = ChatTools.now(); }
    private void navigate(Page next) {
        saveDraft();
        page = next;
        autoImportRevision++;
        scroll = maxScroll = 0;
        settingsPath.clear();
        notice = "";
        visibleSearch = null;
        hits.clear();
        input.setValue(drafts.getOrDefault(draftKey(), ""));
        input.setHint(Component.literal(page == Page.Conversation ? "Message " + recipient : "Chat or /command"));
        focus(input);
        if (next == Page.Homes) tools.refreshHomes();
        if (next == Page.Hotspot && tools.commandAvailable("hotspot")) tools.refreshHotspot();
    }

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
        input.setHint(Component.literal("Message " + recipient));
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
        return ChatPlayerSearch.matching(query, tools.recentPlayers(), tools.friends(), tools.onlinePlayers(),
            requests.stream().map(ChatTpaRequests.Request::player).toList(), tools.clanMembers());
    }

    private void fill(String command) {
        if (page == Page.Conversation) navigate(Page.Players);
        input.setValue(command);
        input.moveCursorToEnd(false);
        focus(input);
        notice("Finish the command below and press Enter.");
    }

    private void sendInput() {
        String text = input.getValue();
        var mc = Minecraft.getInstance();
        if (text.isBlank() || mc.getConnection() == null) return;
        boolean sent = true, whisper = false;
        if (text.startsWith("/")) {
            if (text.length() > 1) { whisper = tools.isWhisper(text); sent = tools.sendCommand(text); } else sent = false;
        }
        else if (page == Page.Conversation) { whisper = true; sent = tools.sendPrivate(recipient, text); }
        else { mc.getConnection().sendChat(text); mc.gui.hud.getChat().addRecentChat(text); }
        if (sent) {
            input.setValue(""); drafts.put(draftKey(), "");
            long wait = whisper ? tools.whisperWait() : 0;
            notice(wait > 0 ? "Queued for the server's whisper delay: sends in " + ChatTeleportState.remaining(wait) + "."
                : "Sent. Waiting for the server's response.");
            conversationScroll = 0;
        } else notice("Could not send. Check the message length or message-command setting.");
        focus(input);
    }

    private void command(String value) { tools.send(value); notice("Sent " + value); focus(input); }
    private void reply() {
        if (tools.replyPlayer() != null) openConversation(tools.replyPlayer());
        else fill(tools.commandAvailable("r") ? "/r " : "/reply ");
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        fit();
        graphics = g; mouseX = mx; mouseY = my;
        timers = tools.timers(); requests = tools.requests();
        hits.clear(); visibleSearch = null; content = false; conversationVisible = false;
        g.fill(0, 0, width, height, 0x60050709);
        round(g, x - 4, y - 1, w + 8, h + 8, RADIUS + 6, 0x1c000000);
        round(g, x - 1, y + 1, w + 2, h + 3, RADIUS + 3, 0x48000000);
        panel(g, x, y, w, h, RADIUS + 2, BORDER, BACKGROUND);
        header();
        content = true;
        cursor = bodyTop + PAD - scroll;
        g.enableScissor(cx, bodyTop, cx + cw, bodyBottom);
        switch (page) {
            case Home -> overview(delta);
            case Players -> players(delta);
            case Clan -> clan(delta);
            case AutoTpy -> autoTpy(delta);
            case Homes -> homes();
            case Hotspot -> hotspot();
            case Settings -> settings();
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
                round(g, x + w - 7, bodyTop + 4 + (track - thumb) * scroll / maxScroll, 3, thumb, 1, BORDER);
            }
        }
        footer(delta);
        toast();
    }

    // Window chrome: tabs and close in the header, the composer in the footer.

    private void header() {
        var bar = layout.header();
        round(graphics, x + 1, y + 1, w - 2, bar.height() - 1, RADIUS + 1, CHROME, true, false);
        graphics.fill(x + 1, bar.bottom() - 1, x + w - 1, bar.bottom(), BORDER);
        int by = y + (bar.height() - CONTROL) / 2;
        int closeX = x + w - 6 - CONTROL;
        iconButton(CLOSE, "Close (Esc)", closeX, by, SECONDARY, this::onClose);

        float scale = bodyScale();
        int unread = tools.messages().unread();
        int left = x + 6, right = closeX - GAP;
        int[] labels = new int[TABS.length], extras = new int[TABS.length];
        int natural = 0;
        for (int i = 0; i < TABS.length; i++) {
            labels[i] = width(TAB_LABELS[i], scale);
            extras[i] = TABS[i] == Page.Players && unread > 0 ? badgeWidth(unread) + 5 : 0;
            natural += labels[i] + extras[i];
        }
        int padding = Math.clamp((right - left - natural - 2 * (TABS.length - 1)) / (TABS.length * 2), 2, 10);
        int tx = left;
        for (int i = 0; i < TABS.length; i++) {
            Page tab = TABS[i];
            int tw = Math.min(labels[i] + extras[i] + padding * 2, right - tx);
            if (tw <= padding * 2) break;
            boolean selected = page == tab || tab == Page.Players && page == Page.Conversation;
            boolean hover = over(tx, by, tw, CONTROL);
            if (selected || hover) round(graphics, tx, by, tw, CONTROL, 4, selected ? accentSurface() : RAISED);
            type(graphics, TAB_LABELS[i], tx + padding, by + (CONTROL - capHeight(scale)) / 2, tw - padding * 2 - extras[i],
                selected ? accentText() : hover ? TEXT : SECONDARY, scale);
            if (extras[i] > 0) badge(String.valueOf(unread), tx + padding + labels[i] + 5, by + CONTROL / 2);
            register(tx, by, tw, CONTROL, null, true, () -> navigate(tab));
            tx += tw + 2;
        }
    }

    private void footer(float delta) {
        var bar = layout.footer();
        round(graphics, x + 1, bar.y(), w - 2, bar.height() - 1, RADIUS + 1, CHROME, false, true);
        graphics.fill(x + 1, bar.y(), x + w - 1, bar.y() + 1, BORDER);
        var box = layout.composer();
        int send = buttonWidth("Send");
        panel(graphics, box.x(), box.y(), box.width() - send - GAP, box.height(), 4,
            input.isFocused() ? mix(BORDER, accent(), 0.7) : BORDER, SURFACE);
        input.extractRenderState(graphics, mouseX, mouseY, delta);
        button("Send", page == Page.Conversation ? "Send to " + recipient + " (Enter)" : "Send (Enter)",
            box.right() - send, box.y(), send, Tone.Primary, !input.getValue().isBlank(), this::sendInput);
        for (int i = 0; i < 3; i++) graphics.fill(x + w - 4 - i * 3, y + h - 4, x + w - 3 - i * 3, y + h - 3, MUTED);
        graphics.fill(x + w - 4, y + h - 7, x + w - 3, y + h - 6, MUTED);
        graphics.fill(x + w - 4, y + h - 10, x + w - 3, y + h - 9, MUTED);
        graphics.fill(x + w - 7, y + h - 7, x + w - 6, y + h - 6, MUTED);
    }

    /** Short-lived confirmation above the composer, so the body never shifts when an action reports back. */
    private void toast() {
        if (notice.isEmpty()) return;
        if (ChatTools.now() - noticeAt > NOTICE_MILLIS) { notice = ""; return; }
        float scale = smallScale();
        int tw = Math.min(cw, width(notice, scale) + 20);
        int th = capHeight(scale) + 10;
        int tx = cx + (cw - tw) / 2, ty = layout.footer().y() - th - 6;
        panel(graphics, tx, ty, tw, th, th / 2, mix(BORDER, TEXT, 0.12), RAISED);
        small(graphics, notice, tx + 10, ty + 5, tw - 20, TEXT);
    }

    // Pages.

    private void overview(float delta) {
        var actions = new Actions(cx + cw, cursor).add("Reply",
            tools.replyPlayer() != null ? "Reply to " + tools.replyPlayer() : "Reply to your last private message", Tone.Secondary, true, this::reply);
        if (tools.commandAvailable("hotspot")) actions.add("Go to hotspot", "/hotspot", Tone.Secondary, true, () -> command("/hotspot"));
        search(homeSearch, delta, cx, cursor, actions.left() - GAP - cx);
        cursor += CONTROL + PAD;
        timerList();
        menuRow("Auto TPY", tools.autoTpyEnabled() ? "Enabled · " + tools.autoTpyStats().live() + " buffered" : "Off",
            "Buffered automatic acceptance, timing, whitelist and blocklist", true,true,true,() -> navigate(Page.AutoTpy));
        cursor += PAD;

        caption("Favourite homes", null, "Manage", "Open Homes", () -> navigate(Page.Homes));
        var favorites = tools.favoriteHomes();
        if (favorites.isEmpty()) emptyRow("No favourite homes yet", "Star a home in Homes to pin it here.");
        else {
            var tiles = ChatToolsLayout.favoriteTiles(cx, cursor, cw, favorites.size());
            for (int i = 0; i < tiles.size(); i++) homeTile(favorites.get(i), tiles.get(i));
            cursor = tiles.getLast().bottom() + PAD;
        }

        boolean searching = !homeSearch.getValue().isBlank();
        caption(searching ? "Matching players" : "Recent players", null, "View all", "Open Players", () -> navigate(Page.Players));
        List<String> recent = new ArrayList<>(tools.recentPlayers());
        for (var request : requests) if (recent.stream().noneMatch(n -> n.equalsIgnoreCase(request.player()))) recent.add(request.player());
        if (searching) recent = matchingPlayers(homeSearch.getValue());
        if (recent.isEmpty()) emptyRow(searching ? "No matching players" : "No recent players yet",
            searching ? "Try another name, or press Enter to look up a full username." : "People you message or teleport to appear here.");
        else playerList(recent);
    }

    private void players(float delta) {
        search(playerSearch, delta, cx, cursor, cw);
        cursor += CONTROL + PAD;
        String query = playerSearch.getValue().strip().toLowerCase(Locale.ROOT);
        var recent = ChatPlayerSearch.matching(query, tools.recentPlayers(), requests.stream().map(ChatTpaRequests.Request::player).toList());
        var friends = ChatPlayerSearch.matching(query, tools.friends());
        caption("Recent", String.valueOf(recent.size()), null, null, null);
        if (recent.isEmpty()) emptyRow(query.isEmpty() ? "Nothing here yet" : "No matching recent players", "Search for a player to start a conversation.");
        else playerList(recent);
        caption("Meteor friends", String.valueOf(friends.size()), null, null, null);
        if (friends.isEmpty()) emptyRow(query.isEmpty() ? "No friends yet" : "No matching friends", "Add a friend from their conversation or player actions.");
        else playerList(friends);
        if (!query.isEmpty()) {
            var online = ChatPlayerSearch.matching(query, tools.onlinePlayers()).stream()
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
            menuRow(tools.isFriend(player) ? "Remove Meteor friend" : "Add Meteor friend", "Meteor friends", "Update Meteor's shared Friends list",
                true, false, false, () -> tools.toggleFriend(player));
            menuRow(tools.inClan(player) ? "Remove from clan" : "Add to clan", "Clan", "Update the members listed in the Clan tab",
                false, options.isEmpty(), false, () -> tools.toggleClan(player));
            for (int i = 0; i < options.size(); i++) {
                var option = options.get(i);
                menuRow(option.label(), option.command().strip(), option.command(), false, i == options.size() - 1, false, () -> {
                    if (tools.runsImmediately(option)) command(option.command()); else fill(option.command());
                });
            }
            cursor += PAD;
        }
    }

    /** One field both narrows the list and adds the names typed into it; friends come first, then online members. */
    private void clan(float delta) {
        String typed = clanField.getValue().strip();
        var entered = ChatClan.typed(typed).names().stream().filter(name -> !tools.inClan(name)).toList();
        var actions = new Actions(cx + cw, cursor)
            .add("Import CSV", files.choosing() ? "Waiting for the file picker" : "Add members from a .csv file, or drop one on this window",
                Tone.Secondary, !files.choosing(), this::importCsv)
            .add("Add", entered.isEmpty() ? "Type usernames to add, separated by commas"
                    : "Add " + (entered.size() == 1 ? entered.getFirst() : entered.size() + " players") + " to the clan",
                Tone.Primary, !entered.isEmpty(), this::addTyped);
        search(clanField, delta, cx, cursor, actions.left() - GAP - cx);
        cursor += CONTROL + PAD;
        var groups = tools.clanGroups();
        if (groups.isEmpty()) {
            emptyRow("No clan members yet", "Type names above, import a CSV, or drop a .csv file on this window.");
            return;
        }
        String query = ChatMessages.validName(typed) ? typed : "";
        var friends = ChatPlayerSearch.matching(query, groups.friends());
        var online = ChatPlayerSearch.matching(query, groups.online());
        var offline = ChatPlayerSearch.matching(query, groups.offline());
        if (friends.isEmpty() && online.isEmpty() && offline.isEmpty()) {
            emptyRow("No matching clan members", entered.isEmpty() ? "Clear the search to see every member." : "Press Enter to add " + typed + " to the clan.");
            return;
        }
        clanGroup("Friends", friends);
        clanGroup("Online", online);
        clanGroup("Offline", offline);
    }

    private void clanGroup(String label, List<String> names) {
        if (names.isEmpty()) return;
        caption(label, String.valueOf(names.size()), null, null, null);
        playerList(names, true);
    }

    private void addTyped() {
        var typed = ChatClan.typed(clanField.getValue());
        int added = tools.addToClan(typed.names());
        notice(ChatClan.summary(added, typed.names().size() - added, typed.invalid()));
        if (added > 0) clanField.setValue("");
        focus(clanField);
    }

    /** Opens the native picker on its own thread, then imports the chosen clan list. */
    private void importCsv() {
        files.choose(Minecraft.getInstance(), this::importFiles, this::notice);
    }

    private void importFiles(List<Path> paths) {
        if (page != Page.Clan) navigate(Page.Clan);
        var imported = ChatToolsFiles.read(paths, this::notice);
        if (imported == null) return;
        int added = tools.addToClan(imported.names());
        notice(ChatClan.summary(added, imported.names().size() - added, imported.invalid()));
    }

    private void autoTpy(float delta) {
        caption("Auto TPY", tools.autoTpyIntervalMs() + " ms between commands", null,null,null);
        toggleRow("Automatic acceptance", "Capture eligible requests and send from the buffer",tools.autoTpyEnabled(),
            () -> tools.autoTpyEnabled(!tools.autoTpyEnabled()),true,false);
        toggleRow("Adaptive timing", "Learn from confirmed replies; rate limits pause sending",tools.autoTpyAdaptive(),tools::toggleAutoTpyAdaptive,false,false);
        toggleRow("Whitelist only", "Off allows other players unless blocklisted",tools.autoTpyWhitelistOnly(),tools::toggleAutoTpyWhitelistOnly,false,false);
        toggleRow("Allow Meteor friends", "Friends may bypass the whitelist; the blocklist always wins",tools.autoTpyAllowsFriends(),tools::toggleAutoTpyFriends,false,true);
        cursor += PAD;
        caption("Buffer",null,"Clear","Cancel all buffered generations",tools::clearAutoTpy);
        var stats=tools.autoTpyStats();
        emptyRow(stats.queued()+" queued · "+stats.awaiting()+" awaiting · "+stats.retries()+" retries",
            stats.overflow()+" overflow · "+stats.latest());
        menuRow("Reset learned speed", "Timing and limits are in Chat Tools module settings", "Restore the configured starting interval",
            true,true,false,tools::resetAutoTpyLearning);
        var entries=tools.autoTpyEntries();
        int shown=0;
        for(var entry:entries) {
            if(entry.state()!=AutoTpyQueue.State.Queued && entry.state()!=AutoTpyQueue.State.AwaitingReply && entry.state()!=AutoTpyQueue.State.RetryWaiting) continue;
            if(shown++>=64) break;
            menuRow(entry.player()+(entry.friend()?" · Friend":""),entry.state()+" · attempt "+entry.attempts(),
                entry.reason()+". Click to cancel this request.",shown==1,false,false,() -> tools.cancelAutoTpy(entry.player()));
        }
        if(stats.live()>64) emptyRow("Showing the first 64 live requests",stats.live()+" requests remain tracked in the buffer.");
        cursor += PAD;
        caption(autoBlocklistEditing?"Blocklist":"Whitelist",null,autoBlocklistEditing?"Whitelist":"Blocklist","Switch the list being edited",() -> {
            autoBlocklistEditing=!autoBlocklistEditing;autoImportRevision++;autoListField.setValue("");scroll=0;
        });
        String typed=autoListField.getValue().strip();
        var selected=autoBlocklistEditing?tools.autoTpyBlocklist():tools.autoTpyWhitelist();
        var actions=new Actions(cx+cw,cursor).add("Add","Add typed usernames",Tone.Primary,
            !ChatClan.typed(typed).names().isEmpty(),this::addAutoTpyNames);
        if(!autoBlocklistEditing) actions.add("Import CSV",autoImportBusy?"Import in progress":"Append names from CSV; dropping files also works",
            Tone.Secondary,!autoImportBusy,() -> importAutoTpy(null));
        search(autoListField,delta,cx,cursor,Math.max(24,actions.left()-GAP-cx));cursor+=CONTROL+PAD;
        var matches=selected.stream().filter(n -> n.toLowerCase(Locale.ROOT).contains(typed.toLowerCase(Locale.ROOT))).limit(100).toList();
        if(matches.isEmpty()) emptyRow(selected.isEmpty()?"This list is empty":"No matching names","Type usernames to add. Click an existing name to remove it.");
        for(int i=0;i<matches.size();i++) {
            String name=matches.get(i);
            menuRow(name,"Remove", "Remove "+name+" from this list",i==0,i==matches.size()-1,false,() -> {
                if(autoBlocklistEditing) tools.setAutoTpyBlocklist(ChatClan.remove(tools.autoTpyBlocklist(),name));
                else tools.setAutoTpyWhitelist(ChatClan.remove(tools.autoTpyWhitelist(),name));
                autoImportRevision++;
            });
        }
        if(selected.size()>100) emptyRow(selected.size()+" names saved","Search to find names beyond the first 100 matches.");
    }

    private void addAutoTpyNames() {
        var typed=ChatClan.typed(autoListField.getValue());
        var old=autoBlocklistEditing?tools.autoTpyBlocklist():tools.autoTpyWhitelist();
        var updated=ChatClan.add(old,typed.names());
        if(autoBlocklistEditing) tools.setAutoTpyBlocklist(updated);else tools.setAutoTpyWhitelist(updated);
        autoImportRevision++;autoListField.setValue("");focus(autoListField);
        notice("Added "+(updated.size()-old.size())+" names; skipped "+typed.invalid()+" invalid names.");
    }

    /** The ticket prevents a late picker/worker callback from overwriting list edits or another server's UI. */
    private void importAutoTpy(List<Path> paths) {
        if(autoImportBusy||autoBlocklistEditing) return;
        autoImportBusy=true;
        var client=Minecraft.getInstance();
        var ticket=new AutoTpyWhitelistImport.Ticket(++autoImportRevision,tools.autoTpySessionKey(),tools.autoTpyWhitelist());
        Set<String> blocked=Set.copyOf(tools.autoTpyBlocklist());
        Thread worker=new Thread(() -> {
            AutoTpyWhitelistImport.Result result=null;
            String error=null;
            try {
                var chosen=paths==null?AutoTpyFiles.choose():List.copyOf(paths);
                if(!chosen.isEmpty()) result=AutoTpyWhitelistImport.parse(ticket.original(),blocked,AutoTpyFiles.read(chosen));
            } catch(IOException|RuntimeException|LinkageError e) {
                error=e.getMessage()==null?"Could not import the whitelist file.":e.getMessage();
            }
            var imported=result;String problem=error;
            client.execute(() -> {
                autoImportBusy=false;
                if(client.gui.screen()!=this||page!=Page.AutoTpy||autoBlocklistEditing) return;
                if(!ticket.canApply(autoImportRevision,tools.autoTpySessionKey(),tools.autoTpyWhitelist())) {
                    notice("Import discarded because the list, page or session changed.");return;
                }
                if(problem!=null) {notice(problem);return;}
                if(imported==null) return;
                tools.setAutoTpyWhitelist(imported.merged());
                long overlaps=imported.merged().stream().filter(n->ChatClan.contains(tools.autoTpyBlocklist(),n)).count();
                notice("Whitelist: added "+imported.added()+", "+imported.duplicates()+" duplicates, "+imported.invalid()+" invalid; "+overlaps+" saved names remain blocked.");
            });
        },"Comet Auto TPY whitelist import");
        worker.setDaemon(true);worker.start();
    }

    private void homes() {
        var actions = new Actions(cx + cw, cursor);
        actions.add("Server list", "/homes", Tone.Secondary, true, () -> command("/homes"))
            .add("Refresh", "Refresh home suggestions", Tone.Secondary, !tools.loadingHomes(), tools::refreshHomes);
        button("New home", "Set a home at your current position", cx, cursor, buttonWidth("New home"), Tone.Primary, true, () -> fill("/sethome "));
        cursor += CONTROL + PAD;
        timerList();
        var homes = tools.homes();
        caption("Your homes", homes.isEmpty() ? null : String.valueOf(homes.size()), null, null, null);
        if (homes.isEmpty()) emptyRow(tools.loadingHomes() ? "Finding your homes..." : "No homes found", "Create a home, or ask the server for its list.");
        for (int i = 0; i < homes.size(); i++) homeRow(homes.get(i), i == 0, i == homes.size() - 1);
        if (!homes.isEmpty()) cursor += PAD;
    }

    private void hotspot() {
        if (settingsPath.isEmpty()) {
            new Actions(cx + cw, cursor).add("Kill hotspot", "/hotspot kill", Tone.Danger, true, () -> command("/hotspot kill"));
            button("Teleport", "/hotspot", cx, cursor, buttonWidth("Teleport"), Tone.Primary, true, () -> command("/hotspot"));
            int refresh = buttonWidth("Refresh");
            button("Refresh", "/hotspot info", cx + buttonWidth("Teleport") + GAP, cursor, refresh, Tone.Secondary, true, tools::refreshHotspot);
            cursor += CONTROL + PAD;
            caption("Details", null, null, null, null);
            var details = tools.hotspotDetails();
            if (details.isEmpty()) emptyRow("Waiting for details", "Refresh to ask the server for the latest hotspot information.");
            else {
                float scale = bodyScale();
                List<FormattedCharSequence> lines = new ArrayList<>();
                for (String line : details) lines.addAll(wrap(line, cw - 24, scale));
                int lineHeight = lineHeight(scale) + 2;
                int height = (lines.size() - 1) * lineHeight + capHeight(scale) + 20;
                if (visible(cursor, height)) {
                    panel(graphics, cx, cursor, cw, height, RADIUS, BORDER, SURFACE);
                    for (int i = 0; i < lines.size(); i++) line(lines.get(i), cx + 12, cursor + 10 + i * lineHeight, SECONDARY, scale);
                }
                cursor += height + PAD;
            }
            caption("Manage", null, null, null, null);
        }
        var group = ChatServerSettings.groups().stream().filter(e -> e.label().equals("Hotspot")).findFirst();
        entries(group.map(e -> e.children().stream().filter(child -> !"/hotspot info".equals(child.command())).toList()).orElse(List.of()));
    }

    private void settings() {
        if (settingsPath.isEmpty()) {
            caption("Preferences", null, null, null, null);
            toggleRow("DM notifications", "Pop up a notice when a message arrives", tools.messageNotifications(), tools::toggleMessageNotifications, true, false);
            toggleRow("Hide DMs from chat", "Recognised private messages only appear here", tools.hidingMessages(), tools::toggleHidingMessages, false, true);
            cursor += PAD;
            caption("Name colours", null, "Clan members", "Open Clan", () -> navigate(Page.Clan));
            toggleRow("Friend names in chat", "Colour Meteor friends' names in chat, over the clan colour", tools.friendHighlighting(),
                tools::toggleFriendHighlighting, tools.friendColor(), () -> tools.pickFriendColor(false), true, false);
            toggleRow("Friend names in tab list", "Colour Meteor friends' names in the player list", tools.friendTabHighlighting(),
                tools::toggleFriendTabHighlighting, tools.friendTabColor(), () -> tools.pickFriendColor(true), false, false);
            toggleRow("Friend rows in tab list", "Highlight Meteor friends' rows in the player list", tools.friendRowHighlighting(),
                tools::toggleFriendRowHighlighting, tools.friendRowColor(), tools::pickFriendRowColor, false, false);
            toggleRow("Clan names in chat", "Colour clan members' names in chat", tools.clanChatHighlighting(),
                tools::toggleClanChatHighlighting, tools.clanColor(), () -> tools.pickClanColor(false), false, false);
            toggleRow("Clan names in tab list", "Colour clan members' names in the player list", tools.clanTabHighlighting(), tools::toggleClanTabHighlighting,
                tools.clanTabColor(), () -> tools.pickClanColor(true), false, false);
            toggleRow("Clan rows in tab list", "Highlight clan members' rows in the player list", tools.clanRowHighlighting(),
                tools::toggleClanRowHighlighting, tools.clanRowColor(), tools::pickClanRowColor, false, true);
            cursor += PAD;
            caption("Teleport cooldowns", "This account on this server", null, null, null);
            cooldownRow("Home cooldown", ChatTeleportState.Kind.Home, true, false);
            cooldownRow("TP cooldown", ChatTeleportState.Kind.TPA, false, true);
            cursor += PAD;
            caption("Server settings", null, null, null, null);
        }
        entries(ChatServerSettings.groups());
    }

    private void entries(List<ChatServerSettings.Entry> roots) {
        var available = settingsPath.isEmpty() ? ChatServerSettings.available(roots,
            root -> root.equals("tpacancel") ? tools.commandAvailable(root) || tools.commandAvailable("tpcancel") : tools.commandAvailable(root))
            : settingsPath.getLast().children();
        if (!settingsPath.isEmpty()) {
            iconButton(BACK, "Back (Esc)", cx, cursor, SECONDARY, this::back);
            List<String> trail = new ArrayList<>();
            for (var entry : settingsPath) trail.add(entry.label());
            text(graphics, String.join(" / ", trail), cx + CONTROL + GAP, cursor + (CONTROL - capHeight(bodyScale())) / 2, cw - CONTROL - GAP, TEXT);
            cursor += CONTROL + PAD;
        }
        var shown = available.stream().filter(entry -> !("/homecancel".equals(entry.command()) && !tools.canCancelHome())
            && !("/tpacancel".equals(entry.command()) && !tools.canCancelTpa())).toList();
        if (shown.isEmpty()) emptyRow("No controls available", "This server hasn't offered settings for this category.");
        for (int i = 0; i < shown.size(); i++) {
            var entry = shown.get(i);
            menuRow(entry.label(), entry.menu() ? null : entry.command().strip(), entry.menu() ? null : entry.command(), i == 0, i == shown.size() - 1,
                entry.menu(), () -> {
                    if (entry.menu()) { settingsPath.add(entry); scroll = 0; hits.clear(); }
                    else if (entry.needsInput()) fill(entry.command());
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
        boolean friend = tools.isFriend(recipient), online = tools.onlineContact(recipient);
        int tint = nameColor(recipient, accent());
        iconButton(BACK, "Back (Esc)", cx, top + 2, SECONDARY, () -> navigate(conversationBack));
        avatar(recipient, cx + CONTROL + GAP, top, 24, tint, online, BACKGROUND);
        var actions = new Actions(cx + cw, top + 2)
            .add(friend ? "Unfriend" : "Add friend", "Update Meteor friendship", Tone.Secondary, true, () -> tools.toggleFriend(recipient))
            .add("TP", "/tpa " + recipient, Tone.Secondary, true, () -> { tools.requestTeleport(recipient); notice("Teleport request sent to " + recipient); });
        int tx = cx + CONTROL + GAP + 32;
        text(graphics, recipient, tx, top + 3, actions.left() - GAP - tx, nameColor(recipient, TEXT));
        String countdown = ChatCountdowns.player(recipient, timers, List.of(), ChatTools.now());
        small(graphics, countdown.isEmpty() ? status(recipient, online, false) : countdown,
            tx, top + 16, actions.left() - GAP - tx, countdown.isEmpty() ? MUTED : AMBER);
        cursor += 24 + PAD;
        graphics.fill(cx, cursor - PAD / 2, cx + cw, cursor - PAD / 2 + 1, DIVIDER);
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
            text(graphics, heading, cx + (cw - width(heading, scale)) / 2, my, cw, SECONDARY);
            String hint = "Type below to send a direct message.";
            small(graphics, hint, cx + (cw - width(hint, smallScale())) / 2, my + 14, cw, MUTED);
        }
        // Latest bubble sits on the bottom edge; scrolling up reveals older messages.
        int by = transcriptBottom - total + conversationScroll;
        int outgoing = mix(BACKGROUND, accent(), 0.3), queued = mix(BACKGROUND, accent(), 0.12);
        long wait = tools.whisperWait();
        String pendingTip = wait > 0 ? "Queued for the server's whisper delay" + DOT + "sends in " + ChatTeleportState.remaining(wait)
            : "Waiting for the server to confirm";
        for (int i = 0; i < bubbles.size(); i++) {
            Bubble bubble = bubbles.get(i);
            if (i > 0) by += bubbles.get(i - 1).incoming() == bubble.incoming() ? 3 : 9;
            int bx = bubble.incoming() ? cx : cx + cw - bubble.width();
            if (by + bubble.height() > transcriptTop && by < bodyBottom) {
                round(graphics, bx, by, bubble.width(), bubble.height(), RADIUS, bubble.incoming() ? RAISED : bubble.pending() ? queued : outgoing);
                int ly = by + 7;
                for (var text : bubble.lines()) { line(text, bx + 9, ly, bubble.pending() ? SECONDARY : TEXT, scale); ly += lineHeight(scale); }
                if (bubble.pending() && by >= transcriptTop) register(bx, by, bubble.width(), bubble.height(), pendingTip, false, null);
            }
            by += bubble.height();
        }
        graphics.disableScissor();
        if (reading(recipient)) tools.messages().read(recipient);
    }

    private Bubble bubble(String text, boolean incoming, boolean pending, float scale) {
        var lines = wrap(text, Math.max(60, cw * 3 / 4) - 18, scale);
        int textWidth = (int) Math.ceil(lines.stream().mapToInt(Minecraft.getInstance().font::width).max().orElse(10) * scale);
        return new Bubble(incoming, pending, lines, Math.min(cw, Math.max(28, textWidth + 18)),
            (lines.size() - 1) * lineHeight(scale) + capHeight(scale) + 13);
    }

    private static int transcriptHeight(List<Bubble> bubbles) {
        int total = 0;
        for (int i = 0; i < bubbles.size(); i++) {
            total += bubbles.get(i).height();
            if (i > 0) total += bubbles.get(i - 1).incoming() == bubbles.get(i).incoming() ? 3 : 9;
        }
        return total;
    }

    // Shared parts.

    private void search(EditBox field, float delta, int sx, int sy, int sw) {
        field.setX(sx + 22); field.setY(sy + (CONTROL - 8) / 2); field.setWidth(sw - 30);
        if (!visible(sy, CONTROL)) { field.setFocused(false); return; }
        panel(graphics, sx, sy, sw, CONTROL, 4, field.isFocused() ? mix(BORDER, accent(), 0.7) : BORDER, SURFACE);
        icon(graphics, SEARCH, sx + 7, sy + (CONTROL - iconHeight(SEARCH)) / 2, field.isFocused() ? TEXT : MUTED);
        visibleSearch = field;
        field.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    /** An uppercase section label, with an optional count or note on the right, or a link. */
    private void caption(String label, String detail, String link, String tooltip, Runnable action) {
        float scale = smallScale();
        int ty = cursor + 2;
        if (visible(cursor, 14)) {
            small(graphics, label.toUpperCase(Locale.ROOT), cx + 2, ty, cw * 2 / 3, MUTED);
            if (link != null) {
                int lw = width(link, scale), lx = cx + cw - lw - 2;
                boolean hover = over(lx - 4, cursor - 2, lw + 8, 14);
                small(graphics, link, lx, ty, lw + 1, hover ? TEXT : accentText());
                register(lx - 4, cursor - 2, lw + 8, 14, tooltip, true, action);
            } else if (detail != null) {
                int dw = Math.min(width(detail, scale), cw / 3);
                small(graphics, detail, cx + cw - dw - 2, ty, dw, MUTED);
            }
        }
        cursor += 16;
    }

    /** One row of a grouped list: consecutive rows join into a single card separated by dividers. */
    private void row(int ry, int height, boolean first, boolean last, boolean hover) {
        panel(graphics, cx, ry, cw, height, RADIUS, BORDER, hover ? mix(SURFACE, RAISED, 0.7) : SURFACE, first, last);
        if (!first) graphics.fill(cx + 1, ry, cx + cw - 1, ry + 1, DIVIDER);
    }

    private void emptyRow(String heading, String hint) {
        if (visible(cursor, ROW)) {
            row(cursor, ROW, true, true, false);
            text(graphics, heading, cx + 12, cursor + 8, cw - 24, SECONDARY);
            small(graphics, hint, cx + 12, cursor + 20, cw - 24, MUTED);
        }
        cursor += ROW + PAD;
    }

    private void playerList(List<String> names) { playerList(names, false); }
    /** Rows on the Clan page also get a button that removes the player from the clan. */
    private void playerList(List<String> names, boolean clanPage) {
        for (int i = 0; i < names.size(); i++) playerRow(names.get(i), i == 0, i == names.size() - 1, clanPage);
        cursor += PAD;
    }

    /** Meteor friends show in the friend colour and other clan members in the clan colour. */
    private int nameColor(String name, int otherwise) {
        return tools.isFriend(name) ? tools.friendColor() : tools.inClan(name) ? tools.clanColor() : otherwise;
    }
    private String status(String name, boolean online, boolean clanPage) {
        return (online ? "Online" : "Offline") + (tools.isFriend(name) ? DOT + "Friend" : "") + (!clanPage && tools.inClan(name) ? DOT + "Clan" : "");
    }

    private void playerRow(String name, boolean first, boolean last, boolean clanPage) {
        var incoming = requests.stream().filter(r -> r.player().equalsIgnoreCase(name)).findFirst().orElse(null);
        int ry = cursor, height = incoming == null ? ROW : ROW + 26;
        cursor += height;
        if (!visible(ry, height)) return;
        row(ry, height, first, last, false);
        boolean online = tools.onlineContact(name);
        avatar(name, cx + 7, ry + 6, 22, nameColor(name, accent()), online, SURFACE);
        var actions = new Actions(cx + cw - 7, ry + 7);
        if (clanPage) actions.icon(CLOSE, "Remove " + name + " from the clan", () -> {
            tools.removeFromClan(name);
            notice("Removed " + name + " from the clan.");
        });
        actions.icon(MORE, "More actions for " + name, () -> findPlayer(name))
            .add("TP", "/tpa " + name, Tone.Secondary, true, () -> { tools.requestTeleport(name); notice("Teleport request sent to " + name); })
            .add("Message", "Message " + name, Tone.Secondary, true, () -> openConversation(name));
        int tx = cx + 37, textWidth = actions.left() - GAP - tx;
        int unread = tools.messages().unread(name);
        int badge = unread > 0 ? badgeWidth(unread) + 5 : 0;
        float scale = bodyScale();
        text(graphics, name, tx, ry + 8, textWidth - badge, nameColor(name, TEXT));
        if (badge > 0) badge(String.valueOf(unread), tx + Math.min(width(name, scale), textWidth - badge) + 5, ry + 8 + capHeight(scale) / 2);
        String countdown = ChatCountdowns.player(name, timers, List.of(), ChatTools.now());
        small(graphics, countdown.isEmpty() ? status(name, online, clanPage) : countdown, tx, ry + 20, textWidth, countdown.isEmpty() ? MUTED : AMBER);
        if (incoming != null) {
            int sy = ry + ROW - 2;
            var answer = new Actions(cx + cw - 7, sy)
                .add("Decline", tools.denyCommand(incoming), Tone.Secondary, true, () -> tools.answer(incoming, false))
                .add("Accept", tools.acceptCommand(incoming), Tone.Primary, true, () -> tools.answer(incoming, true));
            small(graphics, (incoming.toThem() ? "Asks you to teleport to them" : "Wants to teleport to you") + DOT
                + ChatTeleportState.remaining(incoming.expires() - ChatTools.now()),
                tx, sy + (CONTROL - capHeight(smallScale())) / 2, answer.left() - GAP - tx, AMBER);
        }
    }

    private void requestStrip(ChatTpaRequests.Request request) {
        int ry = cursor, height = CONTROL + 10;
        panel(graphics, cx, ry, cw, height, RADIUS, mix(BORDER, AMBER, 0.3), mix(SURFACE, AMBER, 0.06));
        var answer = new Actions(cx + cw - 5, ry + 5)
            .add("Decline", tools.denyCommand(request), Tone.Secondary, true, () -> tools.answer(request, false))
            .add("Accept", tools.acceptCommand(request), Tone.Primary, true, () -> tools.answer(request, true));
        text(graphics, (request.toThem() ? "Asks you to teleport to them" : "Wants to teleport to you") + DOT
            + ChatTeleportState.remaining(request.expires() - ChatTools.now()),
            cx + 10, ry + (height - capHeight(bodyScale())) / 2, answer.left() - GAP - cx - 10, AMBER);
        cursor += height + PAD;
    }

    private void homeTile(String name, ChatToolsLayout.Rect tile) {
        if (!visible(tile.y(), tile.height())) return;
        boolean hover = over(tile.x(), tile.y(), tile.width(), tile.height());
        panel(graphics, tile.x(), tile.y(), tile.width(), tile.height(), RADIUS, hover ? mix(BORDER, accent(), 0.6) : BORDER, hover ? RAISED : SURFACE);
        round(graphics, tile.x() + 8, tile.y() + 9, 22, 22, 5, accentSurface());
        icon(graphics, HOUSE, tile.x() + 8, tile.y() + 9, 22, 22, accentText());
        text(graphics, name, tile.x() + 38, tile.y() + 10, tile.width() - 46, TEXT);
        String countdown = ChatCountdowns.home(name, timers, ChatTools.now());
        small(graphics, countdown.isEmpty() ? "Teleport home" : countdown, tile.x() + 38, tile.y() + 23, tile.width() - 46,
            countdown.isEmpty() ? MUTED : AMBER);
        register(tile.x(), tile.y(), tile.width(), tile.height(), "/home " + name, true, () -> command("/home " + name));
    }

    private void homeRow(String name, boolean first, boolean last) {
        boolean compact = cw < 400;
        int ry = cursor, height = compact ? ROW + 24 : ROW;
        cursor += height;
        if (!visible(ry, height)) return;
        row(ry, height, first, last, false);
        boolean favorite = tools.favoriteHome(name);
        round(graphics, cx + 7, ry + 6, 22, 22, 5, favorite ? accentSurface() : RAISED);
        icon(graphics, HOUSE, cx + 7, ry + 6, 22, 22, favorite ? accentText() : SECONDARY);
        var actions = new Actions(cx + cw - 7, ry + 7);
        if (!compact) manageHome(actions, name);
        actions.add("Go", "/home " + name, Tone.Primary, true, () -> command("/home " + name))
            .icon(favorite ? STAR : STAR_OUTLINE, favorite ? "Remove from favourites" : "Add to favourites",
                favorite ? AMBER : MUTED, () -> tools.toggleFavoriteHome(name));
        int tx = cx + 37, textWidth = actions.left() - GAP - tx;
        text(graphics, name, tx, ry + 8, textWidth, TEXT);
        String countdown = ChatCountdowns.home(name, timers, ChatTools.now());
        small(graphics, countdown.isEmpty() ? favorite ? "Favourite" : "Saved home" : countdown, tx, ry + 20, textWidth,
            countdown.isEmpty() ? MUTED : AMBER);
        if (compact) manageHome(new Actions(cx + cw - 7, ry + ROW - 2), name);
    }

    private void manageHome(Actions actions, String name) {
        actions.add("Delete", "/delhome " + name, Tone.Danger, true, () -> command("/delhome " + name))
            .add("Rename", "Enter a new name for " + name, Tone.Secondary, true, () -> fill("/homerename " + name + " "));
    }

    /** Active warmups, requests and cooldowns, each with its own Cancel while the server still allows one. */
    private void timerList() {
        long now = ChatTools.now();
        for (int i = 0; i < timers.size(); i++) {
            var timer = timers.get(i);
            int ry = cursor, height = CONTROL + 10;
            cursor += height;
            if (!visible(ry, height)) continue;
            panel(graphics, cx, ry, cw, height, RADIUS, mix(BORDER, AMBER, 0.3), mix(SURFACE, AMBER, 0.06), i == 0, i == timers.size() - 1);
            if (i > 0) graphics.fill(cx + 1, ry, cx + cw - 1, ry + 1, mix(DIVIDER, AMBER, 0.15));
            round(graphics, cx + 10, ry + height / 2 - 3, 6, 6, 3, AMBER);
            var actions = new Actions(cx + cw - 5, ry + 5);
            boolean home = timer.kind() == ChatTeleportState.Kind.Home;
            if (home ? tools.canCancelHome() : tools.canCancelTpa())
                actions.add("Cancel", home ? "/homecancel" : tools.cancelTpaCommand(), Tone.Secondary, true, () -> {
                    if (home) tools.cancelHome(); else tools.cancelTpa();
                    notice(home ? "Home cancellation sent." : "Teleport cancellation sent.");
                });
            String target = timer.target().isEmpty() ? "" : " " + timer.target();
            text(graphics, (home ? "Home" : "Teleport to") + target + DOT + timer.status(now), cx + 24, ry + (height - capHeight(bodyScale())) / 2,
                actions.left() - GAP - cx - 24, AMBER);
        }
        if (!timers.isEmpty()) cursor += PAD;
    }

    private void menuRow(String label, String detail, String tooltip, boolean first, boolean last, boolean submenu, Runnable action) {
        int ry = cursor, height = 28;
        cursor += height;
        if (!visible(ry, height)) return;
        boolean hover = over(cx, ry, cw, height);
        row(ry, height, first, last, hover);
        float scale = bodyScale();
        int labelWidth = width(label, scale);
        text(graphics, label, cx + 12, ry + (height - capHeight(scale)) / 2, cw - 36, TEXT);
        if (submenu) icon(graphics, NEXT, cx + cw - 16, ry + (height - iconHeight(NEXT)) / 2, hover ? TEXT : MUTED);
        else if (detail != null) {
            int space = cw - labelWidth - 44;
            if (space > 40) {
                int dw = Math.min(width(detail, smallScale()), space);
                small(graphics, detail, cx + cw - 12 - dw, ry + (height - capHeight(smallScale())) / 2, dw, MUTED);
            }
        }
        register(cx, ry, cw, height, tooltip, true, action);
    }

    private void toggleRow(String label, String description, boolean checked, Runnable action, boolean first, boolean last) {
        toggleRow(label, description, checked, action, 0, null, first, last);
    }
    /** A switch row; with {@code pick}, a swatch of {@code color} beside the switch opens a colour picker. */
    private void toggleRow(String label, String description, boolean checked, Runnable action, int color, Runnable pick, boolean first, boolean last) {
        int ry = cursor;
        cursor += ROW;
        if (!visible(ry, ROW)) return;
        boolean hover = over(cx, ry, cw, ROW);
        row(ry, ROW, first, last, hover);
        int sx = cx + cw - 38, sy = ry + (ROW - 14) / 2, textWidth = cw - 64;
        if (pick != null) {
            int px = sx - GAP - CONTROL, py = ry + (ROW - CONTROL) / 2;
            panel(graphics, px, py, CONTROL, CONTROL, 4, over(px, py, CONTROL, CONTROL) ? mix(BORDER, TEXT, 0.4) : BORDER, SURFACE);
            round(graphics, px + 3, py + 3, CONTROL - 6, CONTROL - 6, 2, checked ? color : mix(SURFACE, color, 0.35));
            // Registered before the row, so a click on the swatch picks a colour instead of flipping the switch.
            register(px, py, CONTROL, CONTROL, "Change colour", true, pick);
            textWidth -= CONTROL + GAP;
        }
        text(graphics, label, cx + 12, ry + 8, textWidth, TEXT);
        small(graphics, description, cx + 12, ry + 20, textWidth, MUTED);
        round(graphics, sx, sy, 26, 14, 7, checked ? accent() : BORDER);
        round(graphics, sx + (checked ? 14 : 2), sy + 2, 10, 10, 5, checked ? onAccent() : SECONDARY);
        register(cx, ry, cw, ROW, null, true, action);
    }

    /**
     * The length assumed after a teleport. Auto follows what the server reports for this account (or the fallback until
     * it does); Manual pins the player's own length. Pressing a stepper switches to Manual from the current value.
     */
    private void cooldownRow(String label, ChatTeleportState.Kind kind, boolean first, boolean last) {
        var length = tools.cooldownLength(kind);
        boolean manual = length.manual() != null, compact = cw < 440;
        int ry = cursor, height = compact ? ROW + 24 : ROW;
        cursor += height;
        if (!visible(ry, height)) return;
        row(ry, height, first, last, false);
        long seconds = length.effective() / 1_000;
        var actions = new Actions(cx + cw - 7, ry + 7).add("+", "Longer", Tone.Secondary, seconds < 86_400,
            () -> tools.manualCooldown(kind, ChatSavedCooldowns.step(seconds, true) * 1_000));
        int valueX = actions.reserve(48);
        actions.add("-", "Shorter", Tone.Secondary, seconds > 0, () -> tools.manualCooldown(kind, ChatSavedCooldowns.step(seconds, false) * 1_000));
        (compact ? new Actions(cx + cw - 7, ry + ROW - 2) : actions).choice(new String[] {"Auto", "Manual"},
            new String[] {"Use the cooldown the server reports for this account", "Set your own cooldown for this account"},
            manual ? 1 : 0, choice -> tools.manualCooldown(kind, choice == 1 ? length.effective() : null));
        String value = seconds == 0 ? "Off" : ChatTeleportState.remaining(length.effective());
        float scale = bodyScale();
        text(graphics, value, valueX + (48 - width(value, scale)) / 2, ry + 7 + (CONTROL - capHeight(scale)) / 2, 48,
            seconds == 0 ? MUTED : manual ? TEXT : SECONDARY);
        String source = manual ? "Your setting" + (length.learned() == null ? "" : DOT + "learned " + ChatTeleportState.remaining(length.learned()))
            : length.learned() != null ? "Learned from the server"
            : length.fallback() > 0 ? "Fallback until the server reports one" : "Off until the server reports one";
        int textWidth = actions.left() - GAP - cx - 12;
        text(graphics, label, cx + 12, ry + 8, textWidth, TEXT);
        small(graphics, source, cx + 12, ry + 20, textWidth, MUTED);
    }

    /** Joined choices; the selected one uses the same highlight as the header tabs. */
    private void segmented(int bx, int by, String[] labels, String[] tooltips, int selected, IntConsumer action) {
        if (!visible(by, CONTROL)) return;
        float scale = bodyScale();
        panel(graphics, bx, by, segmentedWidth(labels), CONTROL, 4, BORDER, SURFACE);
        int sx = bx + 2;
        for (int i = 0; i < labels.length; i++) {
            int sw = width(labels[i], scale) + 14, index = i;
            boolean hover = i != selected && over(sx, by, sw, CONTROL);
            if (i == selected || hover) round(graphics, sx, by + 2, sw, CONTROL - 4, 3, i == selected ? accentSurface() : RAISED);
            type(graphics, labels[i], sx + 7, by + (CONTROL - capHeight(scale)) / 2, sw - 8, i == selected ? accentText() : hover ? TEXT : SECONDARY, scale);
            register(sx, by, sw, CONTROL, tooltips[i], i != selected, () -> action.accept(index));
            sx += sw;
        }
    }
    private static int segmentedWidth(String[] labels) {
        int total = 4;
        for (String label : labels) total += width(label, bodyScale()) + 14;
        return total;
    }

    private void avatar(String name, int ax, int ay, int size, int tint, boolean online, int ring) {
        round(graphics, ax, ay, size, size, 5, mix(SURFACE, tint, 0.25));
        String initials = name.substring(0, Math.min(2, name.length())).toUpperCase(Locale.ROOT);
        float scale = smallScale();
        type(graphics, initials, ax + (size - width(initials, scale)) / 2, ay + (size - capHeight(scale)) / 2, size, mix(TEXT, tint, 0.45), scale);
        round(graphics, ax + size - 6, ay + size - 6, 9, 9, 4, ring);
        round(graphics, ax + size - 5, ay + size - 5, 7, 7, 3, online ? GREEN : MUTED);
    }

    private int badgeWidth(int count) { return width(String.valueOf(count), smallScale()) + 7; }
    /** A small accent pill vertically centred on {@code centerY}. */
    private void badge(String text, int bx, int centerY) {
        float scale = smallScale();
        int bh = capHeight(scale) + 5, bw = width(text, scale) + 7;
        round(graphics, bx, centerY - bh / 2, bw, bh, bh / 2, accent());
        type(graphics, text, bx + 4, centerY - bh / 2 + 3, bw - 6, onAccent(), scale);
    }

    private List<FormattedCharSequence> wrap(String text, int width, float scale) {
        return Minecraft.getInstance().font.split(Component.literal(text), Math.max(1, (int) (width / scale)));
    }
    private static int lineHeight(float scale) { return Math.round(10 * scale); }
    private void line(FormattedCharSequence text, int lx, int ly, int color, float scale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(lx, ly);
        graphics.pose().scale(scale);
        graphics.text(Minecraft.getInstance().font, text, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    private boolean visible(int by, int height) { return !content || by + height > bodyTop && by < bodyBottom; }
    private boolean over(int bx, int by, int width, int height) {
        return mouseX >= bx && mouseX < bx + width && mouseY >= by && mouseY < by + height
            && (!content || mouseY >= bodyTop && mouseY < bodyBottom && mouseX >= cx && mouseX < cx + cw);
    }
    private void register(int bx, int by, int width, int height, String tooltip, boolean enabled, Runnable action) {
        if (!visible(by, height)) return;
        int left = content ? Math.max(cx, bx) : bx;
        int right = content ? Math.min(cx + cw, bx + width) : bx + width;
        int top = content ? Math.max(bodyTop, by) : by;
        int bottom = content ? Math.min(bodyBottom, by + height) : by + height;
        if (right <= left || bottom <= top) return;
        Hit hit = new Hit(left, top, right - left, bottom - top, action);
        if (tooltip != null && hit.contains(mouseX, mouseY)) graphics.setTooltipForNextFrame(Component.literal(tooltip), mouseX, mouseY);
        if (enabled) hits.add(hit);
    }

    private static int buttonWidth(String label) { return Math.max(CONTROL, width(label, bodyScale()) + 16); }
    private void button(String label, String tooltip, int bx, int by, int bw, Tone tone, boolean enabled, Runnable action) {
        if (!visible(by, CONTROL)) return;
        boolean hover = enabled && over(bx, by, bw, CONTROL);
        if (!enabled && tone != Tone.Ghost) panel(graphics, bx, by, bw, CONTROL, 4, DIVIDER, SURFACE);
        else switch (tone) {
            case Primary -> round(graphics, bx, by, bw, CONTROL, 4, hover ? mix(accent(), TEXT, 0.15) : accent());
            case Secondary -> panel(graphics, bx, by, bw, CONTROL, 4, hover ? mix(BORDER, TEXT, 0.15) : BORDER, hover ? mix(RAISED, TEXT, 0.05) : RAISED);
            case Danger -> panel(graphics, bx, by, bw, CONTROL, 4, hover ? mix(BORDER, RED, 0.5) : BORDER, hover ? mix(RAISED, RED, 0.15) : RAISED);
            case Ghost -> { if (hover) round(graphics, bx, by, bw, CONTROL, 4, RAISED); }
        }
        int color = !enabled ? MUTED : switch (tone) {
            case Primary -> onAccent();
            case Danger -> RED;
            case Ghost -> hover ? TEXT : SECONDARY;
            case Secondary -> TEXT;
        };
        float scale = bodyScale();
        type(graphics, label, bx + Math.max(4, (bw - width(label, scale)) / 2), by + (CONTROL - capHeight(scale)) / 2, bw - 8, color, scale);
        register(bx, by, bw, CONTROL, tooltip, enabled, action);
    }
    private void iconButton(String[] bitmap, String tooltip, int bx, int by, int color, Runnable action) {
        if (!visible(by, CONTROL)) return;
        boolean hover = over(bx, by, CONTROL, CONTROL);
        if (hover) round(graphics, bx, by, CONTROL, CONTROL, 4, RAISED);
        icon(graphics, bitmap, bx, by, CONTROL, CONTROL, hover && color == SECONDARY ? TEXT : color);
        register(bx, by, CONTROL, CONTROL, tooltip, true, action);
    }

    /** Places a row's buttons from its right edge leftwards, so text beside them can stop at {@link #left()}. */
    private final class Actions {
        private final int by;
        private int right;
        Actions(int right, int by) { this.right = right; this.by = by; }
        Actions add(String label, String tooltip, Tone tone, boolean enabled, Runnable action) {
            int bw = buttonWidth(label);
            right -= bw;
            button(label, tooltip, right, by, bw, tone, enabled, action);
            right -= GAP;
            return this;
        }
        Actions choice(String[] labels, String[] tooltips, int selected, IntConsumer action) {
            right -= segmentedWidth(labels);
            segmented(right, by, labels, tooltips, selected, action);
            right -= GAP;
            return this;
        }
        Actions icon(String[] bitmap, String tooltip, Runnable action) { return icon(bitmap, tooltip, SECONDARY, action); }
        Actions icon(String[] bitmap, String tooltip, int color, Runnable action) {
            right -= CONTROL;
            iconButton(bitmap, tooltip, right, by, color, action);
            right -= GAP;
            return this;
        }
        /** Leaves a gap for something the caller draws itself, such as a value between two buttons. */
        int reserve(int width) { right -= width; int start = right; right -= GAP; return start; }
        int left() { return right + GAP; }
    }

    private void focus(EditBox box) {
        input.setFocused(box == input); homeSearch.setFocused(box == homeSearch); playerSearch.setFocused(box == playerSearch);
        clanField.setFocused(box == clanField);
        autoListField.setFocused(box == autoListField);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) return true;
        if (event.x() < x || event.x() >= x + w || event.y() < y || event.y() >= y + h) { focus(null); return true; }
        for (Hit hit : List.copyOf(hits)) if (hit.contains(event.x(), event.y())) { hit.action().run(); hits.clear(); return true; }
        if (event.x() >= x + w - 10 && event.y() >= y + h - 10) { resizing = true; dragX = event.x(); dragY = event.y(); return true; }
        if (event.y() < layout.header().bottom()) { dragging = true; dragX = event.x(); dragY = event.y(); return true; }
        if (input.mouseClicked(event, doubled)) focus(input);
        else if (visibleSearch != null && event.y() >= bodyTop && event.y() < bodyBottom && visibleSearch.mouseClicked(event, doubled)) focus(visibleSearch);
        else focus(null);
        return true;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging && !resizing) return false;
        int ddx = (int) (event.x() - dragX), ddy = (int) (event.y() - dragY);
        if (resizing) { w += ddx; h += ddy; } else { x += ddx; y += ddy; }
        fit(); hits.clear(); dragX = event.x(); dragY = event.y();
        return true;
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) { dragging = resizing = false; return true; }
    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (mx < cx || mx >= cx + cw || my < bodyTop || my >= bodyBottom) return true;
        double amount = vertical != 0 ? vertical : horizontal;
        if (page == Page.Conversation) conversationScroll = Math.max(0, conversationScroll + (int) Math.signum(amount) * 30);
        else scroll = Math.clamp(scroll - (int) Math.signum(amount) * 30, 0, maxScroll);
        hits.clear();
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (!settingsPath.isEmpty()) back();
            else if (page == Page.Conversation) navigate(conversationBack);
            else if (page != Page.Home) navigate(Page.Home);
            else onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB) { focus(input.isFocused() && visibleSearch != null ? visibleSearch : input); return true; }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            if (visibleSearch == autoListField && autoListField.isFocused()) addAutoTpyNames();
            else if (visibleSearch == clanField && clanField.isFocused()) addTyped();
            else if (visibleSearch != null && visibleSearch.isFocused()) findPlayer(visibleSearch.getValue());
            else sendInput();
            return true;
        }
        if (input.isFocused()) return input.keyPressed(event);
        return visibleSearch != null && visibleSearch.isFocused() && visibleSearch.keyPressed(event);
    }
    @Override public boolean charTyped(CharacterEvent event) {
        if (input.isFocused()) return input.charTyped(event);
        return visibleSearch != null && visibleSearch.isFocused() && visibleSearch.charTyped(event);
    }
    @Override public void onFilesDrop(List<Path> files) {
        if(page==Page.AutoTpy) { if(autoBlocklistEditing) notice("Switch to Whitelist to import CSV.");else importAutoTpy(files); }
        else importFiles(files);
    }
    @Override public void tick() { if (!tools.isActive() || Minecraft.getInstance().getConnection() == null) Minecraft.getInstance().gui.setScreen(null); }
    @Override public void removed() { autoImportRevision++;saveDraft(); dragging = resizing = false; if (input != null) focus(null); }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(Minecraft.getInstance().level == null ? null : parent); }
    @Override public boolean isPauseScreen() { return false; }
}
