package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Server preferences and customization from https://www.6b6t.org/en/commands. */
public final class ChatServerSettings {
    public record Entry(String label, String command, List<Entry> children) {
        public boolean menu() { return !children.isEmpty(); }
        public boolean needsInput() { return command != null && command.endsWith(" "); }
        /** The top-level command name, such as "togglechat" for "/togglechat"; null for a menu. */
        public String rootCommand() {
            if (command == null) return null;
            String text = command.startsWith("/") ? command.substring(1) : command;
            int space = text.indexOf(' ');
            return space < 0 ? text : text.substring(0, space);
        }
        public Entry { children = List.copyOf(children); }
    }

    private ChatServerSettings() {}

    public static List<Entry> groups() { return GROUPS; }

    /** Only entries whose command the server offers; a menu disappears once none of its entries remain. */
    public static List<Entry> available(List<Entry> entries, Predicate<String> commandAvailable) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.menu()) {
                List<Entry> children = available(entry.children(), commandAvailable);
                if (!children.isEmpty()) result.add(new Entry(entry.label(), entry.command(), children));
            } else if (commandAvailable.test(entry.rootCommand())) result.add(entry);
        }
        return result;
    }

    private static Entry run(String label, String command) { return new Entry(label, command, List.of()); }
    private static Entry edit(String label, String command) { return run(label, command + " "); }
    private static Entry menu(String label, Entry... children) { return new Entry(label, null, List.of(children)); }
    private static Entry onOff(String label, String command) {
        return menu(label, run("On", command + " on"), run("Off", command + " off"));
    }

    private static final List<Entry> GROUPS = List.of(
        menu("Chat",
            run("Toggle chat", "/togglechat"), run("Toggle whispers", "/togglewhispers"),
            onOff("Spam messages", "/togglespam"), onOff("Unranked messages", "/toggleranked"),
            edit("Ignore player", "/ignore"), run("Ignored players", "/ignorelist"),
            run("Clear ignored players", "/ignorelist clear"), run("Group chat", "/groupchat help")),
        menu("Bots", onOff("All bot messages", "/togglebots"),
            onOff("Chat", "/bots chat"), onOff("Whispers", "/bots whispers"),
            onOff("Connections", "/bots connections"), onOff("Deaths", "/bots deaths"), run("Status", "/bots")),
        menu("Notifications",
            menu("Connections", run("On", "/connectionmsgs on"), run("Off", "/connectionmsgs off"), run("Colour", "/connectionmsgs color")),
            menu("Deaths", run("On", "/deathmsgs on"), run("Off", "/deathmsgs off"), run("Colour", "/deathmsgs color")),
            menu("Advancements in chat", run("All", "/advancements chat all"), run("Personal", "/advancements chat personal"),
                run("Others", "/advancements chat others"), run("None", "/advancements chat none")),
            onOff("Advancement popups", "/advancements toast")),
        menu("Homes & TPA", run("Toggle TPA requests", "/toggletpa"), edit("Set home", "/sethome"),
            edit("Rename home", "/homerename"), edit("Delete home", "/delhome"),
            run("Home list", "/homes"), run("Cancel home", "/homecancel"), run("Cancel TPA", "/tpacancel")),
        menu("Appearance", run("Chat colour", "/chatcolor"), run("Name colour", "/namecolor"),
            run("Particles", "/particles"), run("Hat", "/hat"), run("Invisible frames", "/invisframe"),
            run("Glow", "/glow"), run("Balloons", "/balloons"), edit("Skin", "/skin"),
            run("Sit", "/sit"), run("Lay", "/lay"), run("Cigar", "/cigar")),
        menu("Hotspot", menu("Notifications", run("Actionbar", "/hotspot notify actionbar"), edit("Other mode", "/hotspot notify")),
            run("Colour", "/hotspot color"), edit("Name", "/hotspot name"), run("Create", "/hotspot create"),
            run("Cancel hotspot", "/hotspot cancel"), run("Glow", "/hotspot glow"), run("Balloons", "/hotspot balloon"), run("Info", "/hotspot info")),
        menu("Gameplay", run("Builder mode", "/buildermode"), edit("Builder mode at home", "/buildermode home"),
            run("Freecam", "/freecam"), run("PvP messages", "/pvpmode"))
    );
}
