package dev.tntcoating.proxy;

import meteordevelopment.meteorclient.systems.proxies.ProxyType;

/** One entry from the free proxy mirror, with the metadata used to rank it. */
public record FreeProxyCandidate(
    ProxyType type,
    String address,
    int port,
    String countryCode,
    String isp,
    double uptimePercent,
    double latencyMs
) {
    /** Hosting providers, which share datacenter IP reputation. Anything else is treated as residential. */
    private static final String[] HOSTING = {
        "digitalocean", "hetzner", "ovh", "amazon", "aws", "google", "microsoft", "azure", "oracle",
        "alibaba", "tencent", "vultr", "linode", "contabo", "choopa", "leaseweb", "performive",
        "datawagon", "net2atlanta", "secure internet", "psychz", "hostinger", "namecheap", "colocation",
        "m247", "poneytelecom", "scaleway", "ionos", "godaddy", "akamai", "cloudflare", "fastly",
        "netcup", "serverius", "worldstream", "fdcservers", "hivelocity", "quadranet", "interserver",
        "colocrossing", "sharktech", "datacamp", "host", "vps", "server", "cloud", "datacenter"
    };

    public boolean residential() {
        String lower = isp.toLowerCase();
        for (String hosting : HOSTING) {
            if (lower.contains(hosting)) return false;
        }
        return true;
    }

    public String label() {
        return address + ":" + port;
    }
}
