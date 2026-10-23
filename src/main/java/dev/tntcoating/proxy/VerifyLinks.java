package dev.tntcoating.proxy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the verification URL inside a disconnect reason. Servers usually print the link as plain
 * text, and the last path segment changes on every refusal, so it has to be read from the message
 * that is on screen rather than stored anywhere.
 */
public final class VerifyLinks {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+");
    private static final Pattern TRAILING = Pattern.compile("[.,;:!?)\\]}]+$");

    private VerifyLinks() {}

    /** @return the verification URL in {@code text}, or null when there is none. */
    public static String find(String text) {
        if (text == null || text.isEmpty()) return null;

        String best = null;
        Matcher matcher = URL.matcher(text);

        while (matcher.find()) {
            String url = TRAILING.matcher(matcher.group()).replaceAll("");
            if (url.length() <= "https://".length()) continue;

            if (best == null) best = url;
            if (isVerificationHost(url)) return url;
        }

        return best;
    }

    private static boolean isVerificationHost(String url) {
        String lower = url.toLowerCase();
        return lower.contains("verify.") || lower.contains("/verify") || lower.contains("enderdash.com");
    }
}
