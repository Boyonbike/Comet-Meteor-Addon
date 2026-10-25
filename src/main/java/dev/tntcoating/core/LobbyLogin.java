package dev.tntcoating.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds the cracked-account login command and recognises the replies common login plugins send. Only chat lines
 * that are not player chat should be classified, so a player cannot type a confirmation for the module.
 */
public final class LobbyLogin {
    public enum Result { IGNORED, SUCCESS, FAILURE }

    private static final List<Pattern> SUCCESS = List.of(
        Pattern.compile("\\bsuccessfully logged in\\b"),
        Pattern.compile("\\blogged in successfully\\b"),
        Pattern.compile("\\byou are (?:now )?logged in\\b"),
        Pattern.compile("\\byou have been logged in\\b"),
        Pattern.compile("\\blogin successful\\b"),
        Pattern.compile("\\bsuccessfully authenticated\\b"),
        Pattern.compile("\\bauthentication successful\\b"),
        Pattern.compile("\\byou (?:are|have) already (?:been )?logged in\\b"),
        Pattern.compile("\\balready logged in\\b")
    );

    private static final List<Pattern> FAILURE = List.of(
        Pattern.compile("\\bwrong password\\b"),
        Pattern.compile("\\bincorrect password\\b"),
        Pattern.compile("\\binvalid password\\b"),
        Pattern.compile("\\bpassword (?:is )?incorrect\\b"),
        Pattern.compile("\\b(?:wrong|incorrect|invalid) credentials\\b"),
        Pattern.compile("\\blogin failed\\b"),
        Pattern.compile("\\bfailed to log in\\b"),
        Pattern.compile("\\bauthentication failed\\b"),
        Pattern.compile("\\bnot registered\\b"),
        Pattern.compile("\\bplease register\\b"),
        Pattern.compile("\\bunknown account\\b"),
        Pattern.compile("\\baccount not found\\b"),
        Pattern.compile("\\btoo many (?:login )?attempts\\b"),
        Pattern.compile("\\bplease wait before (?:trying|logging in) again\\b")
    );

    private LobbyLogin() {}

    /** The command packet body for {@code password}, or null when there is no password to send. */
    public static String command(String password) {
        if (password == null || password.isBlank()) return null;
        return "login " + password;
    }

    /** The line without the password, for showing a server reply in chat without leaking it. */
    public static String redact(String message, String password) {
        if (message == null) return "";
        if (password == null || password.isBlank()) return message;
        return message.replace(password, "********");
    }

    /** Classifies a chat line that the caller has already confirmed is not player chat. */
    public static Result classify(String message) {
        if (message == null || message.isBlank()) return Result.IGNORED;
        String text = ChatSender.plain(message).toLowerCase(Locale.ROOT);
        for (Pattern pattern : SUCCESS) {
            if (pattern.matcher(text).find()) return Result.SUCCESS;
        }
        for (Pattern pattern : FAILURE) {
            if (pattern.matcher(text).find()) return Result.FAILURE;
        }
        return Result.IGNORED;
    }
}
