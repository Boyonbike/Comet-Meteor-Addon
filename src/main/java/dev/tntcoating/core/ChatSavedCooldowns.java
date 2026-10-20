package dev.tntcoating.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Cooldown lengths kept per server/account in a Meteor string-list setting: those learned from server replies and
 * those the player set manually. Entries read "server|Home|600000", with "|manual" appended for a manual length.
 */
public final class ChatSavedCooldowns {
    public record Saved(Map<ChatTeleportState.Kind, Long> learned, Map<ChatTeleportState.Kind, Long> manual) {
        public static final Saved NONE = new Saved(Map.of(), Map.of());
        public Saved { learned = Map.copyOf(learned); manual = Map.copyOf(manual); }
    }
    private static final String MANUAL = "manual";
    private static final long LIMIT = 86_400_000L;

    private ChatSavedCooldowns() {}
    private static String prefix(String server) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(server.getBytes(StandardCharsets.UTF_8)) + "|";
    }

    public static Saved read(List<String> entries, String server) {
        String prefix = prefix(server);
        Map<ChatTeleportState.Kind, Long> learned = new EnumMap<>(ChatTeleportState.Kind.class);
        Map<ChatTeleportState.Kind, Long> manual = new EnumMap<>(ChatTeleportState.Kind.class);
        for (String entry : entries) {
            if (!entry.startsWith(prefix)) continue;
            String[] parts = entry.substring(prefix.length()).split("\\|");
            boolean isManual = parts.length == 3 && parts[2].equals(MANUAL);
            if (parts.length != 2 && !isManual) continue;
            try {
                var kind = ChatTeleportState.Kind.valueOf(parts[0]);
                long millis = Long.parseLong(parts[1]);
                // A manual zero means "no cooldown"; a learned length is always positive.
                if (millis > LIMIT || millis < (isManual ? 0 : 1)) continue;
                (isManual ? manual : learned).put(kind, millis);
            } catch (IllegalArgumentException ignored) {
                // A hand-edited or outdated entry is skipped rather than breaking the others.
            }
        }
        return new Saved(learned, manual);
    }

    public static List<String> write(List<String> entries, String server, Saved saved) {
        String prefix = prefix(server);
        List<String> result = new ArrayList<>(entries.stream().filter(entry -> !entry.startsWith(prefix)).toList());
        for (var kind : ChatTeleportState.Kind.values()) {
            Long learned = saved.learned().get(kind);
            if (learned != null && learned > 0) result.add(prefix + kind.name() + "|" + learned);
            Long manual = saved.manual().get(kind);
            if (manual != null) result.add(prefix + kind.name() + "|" + manual + "|" + MANUAL);
        }
        return List.copyOf(result);
    }

    /**
     * One stepper press: 5 seconds under a minute, 30 under ten minutes, then a minute, landing on whole steps so a
     * learned 9m 59s becomes 10m or 9m 30s.
     */
    public static long step(long seconds, boolean longer) {
        long size = longer ? seconds < 60 ? 5 : seconds < 600 ? 30 : 60 : seconds <= 60 ? 5 : seconds <= 600 ? 30 : 60;
        long next = longer ? (seconds / size + 1) * size : (seconds + size - 1) / size * size - size;
        return Math.clamp(next, 0, LIMIT / 1_000);
    }
}
