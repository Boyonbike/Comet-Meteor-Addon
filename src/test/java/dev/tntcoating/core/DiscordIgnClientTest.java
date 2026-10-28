package dev.tntcoating.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DiscordIgnClientTest {
    @Test void pagesMembersByUserIdAndStopsOnShortPage() throws Exception {
        List<String> urls = new ArrayList<>();
        DiscordIgnClient client = client(request -> {
            urls.add(request.uri().toString());
            assertEquals("Bot secret", request.headers().firstValue("Authorization").orElse(""));
            assertEquals("Comet discord-ign-sync", request.headers().firstValue("User-Agent").orElse(""));
            String url = request.uri().toString();
            if (url.contains("/members")) {
                return url.contains("after=") ? ok("[]") : ok(array(member("1"), member("2")));
            }
            return ok(array(message("9", "1", "Notch")));
        }, "secret", 2, 2, 4, 4);

        DiscordIgnClient.Sync sync = client.fetch("g1", "c1");

        assertEquals(List.of("Notch"), sync.result().names());
        assertTrue(urls.get(0).contains("/guilds/g1/members?limit=2"));
        assertTrue(urls.get(1).contains("after=2"));
    }

    @Test void pagesMessagesBackwardsByMessageId() throws Exception {
        List<String> urls = new ArrayList<>();
        DiscordIgnClient client = client(request -> {
            String url = request.uri().toString();
            urls.add(url);
            if (url.contains("/members")) return ok(array(member("1")));
            if (url.contains("/messages")) {
                if (url.contains("before=")) return ok(array(message("1", "1", "Old")));
                return ok(array(message("3", "1", "New"), message("2", "1", "Older")));
            }
            return ok("[]");
        }, "secret", 2, 2, 4, 4);

        DiscordIgnClient.Sync sync = client.fetch("g1", "c1");

        assertEquals(List.of("New"), sync.result().names());
        assertTrue(urls.stream().anyMatch(url -> url.contains("/channels/c1/messages?limit=2")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("before=2")));
    }

    @Test void syncsEveryMemberRegardlessOfRoleAndAttachesAllRoleNames() throws Exception {
        DiscordIgnClient client = new DiscordIgnClient(channel(request -> {
            if (request.uri().getPath().endsWith("/members"))
                return ok(array(member("1", "10", "20"), member("2", "20"), member("3")));
            return ok(array(message("5", "1", "Alice"), message("4", "2", "Bob"), message("3", "3", "Carl")));
        }, array(role("10", "Clan Member"), role("20", "Ally"))), "secret", 4, 4, 4, 4, millis -> {});
        var sync = client.fetch("g1", "c1");
        assertEquals(List.of("Alice", "Bob", "Carl"), sync.result().names());
        assertEquals("Clan Member · Ally", DiscordRoles.label(sync.roles(), "Alice"));
        assertEquals("Ally", DiscordRoles.label(sync.roles(), "Bob"));
        assertEquals("No role", DiscordRoles.label(sync.roles(), "Carl"));
    }

    @Test void missingRoleDefinitionsLeaveTheReadUnapplied() {
        DiscordIgnClient client = client(request -> {
            if (request.uri().getPath().endsWith("/members")) return ok(array(member("1", "999")));
            return ok(array(message("1", "1", "Alice")));
        }, "secret", 4, 4, 4, 4);
        assertThrows(IOException.class, () -> client.fetch("g1", "c1"));
    }

    @Test void explainsAMissingMembersIntent() {
        DiscordIgnClient client = client(
            request -> new DiscordIgnClient.Response(403, "{}", ""), "secret", 4, 4, 4, 4);

        DiscordIgnClient.ApiException error = assertThrows(DiscordIgnClient.ApiException.class,
            () -> client.fetch("g1", "c1"));
        assertTrue(error.explanation().contains("Server Members Intent"));
    }

    @Test void explainsAMissingChannelPermission() {
        DiscordIgnClient client = client(request -> {
            String url = request.uri().toString();
            if (url.contains("/members")) return ok(array(member("1")));
            return new DiscordIgnClient.Response(403, "{}", "");
        }, "secret", 4, 4, 4, 4);

        DiscordIgnClient.ApiException error = assertThrows(DiscordIgnClient.ApiException.class,
            () -> client.fetch("g1", "c1"));
        assertTrue(error.explanation().contains("Read Message History"));
    }

    @Test void explainsRateLimitsWithRetryAfter() {
        DiscordIgnClient client = client(
            request -> new DiscordIgnClient.Response(429, "{}", "7"), "secret", 4, 4, 4, 4);

        DiscordIgnClient.ApiException error = assertThrows(DiscordIgnClient.ApiException.class,
            () -> client.fetch("g1", "c1"));
        assertTrue(error.explanation().contains("7 seconds"));
    }

    @Test void flagsMessagesWithoutContent() throws Exception {
        DiscordIgnClient client = client(request -> {
            String url = request.uri().toString();
            if (url.contains("/members")) return ok(array(member("1")));
            return ok(array(message("1", "1", "")));
        }, "secret", 4, 4, 4, 4);

        DiscordIgnClient.Sync sync = client.fetch("g1", "c1");

        assertTrue(sync.result().unreadable());
    }

    @Test void refusesTruncatedMessageHistoryInsteadOfReturningPartialRoster() {
        DiscordIgnClient client = client(request -> {
            if (request.uri().getPath().endsWith("/members")) return ok(array(member("1")));
            return ok(array(message("3", "1", "Alice"), message("2", "1", "Older")));
        }, "secret", 2, 2, 4, 1);
        assertThrows(IOException.class, () -> client.fetch("g1", "c1"));
    }

    private static DiscordIgnClient client(DiscordIgnClient.Transport transport, String token,
                                            int members, int messages, int memberPages, int messagePages) {
        return new DiscordIgnClient(channel(transport), token, members, messages, memberPages, messagePages, millis -> {});
    }

    private static DiscordIgnClient.Transport channel(DiscordIgnClient.Transport transport) {
        return channel(transport, "[]");
    }

    private static DiscordIgnClient.Transport channel(DiscordIgnClient.Transport transport, String roles) {
        return request -> {
            String path = request.uri().getPath();
            if (path.equals("/api/v10/channels/c1")) return ok("{\"id\":\"c1\",\"guild_id\":\"g1\",\"type\":0}");
            if (path.equals("/api/v10/guilds/g1/roles")) return ok(roles);
            return transport.send(request);
        };
    }

    @Test void refusesChannelFromADifferentGuildBeforeReadingNames() {
        List<String> paths = new ArrayList<>();
        DiscordIgnClient client = new DiscordIgnClient(request -> {
            paths.add(request.uri().getPath());
            return ok("{\"id\":\"c1\",\"guild_id\":\"other\",\"type\":0}");
        }, "secret", 2, 2, 4, 4);
        assertThrows(IOException.class, () -> client.fetch("g1", "c1"));
        assertEquals(List.of("/api/v10/channels/c1"), paths);
    }

    @Test void exactMemberAndMessageLimitsAreAllowedAfterAnEmptyProbe() throws Exception {
        DiscordIgnClient client = client(request -> {
            String url = request.uri().toString();
            if (url.contains("after=") || url.contains("before=")) return ok("[]");
            if (url.contains("/members")) return ok(array(member("1"), member("2")));
            return ok(array(message("3", "1", "Alice"), message("2", "2", "Bob")));
        }, "secret", 2, 2, 1, 1);
        assertEquals(List.of("Alice", "Bob"), client.fetch("g1", "c1").result().names());
    }

    @Test void retriesRateLimitsUsingJsonDelayWithoutBlockingTheGameThread() throws Exception {
        List<Long> waits = new ArrayList<>();
        int[] requests = {0};
        DiscordIgnClient client = new DiscordIgnClient(channel(request -> {
            if (request.uri().getPath().endsWith("/members")) {
                if (requests[0]++ == 0) return new DiscordIgnClient.Response(429, "{\"retry_after\":0.25}", "");
                return ok(array(member("1")));
            }
            return ok(array(message("1", "1", "Alice")));
        }), "secret", 2, 2, 4, 4, waits::add);
        assertEquals(List.of("Alice"), client.fetch("g1", "c1").result().names());
        assertEquals(List.of(250L), waits);
        assertEquals(2, requests[0]);
    }

    @Test void retriesTransientServerAndNetworkFailuresWithABound() throws Exception {
        List<Long> waits = new ArrayList<>();
        int[] requests = {0};
        DiscordIgnClient client = new DiscordIgnClient(channel(request -> {
            if (request.uri().getPath().endsWith("/members")) {
                int attempt = requests[0]++;
                if (attempt == 0) throw new IOException("connection reset");
                if (attempt == 1) return new DiscordIgnClient.Response(503, "{}", "");
                return ok(array(member("1")));
            }
            return ok(array(message("1", "1", "Alice")));
        }), "secret", 2, 2, 4, 4, waits::add);
        assertEquals(List.of("Alice"), client.fetch("g1", "c1").result().names());
        assertEquals(List.of(1000L, 2000L), waits);
        assertEquals(3, requests[0]);
    }

    @Test void doesNotRetryInvalidCredentials() {
        List<Long> waits = new ArrayList<>();
        int[] requests = {0};
        DiscordIgnClient client = new DiscordIgnClient(channel(request -> {
            requests[0]++;
            return new DiscordIgnClient.Response(401, "{}", "");
        }), "secret", 2, 2, 4, 4, waits::add);
        assertThrows(DiscordIgnClient.ApiException.class, () -> client.fetch("g1", "c1"));
        assertEquals(1, requests[0]);
        assertTrue(waits.isEmpty());
    }

    @Test void longRateLimitIsReturnedToSchedulerWithoutSleepingOrRetrying() {
        List<Long> waits = new ArrayList<>();
        int[] requests = {0};
        DiscordIgnClient client = new DiscordIgnClient(channel(request -> {
            requests[0]++;
            return new DiscordIgnClient.Response(429, "{\"retry_after\":90.5}", "");
        }), "secret", 2, 2, 4, 4, waits::add);
        var error = assertThrows(DiscordIgnClient.ApiException.class, () -> client.fetch("g1", "c1"));
        assertEquals(90500L, error.retryDelayMillis());
        assertEquals(1, requests[0]);
        assertTrue(waits.isEmpty());
    }

    @Test void repeatedRateLimitsStopAfterThreeAttempts() {
        List<Long> waits = new ArrayList<>();
        int[] requests = {0};
        DiscordIgnClient client = new DiscordIgnClient(channel(request -> {
            requests[0]++;
            return new DiscordIgnClient.Response(429, "{}", "0.1");
        }), "secret", 2, 2, 4, 4, waits::add);
        assertThrows(DiscordIgnClient.ApiException.class, () -> client.fetch("g1", "c1"));
        assertEquals(3, requests[0]);
        assertEquals(List.of(100L, 100L), waits);
    }

    @Test void respectsExhaustedBucketBeforeRequestingTheNextPage() throws Exception {
        List<Long> waits = new ArrayList<>();
        DiscordIgnClient client = new DiscordIgnClient(channel(request -> {
            String url = request.uri().toString();
            if (url.contains("after=")) {
                assertEquals(List.of(500L), waits);
                return ok("[]");
            }
            if (url.contains("/members")) return new DiscordIgnClient.Response(200,
                array(member("1"), member("2")), "", "0", "0.5");
            return ok(array(message("1", "1", "Alice")));
        }), "secret", 2, 2, 4, 4, waits::add);
        assertEquals(List.of("Alice"), client.fetch("g1", "c1").result().names());
        assertEquals(List.of(500L), waits);
    }

    @Test void cancellationDuringBackoffPreservesTheKnownRetryDeadline() {
        List<Long> deadlines = new ArrayList<>();
        DiscordIgnClient client = new DiscordIgnClient(channel(request ->
            new DiscordIgnClient.Response(429, "{\"retry_after\":9}", "")),
            "secret", 2, 2, 4, 4, millis -> {
                assertEquals(List.of(9000L), deadlines);
                throw new InterruptedException("disabled");
            }, deadlines::add);
        assertThrows(InterruptedException.class, () -> client.fetch("g1", "c1"));
        assertEquals(List.of(9000L), deadlines);
    }

    @Test void interruptedReadNeverMakesAnotherRequest() {
        int[] requests = {0};
        DiscordIgnClient client = new DiscordIgnClient(request -> {
            requests[0]++;
            return ok("[]");
        }, "secret", 2, 2, 4, 4);
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> client.fetch("g1", "c1")); }
        finally { Thread.interrupted(); }
        assertEquals(0, requests[0]);
    }

    private static DiscordIgnClient.Response ok(String body) {
        return new DiscordIgnClient.Response(200, body, "");
    }

    private static String array(String... values) {
        return "[" + String.join(",", values) + "]";
    }

    private static String member(String id, String... roles) {
        StringBuilder ids = new StringBuilder();
        for (String role : roles) {
            if (!ids.isEmpty()) ids.append(',');
            ids.append('"').append(role).append('"');
        }
        return "{\"user\":{\"id\":\"" + id + "\",\"bot\":false},\"pending\":false,\"roles\":[" + ids + "]}";
    }

    private static String message(String id, String author, String content) {
        return "{\"id\":\"" + id + "\",\"content\":\"" + content + "\",\"author\":{\"id\":\"" + author
            + "\",\"bot\":false}}";
    }

    private static String role(String id, String name) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"position\":0}";
    }
}
