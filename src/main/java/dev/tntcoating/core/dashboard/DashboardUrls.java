/*
 * Comet highway dashboard LAN address discovery.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.tntcoating.core.dashboard;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves the URLs a phone or another PC on the same network can use to reach the dashboard. */
public final class DashboardUrls {
    private DashboardUrls() {}

    /** Returns human-facing URLs for a bind address, always including a loopback fallback and never empty. */
    public static List<String> resolve(String bindAddress, int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port out of range: " + port);
        String bind = bindAddress == null ? "" : bindAddress.trim();
        Set<String> urls = new LinkedHashSet<>();
        if (isWildcard(bind)) {
            for (String address : interfaceAddresses()) urls.add(url(address, port));
            urls.add(url("127.0.0.1", port));
        } else {
            String host = bind.isEmpty() ? "127.0.0.1" : bind;
            urls.add(url(host, port));
            if (!host.equals("127.0.0.1") && !host.equals("localhost") && !host.equals("::1")) {
                urls.add(url("127.0.0.1", port));
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(urls));
    }

    private static boolean isWildcard(String bind) {
        return bind.isEmpty() || bind.equals("*") || bind.equals("0.0.0.0") || bind.equals("::")
            || bind.equals("0:0:0:0:0:0:0:0");
    }

    /** Public, non-loopback IPv4 interface addresses, in enumeration order. */
    private static List<String> interfaceAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()) {
                        continue;
                    }
                    addresses.add(address.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
            // Fall through to the loopback fallback; the page is still reachable from this machine.
        }
        return addresses;
    }

    private static String url(String host, int port) {
        String formatted = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + formatted + ":" + port;
    }
}
