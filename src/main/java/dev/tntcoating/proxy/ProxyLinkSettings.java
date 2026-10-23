package dev.tntcoating.proxy;

import meteordevelopment.meteorclient.MeteorClient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Remembers whether a clicked link should open in the proxied browser. */
public final class ProxyLinkSettings {
    private static final Path FILE = new File(MeteorClient.FOLDER, "proxy-links.txt").toPath();

    private static Boolean enabled;

    private ProxyLinkSettings() {}

    public static boolean isEnabled() {
        if (enabled == null) enabled = load();
        return enabled;
    }

    public static void set(boolean value) {
        enabled = value;
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, "enabled=" + value, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A missing config only costs the toggle its memory, not the feature.
        }
    }

    private static boolean load() {
        try {
            if (!Files.exists(FILE)) return true;
            return !Files.readString(FILE, StandardCharsets.UTF_8).trim().equalsIgnoreCase("enabled=false");
        } catch (IOException e) {
            return true;
        }
    }
}
