# Discord IGN Sync

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **discord-ign-sync**. Open **Meteor → Comet → discord-ign-sync**.

Reads the Minecraft names clan members post in a Discord **#ign** channel and keeps Chat Tools in step: the [Chat Tools](chat-tools.md) clan list and, optionally, the [Buffered Auto TPY](auto-tpy.md) whitelist. Discord profiles and member lists never contain Minecraft names, so the channel is the bridge — each member posts their Java account names together in one message.

The module talks to Discord's REST API directly. It hosts no bot process and needs no server or middleman: pressing **Sync now** (or the periodic sync) makes the same read requests a bot would.

## Discord setup

1. Create a dedicated application at <https://discord.com/developers/applications> and open **Bot**. Do not reuse the clan's admin bot: the token is stored in Meteor's config on every client that syncs.
2. Reset the token and copy it.
3. Under **Privileged Gateway Intents**, enable **Server Members Intent** and **Message Content Intent**. The first is required even for the member-list request this module makes; with the second off, Discord returns messages with empty content.
4. Invite the bot with `https://discord.com/oauth2/authorize?client_id=<APP_ID>&scope=bot&permissions=0`.
5. In **#ign**, give the bot's role **View Channel** and **Read Message History**, and nothing else. Keep this channel dedicated to account-name posts; every current guild member is eligible for syncing regardless of roles.
6. Turn on Developer Mode (Discord **Settings → Advanced**), then copy the guild ID (server right-click), the **#ign** channel ID (channel right-click).
7. In Chat Tools, open **Clan → Discord connection** and fill in the server ID, **#ign** channel ID and bot token, then press **Save and sync**. The same values are the module's **bot-token**, **guild-id** and **ign-channel-id** settings, also listed under **Settings → Discord sync**.

Changing the intents takes effect for the token immediately; the bot does not need to be re-invited. If the token is reset, paste the new one.

## Roles and roster order

Every current guild member with a valid Java account post is included, regardless of their roles. Bots and pending members are excluded. The old **roles** filter has been removed, so saved filters from earlier builds no longer restrict the sync. Members without an IGN post cannot be mapped to a Minecraft account.

Saved role metadata belongs to the guild and channel that produced it. It is used only while the connection still names that guild and channel, and a readable sync that finds no names clears it, so role-based access (such as the Auto TPY role filter) cannot outlive its source; in both cases the clan list and whitelist stay as they are until the next successful sync.

Each synced Minecraft account shows its owner's Discord username beside the IGN, for example `Alice (@discord_owner)`. This uses the current Discord account username rather than the server nickname or display name, and all linked alts show the same owner. Usernames persist across restarts and refresh on successful syncs. Commas and slashes both split account posts, including mixed forms such as `Alice,Bob / Charlie`.

All assigned role names appear beside each synced username in Chat Tools, highest role first. The default `@everyone` role is omitted. Accounts with no assigned roles show **No role**. Each linked account gets its Discord owner's roles; alt accounts remain separate Minecraft entries. Long role lists wrap inside the player row.

