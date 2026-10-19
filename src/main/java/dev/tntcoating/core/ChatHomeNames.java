package dev.tntcoating.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ChatHomeNames {
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_+.-]{1,64}");
    private static final Pattern HOME_LIST = Pattern.compile("^(?:your\\s+)?homes(?:\\s*\\(\\d+(?:/\\d+)?\\))?\\s*:.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPTY_LIST = Pattern.compile("none\\.?|no homes.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARATORS = Pattern.compile("[,\\s]+");

    private ChatHomeNames() {}

    /** Accept one argument only, so a home button can never append a second command. */
    public static String valid(String value) {
        if (value == null) return null;
        String name = value.strip();
        return NAME.matcher(name).matches() && !name.equals(".") && !name.equals("..") ? name : null;
    }

    @SafeVarargs
    public static List<String> merge(Collection<String>... sources) {
        var names = new LinkedHashMap<String, String>();
        for (Collection<String> source : sources) for (String value : source) {
            String name = valid(value);
            if (name != null) names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        return new ArrayList<>(names.values());
    }

    /** Common plain-text /homes output; clickable /home commands are collected separately. */
    public static List<String> fromMessage(String raw) {
        String text = ChatSender.plain(raw).strip();
        if (!HOME_LIST.matcher(text).matches()) return List.of();
        String list = text.substring(text.indexOf(':') + 1).strip();
        if (list.isEmpty() || EMPTY_LIST.matcher(list).matches()) return List.of();
        return merge(List.of(SEPARATORS.split(list)));
    }
}
