package dev.tntcoating.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Reads the guild member list and the last pages of an #ign channel from Discord's REST API. Each sync is a fresh
 * full read, so edited or deleted messages and players who left are noticed.
 */
public final class DiscordIgnClient {
    private static final String BASE = "https://discord.com/api/v10";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MEMBER_PAGE = 1000;
    private static final int MESSAGE_PAGE = 100;
    private static final int MAX_MEMBER_PAGES = 20;
    private static final int MAX_MESSAGE_PAGES = 100;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** A response body with its status; Retry-After is kept for 429s. */
    public record Response(int status, String body, String retryAfter, String remaining, String resetAfter) {
        public Response(int status, String body, String retryAfter) { this(status, body, retryAfter, "", ""); }
    }

    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }

    /** Does one HTTP exchange. Tests use a fake; production is {@link #live()}. */
    @FunctionalInterface public interface Transport {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }

    /** Which request Discord refused, so chat can say what to fix. */
    public enum Endpoint {CHANNEL, ROLES, MEMBERS, MESSAGES}

    /** Discord refused a request. {@link #explanation()} is written for the chat log, not for a stack trace. */
    public static final class ApiException extends Exception {
        private final Endpoint endpoint;
        private final int status;
        private final String retryAfter;

        ApiException(Endpoint endpoint, int status, String retryAfter) {
            super("Discord returned HTTP " + status + " for " + endpoint.name().toLowerCase());
            this.endpoint = endpoint;
            this.status = status;
            this.retryAfter = retryAfter == null ? "" : retryAfter;
        }

        public long retryDelayMillis() {
            return status == 429 ? Math.max(1, secondsMillis(retryAfter, 60_000)) : 0;
        }

        public String explanation() {
            return switch (status) {
                case 401 -> "Discord rejected the bot token (401). Reset the token in the Developer Portal, then paste "
                    + "the new one; the old token stops working as soon as it is reset.";
                case 403 -> switch (endpoint) {
                    case MEMBERS -> "Discord refused the member list (403). Turn on Server Members Intent on the app's Bot page.";
                    case CHANNEL, MESSAGES -> "Discord refused #ign (403). Give the bot View Channel and Read Message History on that channel.";
                    case ROLES -> "Discord refused the role list (403). Re-invite the bot with the invite link from the setup guide.";
                };
                case 404 -> switch (endpoint) {
                    case MEMBERS, ROLES -> "Discord could not find the guild (404). Check guild-id.";
                    case CHANNEL, MESSAGES -> "Discord could not find #ign (404). Check ign-channel-id.";
                };
                case 429 -> "Discord is rate limiting the sync. Try again "
                    + "in " + (long) Math.ceil(retryDelayMillis() / 1000.0) + " seconds.";
                default -> "Discord returned HTTP " + status + " while reading "
                    + (endpoint == Endpoint.MEMBERS ? "the member list" : endpoint == Endpoint.ROLES
                    ? "the role list" : "#ign messages") + ".";
            };
        }
    }

    /**
     * One sync: every eligible guild member's posted Java accounts, plus all roles for each accepted account.
     */
    public record Sync(DiscordIgn.Result result, Map<String, List<DiscordRoles.Role>> roles) {}

    private final Transport transport;
    private final String token;
    private final int memberLimit;
    private final int messageLimit;
    private final int maxMemberPages;
    private final int maxMessagePages;
    private final Sleeper sleeper;
    private final LongConsumer deferRetry;
    private long pendingWait;
    private long deadline;

    public DiscordIgnClient(Transport transport, String token) {
        this(transport, token, MEMBER_PAGE, MESSAGE_PAGE, MAX_MEMBER_PAGES, MAX_MESSAGE_PAGES);
    }

    DiscordIgnClient(Transport transport, String token, int memberLimit, int messageLimit, int maxMemberPages,
                     int maxMessagePages) {
        this(transport, token, memberLimit, messageLimit, maxMemberPages, maxMessagePages, Thread::sleep);
    }

    public DiscordIgnClient(Transport transport, String token, LongConsumer deferRetry) {
        this(transport, token, MEMBER_PAGE, MESSAGE_PAGE, MAX_MEMBER_PAGES, MAX_MESSAGE_PAGES, Thread::sleep, deferRetry);
    }

    DiscordIgnClient(Transport transport, String token, int memberLimit, int messageLimit, int maxMemberPages,
                     int maxMessagePages, Sleeper sleeper) {
        this(transport, token, memberLimit, messageLimit, maxMemberPages, maxMessagePages, sleeper, millis -> {});
    }

    DiscordIgnClient(Transport transport, String token, int memberLimit, int messageLimit, int maxMemberPages,
                     int maxMessagePages, Sleeper sleeper, LongConsumer deferRetry) {
        this.deferRetry = deferRetry;
        this.transport = transport;
        this.sleeper = sleeper;
        this.token = token.strip();
        this.memberLimit = memberLimit;
        this.messageLimit = messageLimit;
        this.maxMemberPages = maxMemberPages;
        this.maxMessagePages = maxMessagePages;
    }

    /** The production transport: one shared HTTP client, no proxy tricks, no redirect following. */
    public static Transport live() {
        return request -> {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String retryAfter = response.headers().firstValue("Retry-After").orElse("");
            return new Response(response.statusCode(), response.body(), retryAfter,
                response.headers().firstValue("X-RateLimit-Remaining").orElse(""),
                response.headers().firstValue("X-RateLimit-Reset-After").orElse(""));
        };
    }

    /**
     * Read every eligible guild member, their account posts and their role labels. Roles never filter membership.
     */
    public Sync fetch(String guildId, String channelId)
        throws ApiException, IOException, InterruptedException {
        deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        JsonObject channel = JsonParser.parseString(get(BASE + "/channels/" + channelId, Endpoint.CHANNEL).body()).getAsJsonObject();
        if (!channelId.equals(string(channel, "id")) || !guildId.equals(string(channel, "guild_id")))
            throw new IOException("ign-channel-id must identify a channel in guild-id.");
        JsonElement type = channel.get("type");
        if (type == null || !Set.of(0, 5, 10, 11, 12).contains(type.getAsInt()))
            throw new IOException("ign-channel-id must be a text channel or text thread.");
        List<String> members = memberPages(guildId);
        String roleJson = get(BASE + "/guilds/" + guildId + "/roles", Endpoint.ROLES).body();
        DiscordIgn.Result result = DiscordIgn.parse(members, messagePages(channelId));
        return new Sync(result, DiscordRoles.bind(result.roleIds(), guildId, roleJson));
    }

    private List<String> memberPages(String guildId) throws ApiException, IOException, InterruptedException {
        List<String> pages = new ArrayList<>();
        String after = null;
        for (int page = 0; page < maxMemberPages; page++) {
            String url = BASE + "/guilds/" + guildId + "/members?limit=" + memberLimit
                + (after == null ? "" : "&after=" + after);
            Response response = get(url, Endpoint.MEMBERS);
            pages.add(response.body());
            JsonArray members = JsonParser.parseString(response.body()).getAsJsonArray();
            if (members.size() < memberLimit) return pages;
            String last = lastUserId(members);
            if (last == null || last.equals(after)) {
                throw new IOException("Discord returned a member page with no user ID.");
            }
            after = last;
        }
        if (JsonParser.parseString(get(BASE + "/guilds/" + guildId + "/members?limit=1&after=" + after,
            Endpoint.MEMBERS).body()).getAsJsonArray().isEmpty()) return pages;
        throw new IOException("This guild has more members than one sync reads ("
            + (maxMemberPages * memberLimit) + ").");
    }

    private List<String> messagePages(String channelId) throws ApiException, IOException, InterruptedException {
        List<String> pages = new ArrayList<>();
        String before = null;
        for (int page = 0; page < maxMessagePages; page++) {
            String url = BASE + "/channels/" + channelId + "/messages?limit=" + messageLimit
                + (before == null ? "" : "&before=" + before);
            Response response = get(url, Endpoint.MESSAGES);
            pages.add(response.body());
            JsonArray messages = JsonParser.parseString(response.body()).getAsJsonArray();
            if (messages.size() < messageLimit) return pages;
            String last = lastMessageId(messages);
            if (last == null || last.equals(before)) {
                throw new IOException("Discord returned a message page with no message ID.");
            }
            before = last;
        }
        if (JsonParser.parseString(get(BASE + "/channels/" + channelId + "/messages?limit=1&before=" + before,
            Endpoint.MESSAGES).body()).getAsJsonArray().isEmpty()) return pages;
        throw new IOException("The #ign history reached the sync limit (" + (maxMessagePages * messageLimit)
            + " messages). A partial roster cannot safely update the lists; use a dedicated, shorter #ign channel.");
    }

    private Response get(String url, Endpoint endpoint) throws ApiException, IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("Authorization", "Bot " + token)
            .header("User-Agent", "Comet discord-ign-sync")
            .header("Accept", "application/json")
            .GET()
            .build();
        if (pendingWait > 0) {
            long wait = pendingWait;
            pendingWait = 0;
            pause(wait, endpoint);
        }
        for (int attempt = 0; ; attempt++) {
            checkCancelled();
            Response response;
            try {
                response = transport.send(request);
            } catch (IOException exception) {
                if (attempt >= 2) throw exception;
                pause(1000L << attempt, endpoint);
                continue;
            }
            if (response.status() == 200) {
                if ("0".equals(response.remaining())) {
                    pendingWait = secondsMillis(response.resetAfter(), 0);
                    if (pendingWait > 0) deferRetry.accept(pendingWait);
                }
                return response;
            }
            long delay = secondsMillis(response.retryAfter(), 0);
            if (response.status() == 429) {
                try {
                    JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                    delay = Math.max(delay, secondsMillis(string(body, "retry_after"), 0));
                } catch (RuntimeException ignored) { /* A broken error body must not hide the status/header. */ }
                if (delay == 0) delay = 60_000;
                // Publish the deadline before an interruptible wait so disable/re-enable cannot bypass it.
                deferRetry.accept(delay);
            }
            ApiException error = new ApiException(endpoint, response.status(), Double.toString(delay / 1000.0));
            if (attempt >= 2 || (response.status() != 429 && response.status() < 500)
                || response.status() >= 600) throw error;
            if (response.status() == 429 && delay > 30_000) throw error;
            pause(response.status() == 429 ? delay : 1000L << attempt, endpoint);
        }
    }

    private void checkCancelled() throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Discord sync cancelled");
        if (System.nanoTime() >= deadline) throw new IOException("Discord sync exceeded its five-minute time limit.");
    }

    private void pause(long millis, Endpoint endpoint) throws ApiException, IOException, InterruptedException {
        checkCancelled();
        if (millis > 30_000) throw new ApiException(endpoint, 429, Double.toString(millis / 1000.0));
        sleeper.sleep(millis);
        checkCancelled();
    }

    private static long secondsMillis(String text, long fallback) {
        try {
            double seconds = Double.parseDouble(text);
            if (!Double.isFinite(seconds) || seconds <= 0) return fallback;
            return (long) Math.min(Math.ceil(seconds * 1000), Long.MAX_VALUE / 4.0);
        } catch (RuntimeException ignored) { return fallback; }
    }

    /** The last user ID on a member page, for the next {@code after}. */
    private static String lastUserId(JsonArray page) {
        for (int index = page.size() - 1; index >= 0; index--) {
            JsonElement element = page.get(index);
            if (!element.isJsonObject()) continue;
            JsonObject user = object(element.getAsJsonObject(), "user");
            if (user == null) continue;
            String id = string(user, "id");
            if (!id.isEmpty()) return id;
        }
        return null;
    }

    /** The last message ID on a message page, for the next {@code before}. */
    private static String lastMessageId(JsonArray page) {
        for (int index = page.size() - 1; index >= 0; index--) {
            JsonElement element = page.get(index);
            if (!element.isJsonObject()) continue;
            String id = string(element.getAsJsonObject(), "id");
            if (!id.isEmpty()) return id;
        }
        return null;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }
}
