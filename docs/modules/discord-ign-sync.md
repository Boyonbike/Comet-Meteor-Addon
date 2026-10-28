# Discord IGN Sync

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **discord-ign-sync**. Open **Meteor → Comet → discord-ign-sync**.

Reads the Minecraft names clan members post in a Discord **#ign** channel and keeps Chat Tools in step: the [Chat Tools](chat-tools.md) clan list and, optionally, the [Buffered Auto TPY](auto-tpy.md) whitelist. Discord profiles and member lists never contain Minecraft names, so the channel is the bridge — each member posts their Java account names together in one message.

Players only paste one public **relay URL**. The relay is a small Cloudflare Worker run by the clan owner: it is the only place the Discord bot token lives, and it answers four fixed read-only requests (the #ign channel, the member list, the role list and #ign messages) for whichever guild and channel it is configured with. No player stores a token, and the relay cannot be used to send messages, create invites or read any other channel.

## Joining (players)

1. Ask the clan owner for the relay URL, for example `https://comet-ign-relay.example.workers.dev`.
2. In Chat Tools, open **Clan → Discord connection**, paste it into **Clan relay URL** and press **Save and sync**. The same value is the module's **relay-url** setting, also listed under **Settings → Discord sync**.

## Relay setup (clan owner, once)

The relay's code is [`relay/worker.js`](../../relay/worker.js).

1. Create a dedicated application at <https://discord.com/developers/applications>, open **Bot**, and reset and copy the token. Turn off **Public Bot**.
2. Under **Privileged Gateway Intents**, enable **Server Members Intent** and **Message Content Intent**. The first is required for the member list; with the second off, Discord returns messages with empty content.
3. Invite the bot with `https://discord.com/oauth2/authorize?client_id=<APP_ID>&scope=bot&permissions=0`, then in **#ign** give its role **View Channel** and **Read Message History**. Keep this channel dedicated to account-name posts; every current guild member is eligible for syncing regardless of roles.
4. Turn on Developer Mode (Discord **Settings → Advanced**), then copy the guild ID (server right-click) and the **#ign** channel ID (channel right-click).
5. In the Cloudflare dashboard, open **Workers & Pages → Create → Create Worker**, deploy the starter, then **Edit code**, replace everything with `relay/worker.js` and deploy again.
6. Under the Worker's **Settings → Variables and Secrets**, add `GUILD_ID` and `CHANNEL_ID` as text and `BOT_TOKEN` as a secret, then deploy.
7. Check `https://<worker>/channel` in a browser: it should show #ign's channel details. Give players the Worker's URL.

If the token is reset, update `BOT_TOKEN` in the Worker; players change nothing. Changing the intents takes effect immediately; the bot does not need to be re-invited.

## Roles and roster order

Every current guild member with a valid Java account post is included, regardless of their roles. Bots and pending members are excluded. The old **roles** filter has been removed, so saved filters from earlier builds no longer restrict the sync. Members without an IGN post cannot be mapped to a Minecraft account.

Saved role metadata belongs to the relay that produced it. It is used only while the connection still names that relay, and a readable sync that finds no names clears it, so role-based access (such as the Auto TPY role filter) cannot outlive its source; in both cases the clan list and whitelist stay as they are until the next successful sync.

Each synced Minecraft account shows its owner's Discord username beside the IGN, for example `Alice (@discord_owner)`. This uses the current Discord account username rather than the server nickname or display name, and all linked alts show the same owner. Usernames persist across restarts and refresh on successful syncs. Commas and slashes both split account posts, including mixed forms such as `Alice,Bob / Charlie`.

All assigned role names appear beside each synced username in Chat Tools, highest role first. The default `@everyone` role is omitted. Accounts with no assigned roles show **No role**. Each linked account gets its Discord owner's roles; alt accounts remain separate Minecraft entries. Long role lists wrap inside the player row.

The Clan page keeps **Meteor friends** first, online friends before offline friends and then A–Z. The remaining **Members by role** list follows each member's highest role, highest to lowest; members with the same highest role are A–Z and those with no assigned roles are last. Role positions come from [Discord's role hierarchy](https://docs.discord.com/developers/topics/permissions#role-object), with numeric role IDs breaking position ties. Role names and ordering persist across restarts and refresh on successful syncs.

Clan CSV imports, dropped-file imports and manual search-to-add controls have been removed. Use Discord IGN Sync to populate the Clan page. The general Players search and the separate Auto TPY whitelist/blocklist controls remain available.

## How a sync works

- **Full rescan:** every sync checks that the relay's channel and role list belong to the same guild, then reads all guild members and the **#ign** history newest-first. Reads are limited to 20,000 members and 10,000 messages. An extra one-record probe distinguishes an exact limit from truncated history; if more data remains, the entire sync is refused and both lists stay unchanged. Keep a dedicated **#ign** channel rather than using general chat.
- **Who counts:** all current guild members, regardless of roles; bots and members whose join is still pending are skipped. A post by anyone else is ignored, including one that would otherwise look like a username.
- **What counts:** a message whose whole content is one or more Java usernames, separated by commas, slashes, semicolons or new lines. For example, `Kay_Rage, immabigback`, `aagaming22 / 0r7i / FRM / fffxioxmain`, or one account per line. Names may be wrapped in backticks and the message may start with `ign:`. Names are 3–16 letters, digits or underscores. Dot-prefixed Bedrock accounts such as `.Neuro` are skipped without removing the dot; valid Java accounts alongside them still count. Spaces alone do not separate names, so conversation and announcements are not mined for usernames.
- **Latest list wins:** each member's latest valid account-list message replaces their older list; it does not accumulate every account they ever posted. Members should include all their current accounts together in the latest post. A newer Bedrock-only list suppresses older Java claims without importing the Bedrock accounts; the empty-result safeguard below still applies if no Java names remain anywhere. Invalid newer posts do not revoke an older valid list. These are self-reported names; the sync does not verify Minecraft account ownership.
- **Conflicts:** an account claimed by two members is reported in chat and skipped, while their other, unambiguous accounts still count. Repeated names within one person's post are deduplicated ignoring case.
- **Applying:** **apply-to-clan** and **apply-to-auto-tpy** (both on by default) write the synced names into Chat Tools. Only names the module actually introduced are tracked as owned. A manual name that also appears in Discord stays manual. Names the module added earlier are removed when their member leaves or changes their account list; names the sync never added stay. The module keeps its own previous set in the hidden **clan-synced** and **auto-tpy-synced** settings.
- **Safety:** a failed, malformed, truncated or empty read, any eligible post with unreadable text, or a missing Chat Tools module leaves both lists exactly as they were and reports why in chat. Empty and attachment-only posts from eligible members also block the read, since they cannot safely be distinguished from missing Message Content Intent. A result is discarded if connection settings, apply switches or either destination list changed during the read. Disabling the module interrupts the worker and invalidates queued results.

## Settings

- **relay-url**: the clan's relay link. It must be a plain `https://` URL; trailing slashes are ignored.
- **Applying** group:
  - **apply-to-clan** (default on) and **apply-to-auto-tpy** (default on): which Chat Tools lists to update.
  - **auto-sync-hours** (default 6, 0–24): while the module is on, it syncs once when you first join a server after starting the game, then this many hours apart while you play; 0 means only that first sync and the **Sync now** button. Joining another server or reconnecting does not sync again unless the last read failed. Every player's sync goes through the shared relay, so a long interval keeps a large clan within Cloudflare's free plan.
- **Sync now** button on the module screen (also works while periodic syncing is off). Enabling the module validates the settings and, if nothing has synced since the game started, runs a sync. The periodic interval is measured from the last read, so a slow request does not immediately trigger another scan.

## Limitations

- The relay is public: anyone with its URL, not only guild members, can read the member list, role names and #ign posts it serves. Repeated requests with new parameters bypass its one-minute cache and spend the bot's Discord rate limit, which can delay syncs; the bot token itself never leaves Cloudflare. Discord occasionally blocks shared Cloudflare addresses (HTTP 429, error 1015); if that persists the relay needs another host.
- Syncing is periodic, not live. Network errors and HTTP 5xx responses (including a relay 502 when its bot token is rejected) get at most two retries per request. Discord HTTP 429 delays are read from the header and JSON body; short waits happen on the worker, while waits over 30 seconds return to the scheduler. Exhausted rate-limit buckets are respected before another request. Known deadlines survive disable/re-enable. Individual requests time out after 20 seconds and a scan stops at its next request boundary after five minutes. The game thread never waits for HTTP or backoff.
- The lists are module settings shared by every server, like the clan list they update.
- The bot can only read channels it can see, so **#ign** must stay readable to it.
- An empty result never clears the lists or the whitelist, but it does clear saved Discord role metadata (and so any Auto TPY role filter) until the next successful sync; changing the guild or channel does the same. If every synced member leaves or every claim disappears, review/remove the old entries manually.
- Upgrading from the earlier ownership format preserves existing entries as manual, because that format cannot distinguish manual names from sync-added names. A one-time notice asks you to review both lists for former members. To let sync manage an existing name again, remove it from the list and sync so it is newly added.
- More than 20,000 guild members or 10,000 channel messages is refused rather than partly read; the chat message says so.

API behavior is checked against Discord’s [rate-limit documentation](https://docs.discord.com/developers/topics/rate-limits), [guild member endpoint](https://docs.discord.com/developers/resources/guild#list-guild-members), and [channel resource](https://docs.discord.com/developers/resources/channel#get-channel).
