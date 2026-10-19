package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Colours complete usernames before line wrapping, preserving inherited chat interactions. */
public final class ChatFriendHighlight {
    private record Run(int start, String text, Style style) { int end() { return start + text.length(); } }
    private record Match(int start, int end) {}
    private static final Pattern USERNAME = Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z0-9_]{1,16}(?![A-Za-z0-9_])");

    private ChatFriendHighlight() {}

    public static Component apply(Component message, Predicate<String> friend, int color) {
        List<Run> runs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        message.visit((Style style, String part) -> {
            runs.add(new Run(text.length(), part, style));
            text.append(part);
            return Optional.empty();
        }, Style.EMPTY);
        List<Match> matches = new ArrayList<>();
        Matcher matcher = USERNAME.matcher(text);
        while (matcher.find()) if (friend.test(matcher.group())) matches.add(new Match(matcher.start(), matcher.end()));
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
                append(result, run, start, end, run.style().withColor(color & 0xffffff));
                offset = end;
            }
            append(result, run, offset, run.text().length(), run.style());
        }
        return result;
    }

    private static void append(MutableComponent result, Run run, int start, int end, Style style) {
        if (end > start) result.append(Component.literal(run.text().substring(start, end)).setStyle(style));
    }
}
