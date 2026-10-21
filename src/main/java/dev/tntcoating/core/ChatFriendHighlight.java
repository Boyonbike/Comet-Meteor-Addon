package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Colours complete usernames before line wrapping, preserving inherited chat interactions. */
public final class ChatFriendHighlight {
    private record Run(int start, String text, Style style) { int end() { return start + text.length(); } }
    private record Match(int start, int end, int color) {}
    private static final Pattern USERNAME = Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z0-9_]{1,16}(?![A-Za-z0-9_])");

    private ChatFriendHighlight() {}

    public static Component apply(Component message, Predicate<String> friend, int color) {
        return apply(message, name -> friend.test(name) ? color : null);
    }

    /** Colours each complete username {@code colors} returns a colour for; null leaves that name as the server sent it. */
    public static Component apply(Component message, Function<String, Integer> colors) {
        List<Run> runs = runs(message);
        StringBuilder text = new StringBuilder();
        for (Run run : runs) text.append(run.text());
        List<Match> matches = new ArrayList<>();
        Matcher matcher = USERNAME.matcher(text);
        while (matcher.find()) {
            Integer color = colors.apply(matcher.group());
            if (color != null) matches.add(new Match(matcher.start(), matcher.end(), color));
        }
        if (matches.isEmpty()) return message;
        MutableComponent result = Component.empty();
        for (Run run : runs) {
            int offset = 0;
            for (Match match : matches) {
                if (match.end() <= run.start()) continue;
                if (match.start() >= run.end()) break;
                int start = Math.max(match.start(), run.start()) - run.start();
                int end = Math.min(match.end(), run.end()) - run.start();
                append(result, run, offset, start, run.style());
                append(result, run, start, end, run.style().withColor(match.color() & 0xffffff));
                offset = end;
            }
            append(result, run, offset, run.text().length(), run.style());
        }
        return result;
    }

    /** The whole text in one colour, for names that never spell out the username, such as a nickname in the tab list. */
    public static Component recolor(Component message, int color) {
        MutableComponent result = Component.empty();
        for (Run run : runs(message)) append(result, run, 0, run.text().length(), run.style().withColor(color & 0xffffff));
        return result;
    }

    private static List<Run> runs(Component message) {
        List<Run> runs = new ArrayList<>();
        int[] length = {0};
        message.visit((Style style, String part) -> {
            runs.add(new Run(length[0], part, style));
            length[0] += part.length();
            return Optional.empty();
        }, Style.EMPTY);
        return runs;
    }

    private static void append(MutableComponent result, Run run, int start, int end, Style style) {
        if (end > start) result.append(Component.literal(run.text().substring(start, end)).setStyle(style));
    }
}
