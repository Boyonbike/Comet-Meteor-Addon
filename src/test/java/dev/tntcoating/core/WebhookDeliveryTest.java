package dev.tntcoating.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebhookDeliveryTest {
    private static final String URL = "https://discord.com/api/webhooks/1/token";
    private static HttpRequest request(String token) {
        return HttpRequest.newBuilder(URI.create(URL + token + "?wait=true"))
            .POST(HttpRequest.BodyPublishers.ofString("message")).build();
    }
    static class Harness {
        long now;
        long sequence;
        record Task(long at, long sequence, Runnable run) implements Comparable<Task> {
            public int compareTo(Task other) {
                int time = Long.compare(at, other.at);
                return time != 0 ? time : Long.compare(sequence, other.sequence);
            }
        }
        final PriorityQueue<Task> tasks = new PriorityQueue<>();
        final List<HttpRequest> posts = new ArrayList<>();
        final List<CompletableFuture<WebhookDelivery.Response>> replies = new ArrayList<>();
        final WebhookDelivery delivery = new WebhookDelivery(req -> {
            posts.add(req);
            var reply = new CompletableFuture<WebhookDelivery.Response>();
            replies.add(reply);
            return reply;
        }, () -> now, (run, delay) -> tasks.add(new Task(now + delay, sequence++, run)),
            3, 100, 100_000, 3, 2);
        void advance(long millis) {
            long until = now + millis;
            while (!tasks.isEmpty() && tasks.peek().at <= until) {
                Task task = tasks.remove();
                now = task.at;
                task.run.run();
            }
            now = until;
        }
        void reply(int index, int status, String retryAfter) {
            replies.get(index).complete(new WebhookDelivery.Response(status, retryAfter, ""));
            advance(0);
        }
    }
    @Test void asynchronousAndSerialIncludingRetries() {
        Harness h = new Harness();
        var first = h.delivery.submit(request("a"));
        var second = h.delivery.submit(request("b"));
        assertTrue(h.posts.isEmpty());
        h.advance(0);
        assertEquals(1, h.posts.size());
        h.reply(0, 503, null);
        assertFalse(first.isDone());
        h.advance(999);
        assertEquals(1, h.posts.size());
        h.advance(1);
        assertEquals(2, h.posts.size());
        assertEquals(h.posts.get(0).uri(), h.posts.get(1).uri());
        h.reply(1, 200, null);
        assertEquals(WebhookDelivery.Outcome.DELIVERED, first.join().outcome());
        assertEquals(3, h.posts.size());
        h.reply(2, 200, null);
        assertEquals(WebhookDelivery.Outcome.DELIVERED, second.join().outcome());
    }
    @Test void rateLimitWaitsForRetryAfterAndExpiresInsteadOfSendingEarly() {
        Harness h = new Harness();
        var result = h.delivery.submit(request("a"));
        h.advance(0);
        h.reply(0, 429, "200");
        h.advance(100_000);
        assertEquals(1, h.posts.size());
        assertEquals(WebhookDelivery.Outcome.DISCARDED, result.join().outcome());
    }
    @Test void fractionalRateLimitAndNetworkFailuresRetryThenExhaust() {
        Harness h = new Harness();
        var result = h.delivery.submit(request("a"));
        h.advance(0);
        h.reply(0, 429, "1.5");
        h.advance(1499);
        assertEquals(1, h.posts.size());
        h.advance(1);
        h.replies.get(1).completeExceptionally(new IOException("timeout"));
        h.advance(2000);
        assertEquals(3, h.posts.size());
        h.reply(2, 500, null);
        assertEquals(WebhookDelivery.Outcome.DISCARDED, result.join().outcome());
    }
    @Test void invalidEndpointsAreSuppressedWithoutBlockingOtherEndpoints() {
        for (int status : new int[] {401, 403, 404}) {
            Harness h = new Harness();
            var first = h.delivery.submit(request("a"));
            var queued = h.delivery.submit(request("a"));
            var other = h.delivery.submit(request("b"));
            h.advance(0);
            h.reply(0, status, null);
            assertEquals(WebhookDelivery.Outcome.REJECTED, first.join().outcome());
            assertEquals(WebhookDelivery.Outcome.SUPPRESSED, queued.join().outcome());
            assertEquals(WebhookDelivery.Outcome.SUPPRESSED, h.delivery.submit(request("a")).join().outcome());
            assertEquals(2, h.posts.size());
            h.reply(1, 200, null);
            assertEquals(WebhookDelivery.Outcome.DELIVERED, other.join().outcome());
        }
    }
    @Test void queueAndPayloadBoundsDiscardExcessWithoutPosting() {
        Harness h = new Harness();
        h.delivery.submit(request("a"));
        h.delivery.submit(request("b"));
        h.delivery.submit(request("c"));
        assertEquals(WebhookDelivery.Outcome.DISCARDED, h.delivery.submit(request("d")).join().outcome());
        var large = HttpRequest.newBuilder(URI.create(URL)).POST(HttpRequest.BodyPublishers.ofString("x".repeat(101))).build();
        assertEquals(WebhookDelivery.Outcome.DISCARDED, new Harness().delivery.submit(large).join().outcome());
    }
    @Test void queuedMessagesExpireAndLaterMessagesRespectEndpointRateLimit() {
        Harness h = new Harness();
        h.delivery.submit(request("a"));
        var waiting = h.delivery.submit(request("a"));
        h.advance(0);
        h.reply(0, 429, "200");
        h.advance(100_000);
        assertEquals(WebhookDelivery.Outcome.DISCARDED, waiting.join().outcome());
        var next = h.delivery.submit(request("a"));
        h.advance(99_999);
        assertEquals(1, h.posts.size(), "Dropping an expired item must not reset Discord's limit.");
        assertFalse(next.isDone());
        h.advance(1);
        assertEquals(WebhookDelivery.Outcome.DISCARDED, next.join().outcome());
    }
    @Test void permanentSuppressionStorageIsBoundedAndOtherClientErrorsDoNotRetry() {
        Harness h = new Harness();
        for (String token : new String[] {"a", "b", "c"}) {
            h.delivery.submit(request(token));
            h.advance(0);
            h.reply(h.posts.size() - 1, 404, null);
        }
        var oldest = h.delivery.submit(request("a"));
        h.advance(0);
        assertEquals(4, h.posts.size(), "The oldest entry is evicted once the bounded cache fills.");
        h.reply(3, 400, null);
        assertEquals(WebhookDelivery.Outcome.REJECTED, oldest.join().outcome());
        h.advance(100_000);
        assertEquals(4, h.posts.size());
    }
    @Test void rateLimitUsesJsonRetryDelayWhenHeaderIsMissing() {
        Harness h = new Harness();
        h.delivery.submit(request("a"));
        h.advance(0);
        h.replies.get(0).complete(new WebhookDelivery.Response(429, null, "{\"retry_after\":2.25}"));
        h.advance(2249);
        assertEquals(1, h.posts.size());
        h.advance(1);
        assertEquals(2, h.posts.size());
    }
    @Test void bothSendersCanUseConfirmedDiscordEndpoint() {
        assertEquals(URL + "?wait=true", DiscordWebhook.confirmedUri(" " + URL + " ").toString());
        assertThrows(IllegalArgumentException.class, () -> DiscordWebhook.confirmedUri("https://example.com/"));
    }
    @Test void globalRateLimitAppliesToOtherEndpointsAfterTheOriginalMessageExpires() {
        Harness h = new Harness();
        h.delivery.submit(request("a"));
        h.advance(0);
        h.replies.get(0).complete(new WebhookDelivery.Response(429, null,
            "{\"retry_after\":200,\"global\":true}"));
        h.advance(100_000);
        var other = h.delivery.submit(request("b"));
        h.advance(99_999);
        assertEquals(1,h.posts.size(),"A global limit must also hold unrelated webhook endpoints.");
        assertFalse(other.isDone());
        h.advance(1);
        assertEquals(WebhookDelivery.Outcome.DISCARDED,other.join().outcome());
    }
    @Test void monotonicClockMayHaveANegativeOrigin() {
        Harness h = new Harness();
        h.now = -1_000_000;
        h.delivery.submit(request("a"));
        h.advance(0);
        assertEquals(1,h.posts.size());
        h.reply(0,429,"2");
        h.advance(1_999);
        assertEquals(1,h.posts.size());
        h.advance(1);
        assertEquals(2,h.posts.size());
    }
}
