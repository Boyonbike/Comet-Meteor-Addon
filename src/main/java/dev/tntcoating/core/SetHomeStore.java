/* SPDX-License-Identifier: GPL-3.0-only */
package dev.tntcoating.core;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Small versioned state file, replaced atomically on each meaningful sethome transition. */
public final class SetHomeStore {
    private static final Gson GSON = new Gson();
    private static final int MAX_BYTES = 4_194_304;
    private record Document(int version, Map<String, SetHomeScheduler.State> records) {}
    private final Path file;
    private final Map<String, SetHomeScheduler.State> records = new LinkedHashMap<>();
    private String loadError;
    public SetHomeStore(Path file) {
        this.file = file;
        if (!Files.exists(file)) return;
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("Home state exceeds size limit");
            Document doc = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Document.class);
            if (doc == null || (doc.version() != 1 && doc.version() != 2) || doc.records() == null || doc.records().size() > 128
                || doc.records().entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null))
                throw new IOException("Unsupported home state");
            doc.records().forEach((key, state) -> records.put(key, doc.version() == 1 ? upgradeLegacy(state) : state));
        } catch (IOException | RuntimeException e) { loadError = e.getMessage(); }
    }
    /** Old learning used elapsed offline time and could persist an inflated estimate indefinitely. */
    private static SetHomeScheduler.State upgradeLegacy(SetHomeScheduler.State state) {
        boolean estimated = state.successActionAt() > 0 && state.lastSuccessAt() >= state.lastSentAt();
        return new SetHomeScheduler.State(state.lastSentAt(), state.lastSuccessAt(), state.successActionAt(),
            0, estimated ? 0 : state.cooldownEnd(), state.retryAt(), state.history());
    }
    /** Most recently saved server for this account, for the dashboard before joining a world. */
    public String latestServer(String account) {
        String latest = null;
        String prefix = account + "|";
        for (String key : records.keySet()) if (key.startsWith(prefix)) latest = key.substring(prefix.length());
        return latest;
    }
    public String loadError() { return loadError; }
    public static String normaliseServer(String server) {
        return server == null ? "singleplayer" : server.strip().toLowerCase(Locale.ROOT).replaceFirst(":25565$", "");
    }
    private static String key(String account, String server) { return account + "|" + normaliseServer(server); }
    public SetHomeScheduler.State load(String account, String server) {
        return records.getOrDefault(key(account, server), SetHomeScheduler.State.empty());
    }
    public void save(String account, String server, SetHomeScheduler.State state) throws IOException {
        var updated = new LinkedHashMap<>(records);
        String key = key(account, server);
        updated.remove(key); updated.put(key, state);
        while (updated.size() > 128) updated.remove(updated.keySet().iterator().next());
        byte[] bytes = GSON.toJson(new Document(2, updated)).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Home state exceeds size limit");
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "auto-set-home-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            records.clear(); records.putAll(updated);
        } finally { Files.deleteIfExists(temporary); }
    }
}
