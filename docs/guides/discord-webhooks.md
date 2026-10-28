# Discord reports and webhooks

[Documentation index](../README.md) · [Project README](../../README.md)

Two one-press modules post to Discord through channel webhooks. Bind each to a key: a press posts once, then the module switches itself off again.

- **call-to-arms** pings the clan (@everyone by default) to join a live grief. It shows your name and face, coordinates, dimension, the dubs in your loaded chunks, a `/tpa <your name>` code block to copy into chat, the **call-to-action** text and a screenshot.
- **stash-report** logs the stash around you with the clan's stash bot and pings nobody. It shows your Discord account and the dubs, always, plus your name and face, dimension and a screenshot. Like `/submit-stash`, it never includes coordinates. The bot credits the stash to your Discord account, counts it in `/stats`, `/mystats` and `/leaderboard`, and reacts to the report once it is logged. A call to arms is never logged as a stash, even when both use the same webhook.

Dubs are counted the same way as **dub-counter**, at the moment you press, whether or not Dub Counter is on.

Settings both share:

- **webhook-url**: in Discord, open the channel's settings → **Integrations** → **Webhooks**, create one and use **Copy Webhook URL**. Each module has its own, so the call to arms can go to a ping channel and the stash report to the channel the stash bot reads. Only Discord webhook URLs are accepted, so a typo can't send your coordinates anywhere else. Anyone with the URL can post in that channel, and Meteor saves it in its config folder, so don't share your config.
- **Sending** group: **cooldown-seconds** (default 30) ignores presses for that long after one is sent, so a double press doesn't post twice; a failed post clears it. **confirm** (default on) asks first: **Send call to arms?** says who will be pinged, and **Report stash?** says how many dubs will be reported. While the call to arms' **show-coordinates** is on, its prompt always appears, even with **confirm** off, and asks "Are you sure?" with the exact coordinates about to be posted. Missing settings, the cooldown and a post still sending are reported before the prompt opens.
- **Contents** group: a **show-** switch for each optional part, all on by default except the call to arms' **show-coordinates**, which starts off. With **show-name** off, the call to arms' ping line reads "join now!" without your name; its `/tpa` still contains it unless **show-tpa** is off too.

Call to Arms only:

- **ping** (default `everyone`): `everyone` pings every member who can see the channel, `here` only those online right now, and a role's ID pings just that role: turn on Developer Mode (Discord **Settings** → **Advanced**), right-click the role and choose **Copy Role ID**, or paste the `<@&id>` mention. Leave it blank to post without a ping. If a role shows but nobody gets notified, turn on **Allow anyone to @mention this role** for it (**Server Settings** → **Roles**). With a role or no ping, an `@everyone` typed into the call to action is shown but does not ping.
- **call-to-action**: the line under the title.

Stash Report only:

- **discord-user-id** (required): your Discord user ID, shown as your clickable name without pinging you. Turn on Developer Mode, then click your name and choose **Copy User ID**; pasting your `<@id>` mention also works. Nothing is sent until it is set, or while no chests are loaded around you.
- Keep **show-screenshot** on: the stash bot only logs reports with a screenshot, as `/submit-stash` needs an image. Two reports of one stash are logged as two entries, as two `/submit-stash` runs would be; an admin removes the extra with `/delete-stash`.

**Screenshot:** with **show-screenshot** on, the module waits for you to close any open menu (the confirmation prompt closes by itself), hides the GUI for one frame exactly as F1 does, photographs that frame and puts the GUI back. That hides the hotbar, chat, hand, crosshair, Meteor's HUD and tracers. Outlines drawn into the world, such as ESP boxes, still show. Screens wider than 2560 pixels are scaled down to stay inside Discord's upload limit. If a menu stays open for 10 seconds, or the picture can't be taken, the message goes without it and chat says so.

Chat says whether each post went out, or why Discord refused it (a deleted webhook, rate limiting, no connection). Sending happens in the background, so the game doesn't pause while it waits. Meteor's toggled on/off messages are off for both modules by default.

For reading Minecraft names from a Discord **#ign** channel into Chat Tools, see [Discord IGN Sync](../modules/discord-ign-sync.md).
