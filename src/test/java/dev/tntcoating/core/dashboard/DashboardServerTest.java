package dev.tntcoating.core.dashboard;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DashboardServerTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void servesHealthAndRejectsBadRequests() throws Exception {
        DashboardServer server = start("", 2);
        try {
            int port = server.port();
            assertTrue(port > 0);

            HttpResponse<String> health = get(port, "/api/health");
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"schema\":2"));
            assertTrue(health.body().contains("\"ok\":true"));
            assertTrue(server.info().urls().contains("http://127.0.0.1:" + port));

            assertEquals(404, get(port, "/missing").statusCode());

            HttpRequest post = HttpRequest.newBuilder(uri(port, "/api/health"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> notAllowed = CLIENT.send(post, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, notAllowed.statusCode());
            assertEquals("GET", notAllowed.headers().firstValue("Allow").orElse(""));
        } finally {
            server.stop();
        }
        assertFalse(server.running());
        assertFalse(server.info().running());
    }

    @Test void servesPageAndAssetsWithCsp() throws Exception {
        DashboardServer server = start("", 2);
        try {
            int port = server.port();
            HttpResponse<String> page = get(port, "/");
            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("")
                .contains("default-src 'self'"));
            assertTrue(page.body().contains("Highway Fly"));
            assertEquals(200, get(port, "/index.html").statusCode());
            assertEquals(200, get(port, "/app.css").statusCode());
            assertEquals(200, get(port, "/app.js").statusCode());
        } finally {
            server.stop();
        }
    }

    @Test void healthReportsWhetherATokenIsRequired() throws Exception {
        DashboardServer open = start("", 1);
        try {
            assertTrue(get(open.port(), "/api/health").body().contains("\"auth\":false"));
        } finally {
            open.stop();
        }
        DashboardServer guarded = start("s3cret", 1);
        try {
            assertTrue(get(guarded.port(), "/api/health").body().contains("\"auth\":true"));
        } finally {
            guarded.stop();
        }
    }

    @Test void snapshotRequiresTheConfiguredToken() throws Exception {
        DashboardServer server = start("s3cret", 2);
        try {
            server.publish("{\"sequence\":1}", 1);
            int port = server.port();

            assertEquals(401, get(port, "/api/snapshot").statusCode());
            assertEquals(401, get(port, "/api/snapshot?token=wrong").statusCode());

            HttpResponse<String> header = get(port, "/api/snapshot", "s3cret");
            assertEquals(200, header.statusCode());
            assertEquals("{\"sequence\":1}", header.body());

            HttpResponse<String> query = get(port, "/api/snapshot?token=s3cret");
            assertEquals(200, query.statusCode());
            assertEquals("{\"sequence\":1}", query.body());
        } finally {
            server.stop();
        }
    }

    @Test void eventStreamSendsTheCurrentAndNextSnapshot() throws Exception {
        DashboardServer server = start("", 2);
        ExecutorService readerPool = Executors.newSingleThreadExecutor();
        try {
            server.publish("{\"sequence\":1}", 1);
            int port = server.port();
            HttpResponse<InputStream> response = CLIENT.send(
                HttpRequest.newBuilder(uri(port, "/api/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));

            try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                Future<String> first = readerPool.submit(() -> readData(lines));
                assertEquals("{\"sequence\":1}", first.get(5, TimeUnit.SECONDS));

                server.publish("{\"sequence\":2}", 2);
                Future<String> second = readerPool.submit(() -> readData(lines));
                assertEquals("{\"sequence\":2}", second.get(5, TimeUnit.SECONDS));
            }
        } finally {
            readerPool.shutdownNow();
            server.stop();
        }
    }

    @Test void extraStreamsAreRejectedAtTheClientLimit() throws Exception {
        DashboardServer server = start("", 1);
        ExecutorService readerPool = Executors.newSingleThreadExecutor();
        try {
            server.publish("{\"sequence\":1}", 1);
            int port = server.port();
            HttpResponse<InputStream> first = CLIENT.send(
                HttpRequest.newBuilder(uri(port, "/api/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, first.statusCode());
            try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(first.body(), StandardCharsets.UTF_8))) {
                Future<String> welcome = readerPool.submit(() -> readData(lines));
                assertEquals("{\"sequence\":1}", welcome.get(5, TimeUnit.SECONDS));

                assertEquals(503, get(port, "/api/events").statusCode());
            }
        } finally {
            readerPool.shutdownNow();
            server.stop();
        }
    }

    @Test void olderSnapshotsNeverReplaceNewerOnes() throws Exception {
        DashboardServer server = start("", 1);
        try {
            server.publish("{\"sequence\":5}", 5);
            server.publish("{\"sequence\":4}", 4);
            assertEquals("{\"sequence\":5}", server.latestJson());
        } finally {
            server.stop();
        }
    }

    private static DashboardServer start(String token, int maxClients) throws Exception {
        DashboardServer server = new DashboardServer(new DashboardServer.Config("127.0.0.1", 0, token, maxClients,
            500, false, "test"), DashboardLog.NONE);
        server.start();
        return server;
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return get(port, path, null);
    }

    private static HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(port, path)).GET()
            .timeout(Duration.ofSeconds(5));
        if (token != null) request.header(DashboardServer.TOKEN_HEADER, token);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String readData(BufferedReader reader) throws Exception {
        for (int index = 0; index < 20; index++) {
            String line = reader.readLine();
            if (line == null) return null;
            if (line.startsWith("data: ")) return line.substring(6);
        }
        return null;
    }
}