The Clan page keeps **Meteor friends** first, online friends before offline friends and then A–Z. The remaining **Members by role** list follows each member's highest role, highest to lowest; members with the same highest role are A–Z and those with no assigned roles are last. Role positions come from [Discord's role hierarchy](https://docs.discord.com/developers/topics/permissions#role-object), with numeric role IDs breaking position ties. Role names and ordering persist across restarts and refresh on successful syncs.

Clan CSV imports, dropped-file imports and manual search-to-add controls have been removed. Use Discord IGN Sync to populate the Clan page. The general Players search and the separate Auto TPY whitelist/blocklist controls remain available.

## How a sync works

- **Full rescan:** every sync verifies that the channel belongs to the configured guild, then reads all guild members and the **#ign** history newest-first. Reads are limited to 20,000 members and 10,000 messages. An extra one-record probe distinguishes an exact limit from truncated history; if more data remains, the entire sync is refused and both lists stay unchanged. Keep a dedicated **#ign** channel rather than using general chat.
- **Who counts:** all current guild members, regardless of roles; bots and members whose join is still pending are skipped. A post by anyone else is ignored, including one that would otherwise look like a username.
- **What counts:** a message whose whole content is one or more Java usernames, separated by commas, slashes, semicolons or new lines. For example, `Kay_Rage, immabigback`, `aagaming22 / 0r7i / FRM / fffxioxmain`, or one account per line. Names may be wrapped in backticks and the message may start with `ign:`. Names are 3–16 letters, digits or underscores. Dot-prefixed Bedrock accounts such as `.Neuro` are skipped without removing the dot; valid Java accounts alongside them still count. Spaces alone do not separate names, so conversation and announcements are not mined for usernames.
- **Latest list wins:** each member's latest valid account-list message replaces their older list; it does not accumulate every account they ever posted. Members should include all their current accounts together in the latest post. A newer Bedrock-only list suppresses older Java claims without importing the Bedrock accounts; the empty-result safeguard below still applies if no Java names remain anywhere. Invalid newer posts do not revoke an older valid list. These are self-reported names; the sync does not verify Minecraft account ownership.
- **Conflicts:** an account claimed by two members is reported in chat and skipped, while their other, unambiguous accounts still count. Repeated names within one person's post are deduplicated ignoring case.
- **Applying:** **apply-to-clan** and **apply-to-auto-tpy** (both on by default) write the synced names into Chat Tools. Only names the module actually introduced are tracked as owned. A manual name that also appears in Discord stays manual. Names the module added earlier are removed when their member leaves or changes their account list; names the sync never added stay. The module keeps its own previous set in the hidden **clan-synced** and **auto-tpy-synced** settings.
- **Safety:** a failed, malformed, truncated or empty read, any eligible post with unreadable text, or a missing Chat Tools module leaves both lists exactly as they were and reports why in chat. Empty and attachment-only posts from eligible members also block the read, since they cannot safely be distinguished from missing Message Content Intent. A result is discarded if connection settings, apply switches or either destination list changed during the read. Disabling the module interrupts the worker and invalidates queued results.

## Settings

- **bot-token**: the dedicated application's bot token. A leading `bot ` is ignored.
- **guild-id**, **ign-channel-id**: the server and channel IDs from Developer Mode.
- **Applying** group:
  - **apply-to-clan** (default on) and **apply-to-auto-tpy** (default on): which Chat Tools lists to update.
  - **auto-sync-minutes** (default 5, 0–120): periodic sync while the module is on; 0 means only the **Sync now** button runs a sync.
- **Sync now** button on the module screen (also works while periodic syncing is off); enabling the module also validates the settings and runs a sync. The periodic interval is measured from completion, so a slow request does not immediately trigger another scan. A zero interval still permits the initial sync on activation.

## Limitations

- The token is saved in Meteor's config folder. Don't share configs; a leaked token can read the guild and post as the bot. Reset it in the Developer Portal and paste the new one.
- Syncing is periodic, not live. Network errors and HTTP 5xx responses get at most two retries per request. Discord HTTP 429 delays are read from the header and JSON body; short waits happen on the worker, while waits over 30 seconds return to the scheduler. Exhausted rate-limit buckets are respected before another request. Known deadlines survive disable/re-enable. Individual requests time out after 20 seconds and a scan stops at its next request boundary after five minutes. The game thread never waits for HTTP or backoff.
- The lists are module settings shared by every server, like the clan list they update.
- The bot can only read channels it can see, so **#ign** must stay readable to it.
- An empty result never clears the lists or the whitelist, but it does clear saved Discord role metadata (and so any Auto TPY role filter) until the next successful sync; changing the guild or channel does the same. If every synced member leaves or every claim disappears, review/remove the old entries manually.
- Upgrading from the earlier ownership format preserves existing entries as manual, because that format cannot distinguish manual names from sync-added names. A one-time notice asks you to review both lists for former members. To let sync manage an existing name again, remove it from the list and sync so it is newly added.
- More than 20,000 guild members or 10,000 channel messages is refused rather than partly read; the chat message says so.

API behavior is checked against Discord’s [rate-limit documentation](https://docs.discord.com/developers/topics/rate-limits), [guild member endpoint](https://docs.discord.com/developers/resources/guild#list-guild-members), and [channel resource](https://docs.discord.com/developers/resources/channel#get-channel).
