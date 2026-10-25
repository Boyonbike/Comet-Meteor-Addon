/*
 * Comet highway dashboard HTTP server.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core.dashboard;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small read-only HTTP server that serves the dashboard page and live snapshots on the local network. The game thread
 * only calls {@link #publish(String, long)}; every socket read and write happens on daemon server threads.
 */
public final class DashboardServer {
    public static final String TOKEN_HEADER = "X-Highway-Token";
    private static final String TOKEN_PARAMETER = "token";
    private static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
        + "script-src 'self'; connect-src 'self'";

    /** Immutable server configuration. A blank token means the dashboard is open to the whole network. */
    public record Config(String bindAddress, int port, String accessToken, int maxClients, int keepAliveMillis,
                         boolean debug, String version) {
        public Config {
            bindAddress = bindAddress == null || bindAddress.isBlank() ? "0.0.0.0" : bindAddress.trim();
            accessToken = accessToken == null ? "" : accessToken;
            version = version == null || version.isBlank() ? "dev" : version;
            if (port < 0 || port > 65535) throw new IllegalArgumentException("Port out of range: " + port);
            if (maxClients < 1) throw new IllegalArgumentException("maxClients must be at least one.");
            if (keepAliveMillis < 50) throw new IllegalArgumentException("keepAliveMillis must be at least 50.");
        }
    }

    /** Snapshot of the server state for the module widget and the served page. */
    public record Info(boolean running, String bindAddress, int port, List<String> urls, int clients,
                       long startedAtMillis, String error, String version) {
        public Info {
            urls = List.copyOf(urls);
        }
    }

    private record Published(long sequence, String json, byte[] bytes) {}

    private final Config config;
    private final DashboardLog log;
    private final Object monitor = new Object();
    private final Set<HttpExchange> streams = Collections.synchronizedSet(new LinkedHashSet<>());
    private final ExecutorService executor;

    private volatile HttpServer server;
    private volatile Published published;
    private volatile boolean running;
    private volatile long startedAtMillis;

