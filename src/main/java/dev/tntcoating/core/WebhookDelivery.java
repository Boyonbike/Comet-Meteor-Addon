/*
 * Comet asynchronous webhook outbox.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package dev.tntcoating.core;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** A bounded, process-local outbox. Retries keep their place; no network work runs on the caller's thread. */
public final class WebhookDelivery {
    public enum Outcome { DELIVERED, REJECTED, SUPPRESSED, DISCARDED }
    public record Result(Outcome outcome, int status) {}
    record Response(int status, String retryAfter, String body) {}
    @FunctionalInterface interface Transport { CompletableFuture<Response> send(HttpRequest request); }
    @FunctionalInterface interface Scheduler { void schedule(Runnable task, long delayMillis); }

    private static final class Shared {
        static final WebhookDelivery INSTANCE = create();
        private static WebhookDelivery create() {
            var executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Comet webhook delivery");
                thread.setDaemon(true);
                return thread;
            });
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            return new WebhookDelivery(request -> http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new Response(response.statusCode(),
                    response.headers().firstValue("Retry-After").orElse(null), response.body())),
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),
                (task, delay) -> executor.schedule(task, delay, TimeUnit.MILLISECONDS),
                32, 8 * 1024 * 1024, 120_000, 5, 128);
        }
    }
    public static CompletableFuture<Result> send(HttpRequest request) { return Shared.INSTANCE.submit(request); }

    private static final class Pending {
        final HttpRequest request;
        final long expires;
        final CompletableFuture<Result> result = new CompletableFuture<>();
        int attempts;
        Pending(HttpRequest request, long expires) { this.request = request; this.expires = expires; }
    }
    private final Transport transport;
    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final int capacity, maxBytes, maxAttempts, endpointCapacity;
    private final long retention;
    private long globalRateUntil = Long.MIN_VALUE;
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final LinkedHashMap<URI, Integer> rejected = new LinkedHashMap<>();
    private final LinkedHashMap<URI, Long> rateLimits = new LinkedHashMap<>();

    WebhookDelivery(Transport transport, LongSupplier clock, Scheduler scheduler, int capacity, int maxBytes,
                    long retention, int maxAttempts, int endpointCapacity) {
        this.transport = transport;
        this.clock = clock;
        this.scheduler = scheduler;
        this.capacity = capacity;
        this.maxBytes = maxBytes;
        this.retention = retention;
        this.maxAttempts = maxAttempts;
        this.endpointCapacity = endpointCapacity;
    }

    synchronized CompletableFuture<Result> submit(HttpRequest request) {
        Integer bad = rejected.get(request.uri());
        if (bad != null) return CompletableFuture.completedFuture(new Result(Outcome.SUPPRESSED, bad));
        long bytes = request.bodyPublisher().map(HttpRequest.BodyPublisher::contentLength).orElse(0L);
        if (queue.size() >= capacity || bytes < 0 || bytes > maxBytes) {
            return CompletableFuture.completedFuture(new Result(Outcome.DISCARDED, 0));
        }
        Pending pending = new Pending(request, addDelay(clock.getAsLong(),retention));
        queue.add(pending);
        if (queue.size() == 1) scheduler.schedule(() -> attempt(pending), 0);
        return pending.result;
    }

    private synchronized void attempt(Pending pending) {
        if (queue.peek() != pending) return;
        long now = clock.getAsLong();
        rateLimits.entrySet().removeIf(entry -> entry.getValue() <= now);
        Integer bad = rejected.get(pending.request.uri());
        if (bad != null) { finish(pending, Outcome.SUPPRESSED, bad); return; }
        if (now >= pending.expires) { finish(pending, Outcome.DISCARDED, 0); return; }
        long limited = Math.max(globalRateUntil,rateLimits.getOrDefault(pending.request.uri(),Long.MIN_VALUE));
        if (limited > now) {
            scheduler.schedule(() -> attempt(pending), Math.min(limited, pending.expires) - now);
            return;
        }
        pending.attempts++;
        try {
            transport.send(pending.request).whenComplete((response, failure) ->
                scheduler.schedule(() -> completed(pending, response, failure), 0));
        } catch (RuntimeException failure) {
            scheduler.schedule(() -> completed(pending, null, failure), 0);
        }
    }

    private synchronized void completed(Pending pending, Response response, Throwable failure) {
        if (queue.peek() != pending) return;
        int status = response == null ? 0 : response.status();
        if (failure == null && status / 100 == 2) { finish(pending, Outcome.DELIVERED, status); return; }
        if (status == 401 || status == 403 || status == 404) {
            remember(rejected, pending.request.uri(), status);
            finish(pending, Outcome.REJECTED, status);
            return;
        }
        if (failure != null || status == 429 || status >= 500 && status < 600) {
            long now = clock.getAsLong();
            long delay = Math.min(30_000, 1000L << Math.min(10, pending.attempts - 1));
            if (status == 429) {
                delay = Math.max(delay, retryAfter(response));
                long until = addDelay(now,delay);
                remember(rateLimits, pending.request.uri(), until);
                if (globallyLimited(response)) globalRateUntil = Math.max(globalRateUntil,until);
            }
            if (pending.attempts >= maxAttempts || now >= pending.expires) {
                finish(pending, Outcome.DISCARDED, status);
            } else scheduler.schedule(() -> attempt(pending), Math.min(delay, pending.expires - now));
            return;
        }
        finish(pending, Outcome.REJECTED, status);
    }

    private static long addDelay(long now, long delay) {
        return now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
    }

    private static boolean globallyLimited(Response response) {
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject().get("global").getAsBoolean();
        } catch (RuntimeException ignored) { return false; }
    }

    private long retryAfter(Response response) {
        long header = seconds(response.retryAfter());
        if (header < 0 && response.retryAfter() != null) {
            try {
                header = Math.max(0, ZonedDateTime.parse(response.retryAfter(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - System.currentTimeMillis());
            } catch (RuntimeException ignored) { /* Fall back to the JSON body/backoff. */ }
        }
        long body = -1;
        try {
            body = seconds(JsonParser.parseString(response.body()).getAsJsonObject().get("retry_after").getAsString());
        } catch (RuntimeException ignored) { /* A missing/malformed field must not stop the outbox. */ }
        return Math.max(0, Math.max(header, body));
    }
    private static long seconds(String value) {
        try {
            double seconds = Double.parseDouble(value);
            return Double.isFinite(seconds) && seconds >= 0 ? (long) Math.ceil(seconds * 1000) : -1;
        } catch (RuntimeException ignored) { return -1; }
    }
    private <V> void remember(LinkedHashMap<URI, V> map, URI endpoint, V value) {
        map.put(endpoint, value);
        while (map.size() > endpointCapacity) map.remove(map.keySet().iterator().next());
    }
    private void finish(Pending pending, Outcome outcome, int status) {
        queue.remove();
        if (!queue.isEmpty()) {
            Pending next = queue.peek();
            scheduler.schedule(() -> attempt(next), 0);
        }
        pending.result.complete(new Result(outcome, status));
    }
}
