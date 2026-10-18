package dev.tntcoating.core;

/** Parses user-configured chat command entries without losing meaningful trailing spaces. */
public record ChatCommandTemplate(String label, String command) {
    public static ChatCommandTemplate parse(String entry) {
        if (entry == null) return new ChatCommandTemplate("", "");

        int separator = entry.indexOf('|');
        String command = separator >= 0 ? entry.substring(separator + 1).stripLeading() : entry.stripLeading();
        String label = separator >= 0 ? entry.substring(0, separator).trim() : commandLabel(command);
        return new ChatCommandTemplate(label.isEmpty() ? commandLabel(command) : label, command);
    }

    public String expand(String player) {
        String expanded = command.replace("{player}", player).replace("%player%", player);
        if (!command.contains("{player}") && !command.contains("%player%")) {
            expanded += (expanded.endsWith(" ") ? "" : " ") + player;
        }
        return expanded.startsWith("/") ? expanded : "/" + expanded;
    }

    public boolean valid() {
        return !command.isBlank();
    }

    private static String commandLabel(String command) {
        String value = command.stripLeading();
        if (value.startsWith("/")) value = value.substring(1);
        int end = value.indexOf(' ');
        String root = end < 0 ? value : value.substring(0, end);
        return root.isBlank() ? "Command" : "/" + root;
    }
}
