package dev.tntcoating.proxy;

import meteordevelopment.meteorclient.systems.proxies.ProxyType;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the free proxy mirror. The mirror is refreshed hourly and, unlike the API, carries the
 * measured uptime and the ISP of every entry, which is what makes ranking possible.
 */
public final class FreeProxySource {
    private static final String BASE =
        "https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/protocols/";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private FreeProxySource() {}

    public static List<FreeProxyCandidate> fetch(int limit, double minUptime, double maxLatency, String country)
        throws IOException, InterruptedException {

        List<FreeProxyCandidate> all = new ArrayList<>();
        all.addAll(fetchProtocol("socks5", ProxyType.Socks5));
        all.addAll(fetchProtocol("socks4", ProxyType.Socks4));

        return all.stream()
            .filter(candidate -> candidate.uptimePercent() >= minUptime)
            .filter(candidate -> candidate.latencyMs() > 0 && candidate.latencyMs() <= maxLatency)
            .filter(candidate -> country.isBlank() || candidate.countryCode().equalsIgnoreCase(country))
            .sorted(Comparator
                .comparingInt((FreeProxyCandidate c) -> c.residential() ? 1 : 0).reversed()
                .thenComparing(Comparator.comparingDouble(FreeProxyCandidate::uptimePercent).reversed())
                .thenComparingDouble(FreeProxyCandidate::latencyMs))
            .limit(limit)
            .toList();
    }

    private static List<FreeProxyCandidate> fetchProtocol(String protocol, ProxyType type)
        throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + protocol + "/data.csv"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Comet-Meteor-Addon")
            .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + protocol);
        }

        List<FreeProxyCandidate> out = new ArrayList<>();
        for (String line : response.body().split("\n")) {
            FreeProxyCandidate candidate = parse(line, type);
            if (candidate != null) out.add(candidate);
        }
        return out;
    }

    /** Header: protocol,ip,port,country,country_code,city,anonymity,ssl,uptime_percent,asn,isp,latency_ms,last_checked */
    private static FreeProxyCandidate parse(String line, ProxyType type) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("protocol,")) return null;

        String[] parts = trimmed.split(",", -1);
        if (parts.length < 12) return null;

        try {
            return new FreeProxyCandidate(
                type,
                parts[1].trim(),
                Integer.parseInt(parts[2].trim()),
                parts[4].trim(),
                parts[10].trim(),
                Double.parseDouble(parts[8].trim()),
                Double.parseDouble(parts[11].trim())
            );
        } catch (NumberFormatException e) {
            // ISP names containing commas shift the columns; drop the row rather than guess.
            return null;
        }
    }
}
