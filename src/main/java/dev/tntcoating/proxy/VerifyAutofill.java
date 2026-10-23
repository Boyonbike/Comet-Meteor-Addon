package dev.tntcoating.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fills the hosted verification form in the browser Comet launched, leaving only the captcha to
 * the player. Chrome is started with a debugging port on its own profile directory, and the page is
 * driven over the DevTools protocol: username, the confirmation checkbox, and then the submit
 * button as soon as the captcha has been answered.
 */
public final class VerifyAutofill {
    private static final Logger LOG = LoggerFactory.getLogger("Comet");

    private static final int PORT_WAIT_MS = 20_000;
    private static final int PAGE_WAIT_MS = 20_000;
    private static final int FILL_WAIT_MS = 60_000;
    private static final int SUBMIT_WAIT_MS = 15 * 60_000;

    private VerifyAutofill() {}

    public static void schedule(File profileDir, String username) {
        if (username == null || username.isBlank()) return;

        Thread thread = new Thread(() -> run(profileDir, username), "comet-verify-autofill");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(File profileDir, String username) {
        try {
            int port = waitForPort(profileDir);
            if (port <= 0) {
                LOG.warn("Verify page autofill skipped: the browser did not open a debugging port");
                return;
            }

            String wsUrl = waitForPage(port);
            if (wsUrl == null) {
                LOG.warn("Verify page autofill skipped: no page target found");
                return;
            }

            Cdp cdp = Cdp.connect(wsUrl);
            try {
                if (!fill(cdp, username)) return;
                submitWhenReady(cdp);
            } finally {
                cdp.close();
            }
        } catch (Exception e) {
            LOG.warn("Verify page autofill failed: {}", e.toString());
        }
    }

    private static int waitForPort(File profileDir) throws InterruptedException {
        File file = new File(profileDir, "DevToolsActivePort");
        long deadline = System.currentTimeMillis() + PORT_WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (file.isFile()) {
                try {
                    String first = Files.readString(file.toPath(), StandardCharsets.UTF_8).lines().findFirst().orElse("");
                    int port = Integer.parseInt(first.trim());
                    if (port > 0) return port;
                } catch (Exception ignored) {
                    // Written halfway; try again.
                }
            }
            Thread.sleep(250);
        }
        return -1;
    }

    private static String waitForPage(int port) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadline = System.currentTimeMillis() + PAGE_WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/list"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString());

                JsonArray targets = JsonParser.parseString(response.body()).getAsJsonArray();
                for (JsonElement element : targets) {
                    JsonObject target = element.getAsJsonObject();
                    if (!"page".equals(target.get("type").getAsString())) continue;
                    if (!target.get("url").getAsString().startsWith("http")) continue;
                    return target.get("webSocketDebuggerUrl").getAsString();
                }
            } catch (Exception ignored) {
                // Browser still starting up.
            }
            Thread.sleep(300);
        }
        return null;
    }

    private static boolean fill(Cdp cdp, String username) throws Exception {
        String script = FILL_SCRIPT.formatted(jsonQuote(username));
        long deadline = System.currentTimeMillis() + FILL_WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            String result = cdp.evaluate(script, 10);

            if (result.contains("\"user\":true") && result.contains("\"box\":true")) {
                LOG.info("Verify page filled in; waiting for the captcha");
                return true;
            }
            Thread.sleep(800);
        }
        return false;
    }

    private static void submitWhenReady(Cdp cdp) throws Exception {
        long deadline = System.currentTimeMillis() + SUBMIT_WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            String result = cdp.evaluate(SUBMIT_SCRIPT, 10);
            if (result.contains("\"submitted\":true")) {
                LOG.info("Verify form submitted");
                return;
            }
            Thread.sleep(500);
        }
    }

    private static String jsonQuote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    /** Runs inside the page; returns JSON. Idempotent, so it can be repeated until both are set. */
    private static final String FILL_SCRIPT = """
        (() => {
          const USER = %s;
          const setValue = (el, value) => {
            const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
            descriptor.set.call(el, value);
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
          };
          const status = { user: false, box: true };

          const textInputs = [...document.querySelectorAll('input[type=text], input:not([type])')];
          const nameInput = textInputs.find(input => {
            const field = input.closest('[data-slot=field]');
            const label = ((field && field.innerText) || '') + ' ' + (input.name || '') + ' ' + (input.id || '');
            return /username|user name|ign\\b/i.test(label);
          }) || (textInputs.length === 1 ? textInputs[0] : null);

          if (nameInput) {
            if (nameInput.value !== USER) setValue(nameInput, USER);
            status.user = nameInput.value === USER;
          } else {
            status.user = false;
          }

          for (const box of document.querySelectorAll('input[type=checkbox]')) {
            if (box.disabled || box.checked) continue;
            // Base UI style checkboxes keep the real input visually hidden and put the click
            // handler on the [role=checkbox] element; fall back to the input if that does nothing.
            const control = box.closest('[data-slot=field]');
            const proxyControl = control && control.querySelector('[role=checkbox]');
            if (proxyControl) proxyControl.click();
            if (!box.checked) box.click();
            if (!box.checked) status.box = false;
          }

          return JSON.stringify(status);
        })()
        """;

    private static final String SUBMIT_SCRIPT = """
        (() => {
          const button = document.querySelector('button[type=submit]');
          if (button && !button.disabled) { button.click(); return JSON.stringify({ submitted: true }); }
          return JSON.stringify({ submitted: false });
        })()
        """;

    /** Minimal DevTools protocol client: one request at a time, correlated by id. */
    private static final class Cdp implements AutoCloseable {
        private final Map<Integer, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger nextId = new AtomicInteger();
        private final StringBuilder frame = new StringBuilder();
        private volatile WebSocket socket;

        static Cdp connect(String wsUrl) throws Exception {
            Cdp cdp = new Cdp();
            cdp.socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), cdp.listener())
                .get(15, TimeUnit.SECONDS);
            return cdp;
        }

        private WebSocket.Listener listener() {
            return new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    frame.append(data);
                    if (last) dispatch();
                    webSocket.request(1);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    pending.forEach((id, future) -> future.completeExceptionally(error));
                    pending.clear();
                }
            };
        }

        private void dispatch() {
            String message = frame.toString();
            frame.setLength(0);

            try {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                if (!json.has("id")) return;

                CompletableFuture<String> future = pending.remove(json.get("id").getAsInt());
                if (future == null) return;
                future.complete(json.has("result") ? json.get("result").toString() : "{\"error\":true}");
            } catch (Exception ignored) {
                // Event frame or malformed message; nothing waits on it.
            }
        }

        String evaluate(String expression, int timeoutSeconds) throws Exception {
            String params = "{\"expression\":" + jsonQuote(expression)
                + ",\"returnByValue\":true,\"awaitPromise\":true}";
            String result = call("Runtime.evaluate", params, timeoutSeconds);
            JsonObject json = JsonParser.parseString(result).getAsJsonObject();
            if (!json.has("result")) return "";

            JsonObject inner = json.getAsJsonObject("result");
            if (!inner.has("value")) return "";
            return inner.get("value").getAsString();
        }

        private String call(String method, String params, int timeoutSeconds) throws Exception {
            int id = nextId.incrementAndGet();
            CompletableFuture<String> future = new CompletableFuture<>();
            pending.put(id, future);

            socket.sendText("{\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}", true).join();
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
        }
    }
}
