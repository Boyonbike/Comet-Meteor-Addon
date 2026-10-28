// Comet IGN relay: the only holder of the clan's Discord bot token. It answers the four fixed, read-only
// requests discord-ign-sync makes and nothing else, so players need only this Worker's public URL.
// Settings: BOT_TOKEN (secret), GUILD_ID and CHANNEL_ID (text).
const DISCORD = "https://discord.com/api/v10";
const PASS_HEADERS = ["Retry-After", "X-RateLimit-Remaining", "X-RateLimit-Reset-After"];
const TTL_MS = 60_000;
const MAX_CACHED = 500;
const cache = new Map(); // best effort: each Worker instance keeps its own

export default {
  async fetch(request, env) {
    if (request.method !== "GET") return text(405, "GET only.");
    const upstream = route(new URL(request.url), env);
    if (!upstream) return text(404, "Not a sync request.");

    const hit = cache.get(upstream);
    if (hit && hit.expires > Date.now()) return reply(200, hit.body, new Headers());

    const response = await fetch(upstream, {
      headers: { Authorization: `Bot ${env.BOT_TOKEN}`, "User-Agent": "Comet IGN relay" },
    });
    const body = await response.text();
    if (response.status === 200) {
      if (cache.size >= MAX_CACHED) cache.clear();
      cache.set(upstream, { body, expires: Date.now() + TTL_MS });
    }
    // A rejected bot token is the relay owner's problem; 502 tells players it is not their setup.
    return reply(response.status === 401 ? 502 : response.status, body, response.headers);
  },
};

/** The Discord URL for one of the four sync requests, or null for anything else. */
function route(url, env) {
  const routes = new Map([
    ["/channel", [`/channels/${env.CHANNEL_ID}`, []]],
    ["/roles", [`/guilds/${env.GUILD_ID}/roles`, []]],
    ["/members", [`/guilds/${env.GUILD_ID}/members`, ["limit", "after"]]],
    ["/messages", [`/channels/${env.CHANNEL_ID}/messages`, ["limit", "before"]]],
  ]);
  const match = routes.get(url.pathname);
  if (!match) return null;
  const [path, allowed] = match;
  for (const [key, value] of url.searchParams) {
    if (!allowed.includes(key) || !/^\d{1,21}$/.test(value)) return null;
  }
  return DISCORD + path + url.search;
}

function reply(status, body, upstreamHeaders) {
  const headers = new Headers({ "Content-Type": "application/json" });
  for (const name of PASS_HEADERS) {
    const value = upstreamHeaders.get(name);
    if (value) headers.set(name, value);
  }
  return new Response(body, { status, headers });
}

function text(status, message) {
  return new Response(JSON.stringify({ message }), { status, headers: { "Content-Type": "application/json" } });
}