    public DashboardServer(Config config, DashboardLog log) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.executor = Executors.newFixedThreadPool(Math.max(6, config.maxClients() + 4), threadFactory());
    }

    /**
     * Starts the server and returns the bound port.
     *
     * @throws IOException if the address cannot be resolved or the port cannot be bound
     */
    public synchronized int start() throws IOException {
        if (running) return port();
        InetAddress address = InetAddress.getByName(config.bindAddress().equals("*") ? "0.0.0.0"
            : config.bindAddress());
        HttpServer created = HttpServer.create(new InetSocketAddress(address, config.port()), 0);
        created.createContext("/", this::handle);
        created.setExecutor(executor);
        created.start();
        server = created;
        startedAtMillis = System.currentTimeMillis();
        running = true;
        log.info("Highway dashboard listening on " + address.getHostAddress() + ":" + port());
        return port();
    }

    /** Publishes a new snapshot. Called on the game thread; this only stores a string and wakes stream threads. */
    public void publish(String json, long sequence) {
        Objects.requireNonNull(json, "json");
        Published current = published;
        if (current != null && sequence <= current.sequence()) return;
        published = new Published(sequence, json, json.getBytes(StandardCharsets.UTF_8));
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    /** The most recent snapshot JSON, or null before the first publish. */
    public String latestJson() {
        Published current = published;
        return current == null ? null : current.json();
    }

    public int port() {
        HttpServer current = server;
        return current == null ? config.port() : current.getAddress().getPort();
    }

    public int clients() {
        return streams.size();
    }

    public boolean running() {
        return running;
    }

    public Info info() {
        HttpServer current = server;
        boolean up = running && current != null;
        int boundPort = port();
        List<String> urls = boundPort < 1 ? List.of() : DashboardUrls.resolve(config.bindAddress(), boundPort);
        return new Info(up, config.bindAddress(), boundPort, urls, clients(),
            up ? startedAtMillis : 0L, null, config.version());
    }

    /** Stops accepting connections, ends live streams and releases the port. The instance cannot be restarted. */
    public synchronized void stop() {
        HttpServer current = server;
        if (!running && current == null) return;
        running = false;
        server = null;
        synchronized (monitor) {
            monitor.notifyAll();
        }
        if (current != null) {
            try {
                current.stop(0);
            } catch (RuntimeException error) {
                log.warn("Dashboard server did not stop cleanly", error);
            }
        }
        for (HttpExchange exchange : List.copyOf(streams)) {
            try {
                exchange.close();
            } catch (RuntimeException ignored) {
                // The stream thread notices the closure and removes itself.
            }
        }
        streams.clear();
        executor.shutdownNow();
        try {
            executor.awaitTermination(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        log.info("Highway dashboard stopped");
    }

    private void handle(HttpExchange exchange) {
        try {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("X-Frame-Options", "DENY");

            if (!"GET".equals(exchange.getRequestMethod())) {
                headers.set("Allow", "GET");
                sendJson(exchange, 405, errorJson("method not allowed"));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/", "/index.html" -> sendAsset(exchange, "text/html; charset=utf-8", DashboardAssets.html(), true);
                case "/app.css" -> sendAsset(exchange, "text/css; charset=utf-8", DashboardAssets.css(), false);
                case "/app.js" -> sendAsset(exchange, "text/javascript; charset=utf-8", DashboardAssets.js(), false);
                case "/favicon.ico" -> exchange.sendResponseHeaders(204, -1);
                case "/api/health" -> sendJson(exchange, 200, healthJson());
                case "/api/snapshot" -> {
                    if (!authorized(exchange)) {
                        unauthorized(exchange);
                        return;
                    }
                    sendSnapshot(exchange);
                }
                case "/api/events" -> {
                    if (!authorized(exchange)) {
                        unauthorized(exchange);
                        return;
                    }
                    stream(exchange);
                }
                default -> sendJson(exchange, 404, errorJson("not found"));
            }
        } catch (IOException error) {
            log.warn("Dashboard response failed for " + exchange.getRequestURI().getPath(), error);
        } catch (RuntimeException error) {
            log.warn("Dashboard request failed for " + exchange.getRequestURI().getPath(), error);
            try {
                sendJson(exchange, 500, errorJson("internal error"));
            } catch (IOException ignored) {
                // The client is already gone.
            }
        } finally {
            exchange.close();
        }
    }

    private void stream(HttpExchange exchange) throws IOException {
        if (clients() >= config.maxClients()) {
            sendJson(exchange, 503, errorJson("too many dashboard clients"));
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        OutputStream out = exchange.getResponseBody();
        streams.add(exchange);
        long sent = 0;
        try {
            write(out, "retry: 2000\n\n");
            Published current = published;
            if (current != null) {
                write(out, eventFrame(current));
                sent = current.sequence();
            }
            out.flush();
            while (running) {
                Published latest;
                synchronized (monitor) {
                    if (published != null && published.sequence() > sent) {
                        latest = published;
                    } else {
                        try {
                            monitor.wait(config.keepAliveMillis());
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        latest = published;
                    }
                }
                if (!running) return;
                if (latest != null && latest.sequence() > sent) {
                    write(out, eventFrame(latest));
                    sent = latest.sequence();
                } else {
                    write(out, ": keepalive\n\n");
                }
                out.flush();
            }
        } catch (IOException closed) {
            // The browser went away; the finally block unregisters this stream.
        } finally {
            streams.remove(exchange);
        }
    }

    private void sendSnapshot(HttpExchange exchange) throws IOException {
        Published current = published;
        if (current == null) {
            sendJson(exchange, 503, "{\"waiting\":true,\"schema\":" + DashboardSnapshot.SCHEMA + "}");
            return;
        }
        sendBytes(exchange, 200, "application/json; charset=utf-8", current.bytes());
    }

    private String healthJson() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("ok", running);
        health.put("schema", DashboardSnapshot.SCHEMA);
        health.put("auth", !config.accessToken().isBlank());
        health.put("clients", clients());
        health.put("uptimeMillis", running ? System.currentTimeMillis() - startedAtMillis : 0L);
        health.put("version", config.version());
        return DashboardJson.toJson(health);
    }

    private void sendAsset(HttpExchange exchange, String contentType, String body, boolean html)
        throws IOException {
        if (html) exchange.getResponseHeaders().set("Content-Security-Policy", CSP);
        sendBytes(exchange, 200, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        sendBytes(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes)
        throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void unauthorized(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Token realm=\"Comet highway dashboard\"");
        sendJson(exchange, 401, errorJson("unauthorized"));
    }

    private boolean authorized(HttpExchange exchange) {
        String expected = config.accessToken();
        if (expected.isBlank()) return true;
        String supplied = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (supplied == null) supplied = queryToken(exchange.getRequestURI().getRawQuery());
        if (supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryToken(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) continue;
            String key = decode(pair.substring(0, separator));
            if (!TOKEN_PARAMETER.equals(key)) continue;
            return decode(pair.substring(separator + 1));
        }
        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String errorJson(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return DashboardJson.toJson(body);
    }

    private static String eventFrame(Published published) {
        return "event: snapshot\ndata: " + published.json() + "\n\n";
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "Comet dashboard " + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
