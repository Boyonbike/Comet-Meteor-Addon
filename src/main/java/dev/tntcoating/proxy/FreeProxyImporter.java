package dev.tntcoating.proxy;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.screens.ProxiesScreen;
import meteordevelopment.meteorclient.systems.proxies.Proxies;
import meteordevelopment.meteorclient.systems.proxies.Proxy;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;

import java.util.List;

/** Fetches candidates off the render thread and adds them to Meteor's own proxy list. */
public final class FreeProxyImporter {
    private static final int LIMIT = 24;
    private static final double MIN_UPTIME = 50;
    private static final double MAX_LATENCY = 3000;

    private static boolean fetching;

    private FreeProxyImporter() {}

    public static void fetchAndImport() {
        if (fetching) return;
        fetching = true;

        MeteorExecutor.execute(() -> {
            List<FreeProxyCandidate> found;
            try {
                found = FreeProxySource.fetch(LIMIT, MIN_UPTIME, MAX_LATENCY, "");
            } catch (Exception e) {
                fetching = false;
                MeteorClient.mc.execute(() -> ChatUtils.errorPrefix("Comet", "Could not fetch free proxies: %s", String.valueOf(e)));
                return;
            }

            MeteorClient.mc.execute(() -> {
                fetching = false;
                apply(found);
            });
        });
    }

    private static void apply(List<FreeProxyCandidate> found) {
        Proxies proxies = Proxies.get();
        int added = 0;

        for (FreeProxyCandidate candidate : found) {
            Proxy proxy = new Proxy.Builder()
                .type(candidate.type())
                .address(candidate.address())
                .port(candidate.port())
                .name(candidate.label())
                .build();

            if (proxies.add(proxy)) added++;
        }

        if (added > 0) proxies.checkProxies(true);

        ChatUtils.infoPrefix("Comet", "Added %d free proxies%s.", added,
            added > 0 ? ", checking them now" : "");

        if (MeteorClient.mc.gui.screen() instanceof ProxiesScreen screen) {
            screen.taskAfterRender = screen::reload;
        }
    }
}
