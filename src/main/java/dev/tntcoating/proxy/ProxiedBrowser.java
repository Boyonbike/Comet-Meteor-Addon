package dev.tntcoating.proxy;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.proxies.Proxies;
import meteordevelopment.meteorclient.systems.proxies.Proxy;
import meteordevelopment.meteorclient.systems.proxies.ProxyType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Launches a second browser instance on the proxy Meteor is currently connected through. A separate
 * profile per endpoint keeps the normal browser untouched and keeps cookies from the hosted
 * checkpoint page out of the user's main session.
 */
public final class ProxiedBrowser {
    private static final File ROOT = new File(MeteorClient.FOLDER, "proxy-browser");

    private ProxiedBrowser() {}

    /** Opens the URL in the system default browser, on the normal connection. */
    public static boolean openInSystemBrowser(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;

        try {
            Util.getPlatform().openUri(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Open with no proxy, in a browser Comet can fill in. Returns false when there is no Chromium. */
    public static boolean openDirect(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;

        String chromium = findChromium();
        if (chromium == null) return false;

        File profile = new File(ROOT, "direct");
        try {
            launchChromium(chromium, url, null, profile, true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Opens the URL in a browser instance bound to the enabled proxy. */
    public static boolean openThroughProxy(String url) {
        return openThroughProxy(url, false);
    }

    /** Opens the URL in a browser instance bound to the enabled proxy. */
    public static boolean openThroughProxy(String url, boolean autofill) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;

        Proxy proxy = Proxies.get().getEnabled();
        if (proxy == null) return false;

        String host = proxy.address.get();
        int port = proxy.port.get();
        if (host == null || host.isBlank() || port <= 0) return false;

        boolean socks5 = proxy.type.get() == ProxyType.Socks5;
        File profile = new File(ROOT, (socks5 ? "s5-" : "s4-") + host + "-" + port);

        try {
            String chromium = findChromium();
            if (chromium != null) {
                launchChromium(chromium, url, (socks5 ? "socks5" : "socks4") + "://" + host + ":" + port, profile, autofill);
                return true;
            }

            String firefox = findFirefox();
            if (firefox != null) {
                writeFirefoxProfile(profile, host, port, socks5);
                launch(firefox, List.of("-profile", profile.getAbsolutePath(), "-no-remote", url));
                return true;
            }
        } catch (IOException ignored) {
            // Fall through: the caller then leaves the click to vanilla.
        }

        return false;
    }

    /** Launches Chromium in its own profile and, when asked, drives the verification form. */
    private static void launchChromium(String chromium, String url, String proxyArg, File profile, boolean autofill) throws IOException {
        List<String> arguments = new ArrayList<>();

        if (proxyArg != null) arguments.add("--proxy-server=" + proxyArg);
        arguments.add("--user-data-dir=" + profile.getAbsolutePath());
        arguments.add("--no-first-run");
        arguments.add("--no-default-browser-check");
        arguments.add("--new-window");

        if (autofill) {
            // Left over from an earlier launch; only a fresh instance has a port we can trust.
            new File(profile, "DevToolsActivePort").delete();
            arguments.add("--remote-debugging-port=0");
        }

        arguments.add(url);
        launch(chromium, arguments);

        if (autofill) {
            var user = Minecraft.getInstance().getUser();
            VerifyAutofill.schedule(profile, user == null ? null : user.getName());
        }
    }

    private static void launch(String executable, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);

        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    }

    private static void writeFirefoxProfile(File profile, String host, int port, boolean socks5) throws IOException {
        Files.createDirectories(profile.toPath());

        String prefs = """
            user_pref("network.proxy.type", 1);
            user_pref("network.proxy.socks", "%s");
            user_pref("network.proxy.socks_port", %d);
            user_pref("network.proxy.socks_version", %d);
            user_pref("network.proxy.socks_remote_dns", true);
            """.formatted(host, port, socks5 ? 5 : 4);

        Files.writeString(new File(profile, "user.js").toPath(), prefs, StandardCharsets.UTF_8);
    }

    private static String findChromium() {
        List<String> candidates = new ArrayList<>();

        if (isWindows()) {
            for (String base : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String root = System.getenv(base);
                if (root == null) continue;
                candidates.add(root + "\\Google\\Chrome\\Application\\chrome.exe");
                candidates.add(root + "\\Microsoft\\Edge\\Application\\msedge.exe");
                candidates.add(root + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe");
                candidates.add(root + "\\Chromium\\Application\\chrome.exe");
            }
        } else if (isMac()) {
            candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
            candidates.add("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser");
            candidates.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
            String home = System.getProperty("user.home");
            if (home != null) {
                candidates.add(home + "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            }
        } else {
            return findOnPath("google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
                "microsoft-edge", "brave-browser");
        }

        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) return file.getAbsolutePath();
        }
        return null;
    }

    private static String findFirefox() {
        if (isWindows()) {
            for (String base : List.of("ProgramFiles", "ProgramFiles(x86)")) {
                String root = System.getenv(base);
                if (root == null) continue;
                File file = new File(root + "\\Mozilla Firefox\\firefox.exe");
                if (file.isFile()) return file.getAbsolutePath();
            }
            return null;
        }

        if (isMac()) {
            File file = new File("/Applications/Firefox.app/Contents/MacOS/firefox");
            return file.isFile() ? file.getAbsolutePath() : null;
        }

        return findOnPath("firefox", "firefox-esr");
    }

    private static String findOnPath(String... names) {
        String path = System.getenv("PATH");
        if (path == null) return null;

        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : names) {
                File file = new File(dir, name);
                if (file.isFile() && file.canExecute()) return file.getAbsolutePath();
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
