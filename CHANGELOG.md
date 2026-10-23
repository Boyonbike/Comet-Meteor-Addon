# Changelog

## Unreleased

- **Baritone Elytra adjustable flight:** the new **Elytra Fly** section powers the whole flight without needing or consuming fireworks: takeoff assistance, automatic boost during travel, a slower final approach and controlled descent through touchdown. Baritone chooses the route and landing spot. By default, boost speeds and ramps match Adaptive Elytra Fly; turn **use-adaptive-settings** off to adjust horizontal and vertical speed caps and ramp steps separately. Terrain checks, setback recovery and durability protection remain. Comet skips firework restocking and temporarily bypasses Baritone's rocket reserve and ordinary boost settings; forced rocket use is blocked before consumption. Prior Baritone settings are restored when this mode is disabled or the world is left. Meteor ElytraFly is paused and restored around the flight. Turn **elytra-fly** off for vanilla firework flight. Flight is limited to 340 b/s without split moves. In-game route tracking remains to be verified.

## 0.25.20

- **Free proxies and proxy-bound links:** Meteor's **Proxies** screen gains **Fetch Free Proxies**, which lists up to 24 SOCKS4/SOCKS5 proxies from ProxyScrape's public list, filtered to at least 50% uptime and 1-3000 ms latency, with residential ISPs first, and adds them to the proxy list for testing. **Proxy links** (on by default) opens links clicked in chat or menus in a separate Chrome/Chromium or Firefox profile bound to the enabled proxy; profiles are kept under `meteor-client/proxy-browser/` and the toggle in `meteor-client/proxy-links.txt`. Disconnect screens that contain a URL gain **Open Verify Link** and **Open Via Proxy** buttons, and Chrome can fill the username, confirmation box and submit button of the verification form, leaving the captcha to you. These tools are subject to the server's rules; Comet does not create accounts, solve captchas or bypass bans. See [Proxy tools](README.md#proxy-tools).
- **Licensing and attribution:** the combined work now has a single GPL-3.0-only `LICENSE`; original Comet code remains available under CC0 1.0 in `LICENSE-CC0`, and the NoTextureRotations adaptation keeps the Apache-2.0 text in `LICENSE-NoTextureRotations`. Removed the duplicate GPL copies, updated `THIRD_PARTY_NOTICES.md` with the component map and copyright, and made the JAR and source archive bundle all three texts. Replaced the BepHax-derived Angle Calculator, Anti Spawn Set and No Hurt Cam module/mixin with original Comet implementations, so no BepHax source or assets remain. Documented VolytraFly (CC0-1.0), Quiettee Utils, THM Addons, DEVILS, NoTextureRotations, Majkey's DubCounter (with permission), Meteor, Baritone and Xaero.
- **Release housekeeping:** README now starts with install steps, badges, a supported-branch table and a contents list, and documents the proxy tools. Added `CONTRIBUTING.md` and `.gitattributes`, stopped tracking `logs/latest.log` and `.vscode/`, shortened and linked the mod metadata, and made CI build tagged releases as well as branches.

## 0.25.16

- **Anti Coords:** the new **anti-coords** module makes your coordinates as hard as possible to work out from what you show. It hides the texture rotations and model offsets that blocks pick from their own position, adapted from rfresh2's NoTextureRotations (Apache-2.0). **Fixed** mode (default) gives every block the same rotation and offset; **Keyed** keeps vanilla's variety by hashing positions under a secret key. Unlike the original, doors, beds and tall plants are covered too. With **clean-screenshots** (default on), the screenshot key saves only the world, leaving out every HUD, F3, chat, menu, Meteor overlay and other mods' overlays. See [Anti Coords](README.md#anti-coords).
- **1.21.11 port:** Meteor build 82 draws its 2D layer, and flushes vanilla's HUD, while the HUD is built, so on 1.21.11 the clean screenshot is taken as soon as the world is drawn, before any of the GUI is built.

## 1.21.11 port of 0.25.15

- Bring the 1.21.11 build up to 0.25.15: Auto Wasp Devils, the Allay modules, Adaptive Elytra Fly's **steer-server-glide** and the removal of Home Packet Log, as described under 0.25.13 to 0.25.15 below.
- Adapt Auto Wasp's chest-slot swaps, movement output and server-glide packet reads, and Allay Shulker Killer's reach check, to the 1.21.11 APIs. Allay Farm right-clicks an allay as the 1.21.11 client does: at the aimed point first, then a plain interaction if that does nothing.
- Include `vendor/` in the source archive, so it builds against Meteor build 82.
- All 819 automated tests and 224 offscreen shader pixel checks pass against Meteor 1.21.11-82, and the new mixin targets are checked against the pinned dependencies.

## 0.25.15

- **Auto Wasp flies at the server's limits:** pursuit used DEVILS' fixed 2 blocks per tick (40 b/s). It now boosts on VolytraFly's curve, from 60 b/s at once towards 300 b/s, up to the new **horizontal-max-bps** (default 240, up to 300). A vanilla server only checks how far each move packet travels, and pursuit takes 6b6t's limits to be the same, as Adaptive Elytra Fly's boost does. 240 b/s is the fastest that is never set back for distance, even when lag delivers two packets in one tick; up to 300 b/s is set back only when that happens. Speeds are now set in blocks per second like Adaptive Elytra Fly: **horizontal-max-bps** replaces **horizontal-speed** (set it to 40 for roughly the old pace), and **vertical-max-bps** (default and maximum 60) replaces **vertical-speed**, with climbs and descents boosting from 30 b/s as in VolytraFly. The new **3d-max-bps** (default 180) caps total 3D speed while climbing or descending and for half a second after, so climbs and dives fly slower than level flight; the boost ramps are held to it meanwhile and speed up again from it afterwards. With the default **ramp-step** (0.3), pursuit reaches 240 b/s in about 3.5 seconds.
- **Auto Wasp wastes no speed:** the server never checks slowing down, so pursuit flies at the ramp's speed and lands on each waypoint, or the target, on the final tick instead of braking early. Slowing down there keeps the ramp's speed; as in VolytraFly, only a real collision or a setback costs it. While the target is in plain sight, pursuit flies straight at it instead of at a waypoint up to 1.6 seconds old. DEVILS' local avoidance keeps the sideways push it was tuned for at 2 blocks per tick instead of scaling it up with speed.
- **Auto Wasp rides out setbacks:** a setback rewinds both ramps by **setback-rewind-ticks** (15, under 1% near top speed) and holds there for **setback-hold-ticks** (1), instead of asking for full speed again at once. Two setbacks to the same spot switch to vanilla gliding for **pinned-glide-ticks** (10) until the server accepts a move. A correction more than 64 blocks away is a teleport and starts a new route.
- **Auto Wasp server-glide guard:** like Adaptive Elytra Fly, Auto Wasp now follows the glide a vanilla server simulates from each reported position. It keeps walls and unloaded chunks out of that glide's reach (**server-glide-guard**), and reports a heading away from nearby walls (**steer-server-glide**), so flying close to walls no longer risks fly-into-wall damage from the hidden glide.
- **Auto Wasp takeoff and gear:** wings open like a second press of Jump near the top of the jump, and a refused open is retried after half a second (a tenth in liquid) instead of every 4 ticks (every tick in liquid). After an elytra goes on, the new glide is left to vanilla physics for a quarter of a second. An elytra with 1 durability left counts as missing and is swapped for a usable one, instead of retrying takeoff forever. **chest-swap-hysteresis** (1.5 blocks) stops swapping back and forth when the target sits at the edge of a chest-swap range.
- **Auto Wasp prediction:** **predict-movement** now leads the target by its movement over your ping plus the three ticks other players' movement takes to show (at most 8 ticks), measured from positions rather than the last velocity the server sent, and matches the target's speed when flying straight at it.
- **Auto Wasp path search:** clearance and floor/ceiling lookups are cached per block, so nearby samples share them, and each route search judges every node once and stops after 4 ms with its best partial route, so a long search cannot stall the tick.
- **Auto Wasp is self-contained:** its boost ramps, server limits, swap delay, takeoff, gear choice and server-glide code are Auto Wasp's own copies, so tuning Adaptive Elytra Fly cannot change pursuit and the other way round. Only Comet's shared inventory locks, which keep it from clicking during Auto Tool or Auto Weapon loans, are still shared. The Nether flight cap is unchanged: nothing above y 123 in the Nether counts as open.
- **Adaptive Elytra Fly steer-server-glide:** near a wall you face, Adaptive Elytra Fly reports a level heading away from it, so the server's own glide turns away and you can fly right up to the wall without wall damage, instead of being held about 1.6 blocks back by **server-glide-guard**. On by default; your camera does not move.

## 0.25.14

- Removed **Home Packet Log** and **Dig Probe** from Comet. Their source, tests, module-specific helpers and usage guide are preserved in `archived-modules/packet-diagnostics-2026-10-22/`, with verified checksums. Shared logging and interaction helpers remain available to other modules.

## 0.25.13

- **Auto Wasp Devils:** ported DEVILS’ elytra pursuit, A* routing, obstacle avoidance, braking and stuck recovery into Comet. Added local chestplate/elytra swapping, navigation guards, visible GPLv3 settings credits, bundled licence/provenance and a matching source distribution. Meteor’s original Auto Wasp links to the port.

## Meteor 1.21.11 build 82 compatibility

- Compile and test the 1.21.11 port against Meteor build 82, and lower the runtime minimum from build 86 to build 82. All module upgrade hooks fit build 82.
- Preserve the archived build 82 dependency in `vendor/maven` with its source, checksum and upstream commit documented in `vendor/README.md`, since upstream Maven no longer hosts it. Meteor remains a separate player-installed dependency.

## 1.21.11 port of 0.25.11

- Retarget Minecraft 1.21.11 and Java 21, using Loom Remap with official Mojang mappings. Pin Meteor build 86 and matching Xaero Minimap and Baritone dependencies.
- Adapt inventory clicks, movement packets, Meteor interfaces, chat rendering, shulker icons and storage shader pipelines to the 1.21.11 APIs. Preserve the 0.25.11 modules and settings.
- Update Fast Use and tab-list local captures, and verify every configured mixin against the pinned dependencies. Adapt GPU and registry test fixtures to 1.21.11.

## 0.25.12

- **Dig Probe:** **dig-probe** sends a Start, Abort, Stop or Start-then-Stop dig packet at the block you look at, or at set coordinates, and records the server's response: acknowledgement, block changes, break progress, level events and block entity data. Responses show in chat with their delay and are saved to `meteor-client/comet/dig-probes/`. See [Dig Probe](README.md#dig-probe).
- **Allay modules:** **allay-farm** duplicates dancing allays with amethyst shards, including ones holding a shulker. It uses a shard only on allays ready to duplicate, so none is handed over as a held item. It also gives each empty-handed allay a shulker box. With **keep-population** on (default) it never gives out more shulkers than allays it saw bred, so you never lose more allays than you breed. **allay-shulker-killer** kills allays holding shulker boxes so they drop them. It skips allays that take no damage from three hits, since an allay can't be hurt by the player that gave it its item. With **no-sweep** on (default) it jumps and lands critical hits while falling, so a sword never sweeps the allays around the target. See [Allays](README.md#allays).
- **Chat Tools remembers recent players:** the recent players on Overview and Players are now saved per server and account (hidden **recent-players** setting, up to 100), so they are still there after rejoining a server or restarting the game. Conversations themselves still last for the current connection.
- **Chat Tools shows your sent DMs:** 6b6t echoes a whisper as `You whisper to [unknown player head] Name: …`, with a player-head sprite before the name, so the echo was never recognised and your own messages never appeared in the conversation. They now show as outgoing bubbles. `From`/`To` formats accept the sprite too.
- **Whisper queue:** 6b6t refuses a whisper sent too soon after the last one (`Please wait 2 seconds before sending another whisper!`). DMs from a Chat Tools conversation, and `/msg`, `/w`, `/tell`, `/whisper`, `/r` or `/reply` typed in its composer, now wait in a queue instead. A refused whisper is sent again as soon as the wait is over (up to three tries) and the refusal is hidden, and the reported delay then spaces out later whispers so they are not refused at all. Queued messages show as dimmed bubbles until the server echoes them. Whispers typed in normal chat are still sent straight away, but count towards the delay.
- **Whisper delay no longer reads as a teleport cooldown:** within five seconds of `/home` or `/tpa`, that refusal used to replace the teleport's countdown with a 2-second cooldown.
- **Go to hotspot on Overview:** a **Go to hotspot** button beside **Reply** sends `/hotspot`. It is shown when the server offers the command.
- **No GUI Move in Chat Tools:** with Meteor's **gui-move** on, typing in the Chat Tools window walked you around and pressing Space jumped. The window is now exempt like normal chat, and movement keys held when it opens are released.
- **Smoother Adaptive Elytra Fly vertical reversal:** switching straight between Jump and Sneak no longer flips direction in one tick. The old climb or descent slows evenly over the new **vertical-reversal-ticks** setting (2 by default, 0 restores the instant turn), then the new direction speeds up from a standstill as before: a 21 b/s climb goes 14, 7, then a 10 b/s descent, and a 60 b/s Sprint climb goes 40, 20, then a 30 b/s boosted descent, with the descent boost waiting at its start speed until then. It is never slower than releasing the key. The **vertical-reversal** recorded flight is re-recorded for this; the other recorded flights are unchanged.
- **Crystal Aura crash guard:** Meteor's **crystal-aura** skips its tick handlers, including the late rotation one, while there is no player or world, for example after another listener disconnects you during the same tick.

## 0.25.11

- **Release refresh:** packages the current modules for Minecraft 26.1.2, including Speedmine, Call to Arms and Stash Report, plus Chat Tools clan management and friend/clan name and tab-row highlighting.
- **Discord reporting:** Stash Report never includes coordinates and pings nobody; Call to Arms leaves coordinates off by default and always asks for confirmation when sharing them. These behaviours are carried forward from 0.25.10.
- **Metadata:** the addon description now includes clan management and both Discord reporting modules. No gameplay code changed in this release.

## 0.25.10

- **Stash reports never carry coordinates:** like `/submit-stash`, a stash report no longer has a coordinates field or a **show-coordinates** switch, so coordinates never reach the stash bot's channel, its #basehunting-finds post or its records. Two reports of one stash are logged as two entries; an admin removes the extra with `/delete-stash`. The call to arms keeps **show-coordinates**, off by default and always confirmed.

## 0.25.9

- **Coordinates off by default:** **show-coordinates** now starts off in both **call-to-arms** and **stash-report**. While it is on, pressing the key always opens the confirmation prompt, even with **confirm** off, and the prompt asks "Are you sure?" with the exact coordinates about to be posted. Existing setups switch to off as well, because Meteor only saves settings changed from their default and on was the old default. Without coordinates the stash bot can't recognise repeat reports of one stash, so each is logged separately.

## 0.25.8

- **Stash Report works with the stash bot as already set up:** the stash report's footer starts with `Comet grief alert` again, the text the deployed stash bot looks for, so the bot needs no change. A call to arms still says `Comet call to arms` and is never logged as a stash.

## 0.25.7

- **Grief Alert split into Call to Arms and Stash Report:** **call-to-arms** is the old ping (@everyone by default, call to action, `/tpa`, coordinates, dubs, screenshot) without the Discord account. The new **stash-report** logs the stash with the clan's stash bot and pings nobody: it always carries your Discord account (**discord-user-id**, required) and the dub count, plus your name, coordinates, dimension and screenshot, and refuses to send while no chests are loaded. Each has its own **webhook-url**; **cooldown-seconds** and **confirm** moved to a **Sending** group. The stash bot logs only messages with the stash report's footer, so a call to arms is never counted as a stash. **grief-alert** is gone: re-enter your webhook URL, ping and other settings in the new modules.

## 0.25.6

- **Grief Alert for the stash bot:** the new required **discord-user-id** setting puts your Discord account in every alert (shown as your name, never pinged), so the clan's stash bot can credit you. The alert won't send until it is set.
- **Grief Alert screenshot:** new **show-screenshot** (Contents, on by default) attaches a screenshot of your view, taken in F1 mode without the HUD, chat, hand or Meteor's HUD, shown as the embed's image. The alert waits for open menus to close, scales screens wider than 2560 pixels down, and goes without the screenshot if it can't be taken.

## 0.25.5

- **Grief Alert confirmation:** new **confirm** setting (on by default) opens a **Send grief alert?** prompt before posting, saying who will be pinged and that your coordinates will be shared. **Yes** sends, **No** cancels. Turn it off to send on the key press as before.

## 0.25.4

- **Grief Alert contents:** a new **Contents** group switches each part of the alert on or off: **show-name**, **show-face**, **show-coordinates**, **show-dimension**, **show-dubs**, **show-tpa**, **show-call-to-action** and **show-server**, all on by default so alerts look the same until you change one. Dubs are only counted when **show-dubs** is on.

## 0.25.3

- **Grief Alert pings @everyone:** the **ping** setting replaces **ping-role-id** and defaults to `everyone`. It also takes `here` (members online now), a role's ID or `<@&id>` mention, or blank for no ping. Re-enter a role ID you had set.

## 0.25.2

- **Grief Alert:** the new **grief-alert** module posts a live grief alert to a Discord webhook when you press its key, then turns itself off. It pings your grief role (**ping-role-id**) and shows an embed with your in-game name and face, coordinates, dimension, the dubs in your loaded chunks, a copyable `/tpa <name>` and your **call-to-action** text. Only Discord webhook URLs are accepted, only the configured role can be pinged, and **cooldown-seconds** (30) stops a double press from pinging twice. See [Grief Alert](README.md#grief-alert).
- **Speedmine:** ported THM Addons' existing miner to Minecraft 26.1.2 under Comet, including queue/double break, rebreak, prediction, silent swapping, air mining and optional auto targeting. Server-supported bedrock mining uses normal mining actions plus hand swings. Phase candidates require actual hitbox overlap, bedrock is processed independently of the ordinary-block budget, and eligible bedrock targets retain progress. Existing self-protection remains enabled; debug output explains skipped phase targets. THM clan and packet-limiter dependencies are removed; upstream credits and GPL terms are packaged. Live 6b6t behaviour has not been verified.

- **Chat Tools clan tab:** a new **Clan** tab lists your clan members: Meteor friends first, then online members, then everyone else. Add members by typing names (comma-separated lists work), with **Import CSV**, or by dropping a `.csv` file on the window; a header row such as `Username` or `IGN` picks the right column of a spreadsheet export. Each row has Message, TP, more actions and a remove button, and the Players actions menu gains **Add to clan** / **Remove from clan**. Clan members' names are coloured in chat and in the tab list, each with its own switch and colour (**Clan** settings group, or **Settings → Clan highlighting** in the window). The tab list colour works alongside Meteor's Better Tab.
- **Chat Tools friend colours:** Meteor friends get the same treatment: **friend-chat-color** and **friend-tab-color** (General group, or **Settings → Name colours**), with **highlight-friends** for chat and the new **highlight-friends-tab** for the tab list. Friends are now coloured in the tab list without Better Tab. A player who is both a friend and a clan member gets the friend colour. Chat used to follow Meteor's global friend colour; it now uses **friend-chat-color**, which starts at Meteor's default green, so re-pick it if you had changed Meteor's.
- **Tab list row highlights:** friends' and clan members' tab list rows can also be highlighted with a bar behind them, in their own colours separate from the name colours: **highlight-friend-rows** / **friend-row-color** (General) and **highlight-clan-rows** / **clan-row-color** (Clan), both off by default and also under **Settings → Name colours**. The colour's alpha sets how strong the bar is.

## 0.25.1

- **Scaffold packet limits:** the first setback log showed where Scaffold's rubber-banding came from. With **air-place** and **blocks-per-tick** 10, Scaffold placed 50–60 blocks a second and Meteor sent 30–45 of them as separate rotation packets. That is about three times the normal movement packet rate, and each burst ended in an 8–16 block rollback. Scaffold now places at most one rotated block per tick, nearest first. The new **extra-rotation-packets** setting (off) restores the old behaviour. The new **setback-pause** setting (10 ticks) places only the block under you right after a setback, aimed at the setbacks that followed within 4–6 ticks. **sticky-switch** now switches back after a second idle or when Scaffold turns off, and is on by default, saving two slot packets per placement. The setback log adds the radius and range settings, per-tick rotated placements, `extraRotationPacketsLastSecond` and `pauseTicksLeft`.
- **Scaffold setback log:** Meteor's Scaffold gains **Comet → log-setbacks** (on by default). Each server setback while Scaffold is on is written to `meteor-client/comet/scaffold-setback-logs`, one file per Scaffold session, at most 4 MB. A record holds the correction and the move the server rejected (speed, vertical speed, on-ground flag, sent-rotation snap, placements and tower state that tick), whether you stood on a block Scaffold had just placed, ping, input and Scaffold's settings, plus a one-second per-tick timeline. Use it to match rubber-banding to towering, rotations or ghost blocks.
- **Adaptive Elytra Fly instant vertical reversal:** switching straight from Jump to Sneak, or back, now turns around on the very next tick instead of first braking through zero (about 6 ticks, 0.3 s, with the defaults). The old direction's momentum is dropped and the new direction speeds up from a standstill: cruise goes from a 21 b/s climb to a 10 b/s descent and reaches 21 b/s within 3 ticks, and Sprint goes from a 60 b/s climb straight to a 30 b/s boosted descent, then follows the vertical boost curve. Releasing Jump or Sneak still brakes smoothly. Pressing Jump while sinking on Meteor's glide also climbs one tick sooner. A new sixteenth recorded flight, **vertical-reversal**, locks this in; the other recorded flights are unchanged apart from that first climbing tick in **vertical-boost**.
- **Meteor-style HUD elements:** every Comet HUD element (**adaptive-elytra-fly-engine**, **player-speed**, **speed-3d**, **tp-home-countdown** and **dub-counter**) now looks like Meteor's own text elements: shadowed text with no background or side bar, each label in the first of the HUD's **text-colors** and its value in the second, so they match FPS, TPS and Speed and follow your colour choices. Their settings match Meteor's elements too: **shadow**, **alignment** and **border** in General, **custom-scale** and **scale** under Scale, and **background** (off) and **background-color** under Background. The **text-color** and **accent-color** settings are gone. Elements placed beside Meteor's may need a nudge in the HUD editor to line up again, since they lost their 6-pixel inner padding.

## 0.25.0

- **Removed Phase:** the **phase** module (Pearl, Clip, Normal, Sand and Climb modes) and its push-out hook are gone.
- **Removed the Trajectories upgrade:** Comet no longer changes Meteor's **Trajectories**. It is stock Meteor again, is no longer listed under Comet, and its **Comet** settings (**optimize-trajectories**, **remote-trajectory-range**, **trajectory-steps-per-frame**) are gone.
- **Removed Elytra Replace:** the **elytra-replace** module and its Elytra Fly dropdown link are gone. Meteor's Elytra Fly has its own **elytra-replace** and **replace-durability** options for swaps while it is on. **Elytra Log** now counts a spare only when it has more than Elytra Log's own **durability** left, which is what it already did whenever Elytra Replace was off.
- **Consistent settings menus:** every Comet module description now reads the same way ("Shows…", "Coats…"), toggle descriptions read as instructions, and keybind descriptions start with "Key that…". Fast Storage ESP colours describe what they colour instead of repeating the setting name, and **opened-color** explains that zero opacity keeps the normal colour. Server Pacing is now the last group in Block Coat, and Performance the last in Fast Storage ESP, after the feature groups. No setting or group was renamed, so saved configurations load unchanged.
- **No Hurt Cam:** removed its **disable-hurt-cam** setting, which only repeated the module toggle. The module removes the hurt camera whenever it is on.
- **Docs:** README and mod description updated for the removed modules; dropped the stale XaeroPlus dependency note.

## 0.24.6

- **Adaptive Elytra Fly maneuver mode:** moving horizontally, climbing or descending and turning all at once now holds total 3D speed to **maneuver-max-bps** (default 200 b/s; **maneuver-mode**, on by default), to cut rubber-banding in combined maneuvers. The cap lasts half a second after you stop turning, then boost speeds up again from it along its normal curve. Climbing or turning on its own is never capped, and all fifteen recorded scripted flights reproduce exactly.
- **3D speed HUD:** the **adaptive-elytra-fly-engine** element's speed line now includes the engine's 3D output, and a new **speed-3d** element shows measured 3D speed and when the maneuver cap is active.

## 0.24.5

- **Adaptive Elytra Fly speed caps in blocks per second:** **cruise-max-bps** (41.2), **vertical-cruise-max-bps** (21), **boost-max-bps** (300) and **vertical-boost-max-bps** (60) replace **cruise-max-speed**, **sprint-max-speed**, **vertical-max-speed**, **vertical-cruise-max-speed**, **vertical-sprint-max-speed**, **boost-max-speed** and **boost-start-speed**. They are new settings, so re-enter any value you had changed. The boost curve is now fixed at VolytraFly's 2.999 to 14.999 blocks/tick (60 to 300 b/s; overdrive still raises its top). A boost cap stops that curve where you set it instead of reshaping it: boost accelerates exactly as at 300 b/s and holds the cap, where lowering **boost-max-speed** used to make the whole ramp taper towards it. Braking is planned from the curve's top, so a cap never changes it. Default flight is unchanged: all fifteen recorded scripted flights reproduce exactly.

## 0.24.1

- **Adaptive Elytra Fly setback storms:** the first in-game setback logs showed most setbacks arriving in storms, one every round trip (about 3 ticks at 130 ms ping) with you pulled back to the identical spot each time, for up to 11 seconds and 40 seconds in 11 minutes of flight, with fly-into-unloaded-chunks on or off and while climbing and turning. Boost now holds its rewound speed for **boost-setback-hold** (default 1) tick after every setback before accelerating, VolytraFly's tested acceleration delay "to avoid getting stuck". When two setbacks in a row still land on the same spot, **pinned-glide-ticks** (default 10) hands movement to Minecraft's own elytra glide for half a second, then boosts again from the start speed. Flight Diagnostics counts these as **unpinned**.
- **Setback log:** each record now says whether you were pinned and, when it can match the setback to a move you sent, describes the rejected move: speed, horizontal and vertical speed, velocity change from the move before, turn angle and whether it followed an earlier setback.

## 0.24.0

- **Adaptive Elytra Fly rework:** flight is now deterministic: the same keys and terrain always fly the same way. A server setback keeps momentum and only rewinds the boost ramps (**boost-rewind-ticks**), as VolytraFly does; a correction over 64 blocks away stops the flight. Removed the learned server limits, correction backoff, pause and recovery, server memory and the boost auto-cap, with their settings **remember-server-limits**, **correction-backoff**, **correction-pause**, **recovery-delay**, **recovery-rate** and **boost-auto-cap** and the **Forget This Server's Flight Limits** button. On 6b6t most setbacks come from lag and ping jitter, so learning from them made flight random: one cruise setback could cut cruise to a quarter for the session, and chained boost setbacks kept a learned cap above the server's limit. Flight feel is unchanged, proven by fifteen recorded scripted flights reproduced exactly, and the per-tick pipeline now lives in a testable **AdaptiveFlightEngine**. Held or toggled Sprint still waits five gliding ticks after a chestplate-to-elytra swap before boosting.
- **Adaptive Elytra Fly past 300 b/s:** a vanilla server sets back a move packet when `distance² − serverVelocity² > 300 × packets that tick`, so one packet a tick allows about 346 b/s, while two packets landing in one server tick fail above 245 b/s; that bunching is the rubber-banding at 300 b/s. New **overdrive** (off) boosts to 340 b/s, which rubber-bands no more often than 300 b/s under that rule. New experimental **split-moves** (off) also reports a half-step position each boosted tick, doubling the allowance (480 b/s with overdrive) and letting 300–340 b/s survive bunched packets; an anticheat that counts packets may object. Boost's total 3D speed is always held to 17 blocks/tick, so climbs and dives at top speed share the limit. **boost-max-speed** now ranges up to 24.
- **Adaptive Elytra Fly setback log:** new **log-setbacks** (Diagnostics, on) writes each setback while flying to `meteor-client/comet/setback-logs`, one bounded file per session: speed, boost ramps, state, ping, correction distance and whether the chunk ahead was loaded, to compare flights by numbers. **Reset Flight Diagnostics** resets the counters; the engine HUD shows setbacks instead of learned limits and recovery.

- **Adaptive Elytra Fly smart swap:** new **smart-elytra-swap** setting (default on) equips a chestplate on landing and equips a usable elytra on the airborne Jump press, immediately before vanilla opens the wings. Searches hotbar, main inventory and offhand, supports full inventories, and respects container/cursor and Curse of Binding restrictions.

- **Live player search:** match the start of names as you type in both Chat Tools search bars, ignoring case. Include recent contacts, Meteor friends, online players and incoming TP requests, merge duplicate overview matches and avoid adding partial searches to recents.
- **Post-teleport cooldowns:** continue Home/TP countdowns after confirmed teleport success in the Chat Tools window and the Meteor HUD. Preserve the target, prefer server-reported durations, distinguish warmup and cooldown times on combined replies, and offer separate adjustable 30-second fallback estimates marked `~`. Calibrate future estimates from reported remaining time, isolate learned values per connection, and retain cooldowns through retries/cancel commands without restarting them on duplicate success lines.
- **6b6t teleport cooldowns:** start Home/TP cooldowns in the Chat Tools window and **tp-home-countdown** HUD for 6b6t's `Teleporting to: <home>` completion line and for teleports that finish by moving you to another 6b6t worker. Read compound waits such as `9m 35s` and `0m 34s` whole (previously `9m`, and short waits were dropped) and show long times as minutes. Remember learned cooldown lengths per server/account so a new session starts with the real duration, and add **Auto | Manual** cooldown rows to Settings: Manual pins your own length for that server/account (for ranks and reward reductions), re-times a running estimate, and never overrides a time the server reports. Keep conversations, recents, homes, requests and timers when the network switches you between its servers; only a new connection starts a new session. Generic waits still apply to the retried command, matching 6b6t's separate home and TPA cooldowns.
- **Dub Counter:** adapt Majkey's DubCounter 1.0.0, with the author's permission, into Comet as the **dub-counter** module and **dub-counter** HUD element. It counts ordinary chest blocks in loaded chunks and shows them halved as dubs (25 chests = 12.5), with chest and chunk totals in the HUD. Counting runs five times a second instead of every tick and no longer boxes each chest position into a new set, so its cost stays small with large stashes loaded. The HUD uses Comet's panel style and hides while the module is off. Remove the standalone DubCounter jar: both register `dub-counter`. Not yet tested in-game.
- **Chat Tools window:** one movable, resizable window with the same layout at every size: page tabs and close in a header bar (no product title, sidebar or separate compact layout), a scrolling body and a fixed composer bar. Every page uses one set of parts — toolbar, section captions, grouped list rows, shortcut tiles and primary/secondary/danger buttons — with crisp whole-pixel text, bitmap icons, an unread badge on the Players tab and short-lived action notices above the composer. Favourite homes run horizontally in wrapping shortcut tiles; conversations and submenus transform the same contents. Add Overview home favourites, player search, reply and recent-player rows, with Cancel on each cancellable countdown. Share Message/TP controls and request countdown/answers across player lists, and put Go/Rename/Delete directly on home rows. Countdowns read in plain language, such as "Teleporting in 4s" and "Cooldown ~30s". Save favourites per server/account and add Meteor's **tp-home-countdown** HUD element using the same timer state.
- **Chat Tools messages:** integrate DM recognition/history, notifications, Meteor friends, homes, player commands, hotspot and server settings. Preserve per-conversation drafts while navigating, track recent interactions and confirmed home changes, and keep server state isolated between connections. Original implementation; no third-party source or assets copied. In-game rendering and server interaction remain unverified.
- **Movement and player features:** add No Hurt Cam, five-mode Phase (Pearl, Clip, Normal, Sand and Climb), Angle Calculator and Anti Spawn Set to Comet. Adapt pearl rotations, prediction and temporary inventory swaps to Meteor; invalidate queued actions on disable/mode changes and restore Sand physics on exit.
- **Advanced Auto Replenish integration:** upgrade the original Meteor module and share it in the Comet category. Add smart thresholds, source stack preferences, category filters, custom-name protection, pause-on-use and a transfer budget while retaining stock settings, offhand tracking and Auto Totem locking. Use exact destination transfers and pause around carried cursor items, containers and Comet inventory loans.

- **Adaptive Elytra Fly unlimited boost:** holding Sprint now flies VolytraFly's speed system: a ramp from 60 b/s that gains `step × (max − speed) / max` per tick up to **boost-max-speed** (default 300 b/s), outside the engine maximum, but still inside the terrain, chunk and server-glide guards. Setbacks while boosting rewind the ramp 15 ticks instead of restarting it. New **Unlimited Boost** dropdown: **unlimited-boost** (on; off restores adaptive Sprint), **boost-max-speed**, **boost-start-speed**, **boost-ramp-step**, **boost-vertical-start-speed**, **boost-rewind-ticks**. Turn assistance keeps rising with speed while boosting, so a 90° turn at 300 b/s takes 2 ticks. The engine HUD element and Flight Diagnostics show a boost line.
- **Adaptive Elytra Fly unified braking:** one braking policy for released keys, walls, chunk edges and the ground. Around cruise it is **braking** (0.3); with unlimited boost on, planned braking rises with the square of speed just enough that a stop from the boost top speed fits the lookahead, so 300 b/s holds in clear air. When something is closer than planned, the guard commits to the gentlest constant braking that still stops before it, up to an instant stop: about 0.3 blocks/tick per tick from 40 b/s, 1.7 for a wall first seen 64 blocks ahead at 300 b/s, 8 for one appearing 10 blocks ahead, and every stop ends at the terrain margin. Vertical braking mirrors the vertical ramp. New soft landing brakes deliberate descents for the floor below and touches down at 0.2 blocks/tick. Releasing boost brakes down to cruise instead of snapping to the adaptive maximum. Without unlimited boost, braking is exactly as before.
- **Adaptive Elytra Fly vertical rework:** **initial-vertical-speed** (0.75 blocks/tick) is replaced by **vertical-max-speed** (default 3 blocks/tick, 60 b/s), the base for vertical percentages. Default cruise climb rises from about 5 to 21 b/s, Sprint from 15 to 60 b/s. New **vertical-ramp-step** (0.5) accelerates climbs along the boost curve (cruise target in 3 ticks); climbs no longer use up horizontal acceleration.
- **Adaptive Elytra Fly boost key:** fix boost not starting when Sprint was pressed a tick after forward from a hover (or near the ground) until Sprint was released and pressed again. Slow open-air flight counted as close-object control, and Sprint's close-object override then re-latched every tick, also leaving the block guard bypassed while Sprint was held. Close-object control now means touching or blocked by something, the override ends when that contact clears, and boost near an object crawls and resumes once clear. New **boost-key-mode**: **Hold** (default) or **Toggle**, where each Sprint press turns boost on or off until gliding stops. **toggle-off-when-idle** (default on) also turns toggled boost off a quarter of a second after every movement key is released, unless Auto Walk, Elytra Fly autopilot, Baritone or Baritone Elytra is moving you.
- **Adaptive Elytra Fly unloaded chunks:** new Terrain setting **fly-into-unloaded-chunks** (default off) treats chunks the client has not loaded as open air instead of stopping at their edge, for boost and cruise alike; the guards cannot see terrain there. Build and all 602 tests pass; not yet tested in-game or on 6b6t.

- **Removed Dynamic Sprint:** Comet no longer upgrades Meteor's **Speed**. Its Vanilla mode is stock Meteor again, Speed is no longer listed under Comet, and the **Comet Dynamic Sprint** settings and **dynamic-sprint-engine** HUD element are gone.
- **Meteor build compatibility:** before applying an upgrade to a Meteor module, check from bytecode that the installed Meteor still has every method, field, constant and injection point it uses. A changed build now skips just that upgrade (the stock module keeps working, is not listed under Comet, and chat names it on joining) instead of crashing at startup; Adaptive Elytra Fly refuses to start without its hook. Require Meteor 26.1.2 build 42 or newer. A build-time test checks every Meteor upgrade against the pinned build.
- **Scaffold:** run Meteor's own Scaffold tick again instead of a copy, so upstream fixes apply; Comet now only replaces the fallback-support sort with a one-pass nearest selection, stops the tower check at the first collision, and adds sticky switching and confirmation retries around Meteor's placement.
- **HUD elements:** replace the fixed engine overlays and the **player-speed-hud** module with movable, scalable HUD elements in a **Comet** group: **adaptive-elytra-fly-engine** and **player-speed**. The **engine-hud** settings are removed. Player speed now uses real time, so Timer no longer skews them, and teleports no longer spike the reading.
- **Adaptive Elytra Fly:** skip diagnostics formatting during play. Document **close-object-speed** and its Sprint override.
- **Baritone Elytra:** disabling the module mid-flight no longer turns ElytraFly back on while Baritone is still steering; it returns after landing. Add **restock-slot** for firework restocking, and log Baritone's status when take-off times out.
- **Elytra Replace / Elytra Log:** treat any worn glider-component item as an elytra (one that cannot be damaged is never due), swap hotbar spares with a single click, and cache the module-list count per tick. Elytra Log adds **warning-margin** (default 10) for a chat warning before it logs you out.
- **Auto Tool / Auto Weapon:** keep Auto Tool's inventory loan while a block is being broken, not only while Attack is held, and return it half a second after mining stops, so Baritone and other automated mining no longer swap the tool away every tick. Add **inventory-min-gain** (default 1 damage) so Auto Weapon only pulls a main-inventory weapon for a worthwhile upgrade.
- **Chat Tools:** ignore lines that start with an online player's name (public chat and private messages) before parsing timers, requests or homes. Answer TPA requests with the server's own accept/deny commands from its prompt or command tree instead of always `/tpy`/`/tpn`. Show Home, Reply, Hotspot, Settings entries and home actions only when the server offers their commands. Precompile chat patterns and refresh friend highlighting once a second.
- **Fast Storage ESP:** keep opened-container history when chunks unload (bounded to 65,536 per world and dimension) and across resource reloads, turn off with an error if the shader cannot be created, and stop reallocating entries for unchanged storage.
- **Shulker Icons:** compile the solid-colour pipeline once per resource load and fall back to normal icons if it fails; ask before **Reset** deletes all combos; precompute picker search text.
- **Block Coat / Smart Sponge:** read redstone-buffer blocks only in chunk sections whose palette can hold a component; pause rotated placement while riding, when rotations cannot be sent; let Smart Sponge share Block Coat's **max-packets-per-second**. Move Block Coat's blast estimate and sticky switching into their own classes, and reformat Smart Sponge.
- **Inventory Tweaks:** abandon a drop-backwards transfer whose rotation never runs after one second, and drop without turning while riding.
- **Safe Fast Use:** warn when Some mode selects nothing. **Home Packet Log:** report dropped records only after the writer drains, and record the running Minecraft version.
- **Cleanup:** remove the unreachable Trail Hunter, Area Spiral Search, ElytraPilot and XaeroPlus code (3,146 lines) with its 171 tests, the XaeroPlus compile dependency and suggestion, and the stale mod description. Share the braking formula, chunks-only probe and finite-value guards between the flight and sprint engines. Build and all 586 tests pass; nothing here has been tested in-game yet.

- **Friend chat highlighting and conditional cancellation:** colour complete friend names using Meteor's Friends list and configured friend colour, preserving clicks, hover details and formatting. Refresh existing chat when friend settings change and restore original colours when disabled. Show Cancel Home/TPA only while the matching outgoing request or teleport can still be cancelled, including inside Settings, and recheck availability when clicked.
- **Chat tab navigation and Reply:** add Reply immediately after Home to prepare `/r `. Replace the extra close tab with a single active title and a divider before its contents. Keep the title visible while paging, use it for back/close, and restore the main bar after choosing a command or text-entry action.
- **Incoming TPA and server settings:** show named Yes/No actions for incoming requests, retain separate players and request expiry, and show a pending notice below closed chat. Add a Settings button with nested 6b6t chat, bot, notification, home/TPA, appearance, hotspot and gameplay controls in the existing HUD. Preserve manual input for commands requiring values and avoid displaying guessed server toggle states.
- **Compact Chat Tools:** match the rendered chat width, use smaller 12-pixel controls with current Meteor theme colours, and keep countdowns below the messages when chat is closed. Add Hotspot actions (`/hotspot`, `/hotspot kill`), right-click home Delete/Rename actions, confirmed home-cache updates, and always-available Cancel TPA/Home buttons.
- **Embedded Chat Tools HUD:** replace the Meteor popup with a paged, clickable strip between chat messages and the input bar. Keep the current screen and draft when selecting a player, add a Home picker with immediate `/home <name>` execution, discover homes from server suggestions and home lists, and show server-reported home/TPA countdowns, request expiry and cooldowns with cancel actions. Clear discovered state when changing servers.
- **Auto Weapon inventory damage:** defer the first hit after an inventory swap until the server acknowledges the equipped weapon and its attack cooldown recovers. Keep the loan in place while waiting, coalesce repeated clicks, and cancel safely on target/slot changes, timeout or module disable. This prevents new-weapon enchantments from being combined with the previously held item's base damage.
- **Adaptive flight recovery:** speed up vertical and total-movement probes, preserve clean progress through direction changes, and recover toward previously demonstrated speeds before exploring further. Client stalls over 250 ms and correction bursts suspend learning for 20 ticks, apply temporary backoff once per recovery episode, and recover in about 2.3 seconds at defaults. Mixed horizontal/vertical setbacks now teach a shared displacement budget instead of lowering either directional limit; delayed feedback uses the fastest recent displacement. Retain smooth percentage braking and migrate existing server profiles. Build and all 678 tests pass; live server checks remain outstanding.
- **Fast Storage ESP crash fix:** isolate each retained storage batch's vertex and index buffers from Meteor's shared immediate renderer and other storage batches. Rebuilding, unloading or clearing storage no longer closes buffers used by normal rendering, and each batch retains its own geometry and exact index count.
- **Block Coat corridor safety:** preserve the moving player's lane and two blocks ahead in detected one- and two-wide passages, while allowing coating behind and preserving the other lane where available. Recheck the lane before deferred rotated interactions and leave open-area coating unchanged.
- **Block Coat manual clearance:** detect direct body-height TNT mining in narrow passages, remove deliberately cleared TNT from rollback/retry tracking, and keep both body cells clear until the player traverses the opening or a bounded timeout expires.
- **Block Coat movement backfill:** make movement/input direction the default tunnel reference with the original facing direction available as an option. Treat visibility-ray exhaustion as deferred work rather than an unreachable target.
- **Auto Totem removal:** remove Comet's Auto Totem optimization, mixin, tests and duplicate Comet-menu entry. Meteor's original Auto Totem remains unchanged in Combat.
- **Comet upgrade navigation:** add a direct **Settings** button for every separate Comet upgrade linked from Elytra Fly, Storage ESP, Fast Use and Waypoints. Stock modules upgraded in place remain shared entries in both their original category and Comet.
- **Module removal:** remove Stash-Hunter ElytraFly, its optional Stash-Hunter mixins and bridge, its settings links, and the Stash-Hunter suggestion from the packaged metadata.
- **Minecraft 26.1.2 release:** downgrade Minecraft and Meteor to 26.1.2, update the optional Xaero and Baritone compile targets, and port GUI, camera and retained-rendering calls to the 26.1.2 APIs. Baritone 26.1 does not expose its calculated elytra path, so takeoff readiness uses its public process status and calculated-path corridor inspection is unavailable on this target.
- **Chat Tools:** add a configurable player-name click menu in chat. Detect available player-target commands from each server's Brigadier command tree, supplement them with labeled `{player}` templates for plugin commands such as `/tpa`, and keep incomplete commands open for message text. Commands require confirmation by default, with optional immediate execution for complete command paths.
- **Inventory Auto Tool/Weapon:** extend Meteor's stock Auto Tool and Auto Weapon scoring to the main inventory behind an enabled-by-default **use-inventory** setting. Temporarily swap the winning item into the selected hotbar slot, preserve the displaced stack in its source slot, and restore the pair after mining or attacking. Share one guarded loan between both modules and resolve player slots correctly in open container menus.
- **Live adaptive engine HUDs:** add an optional **engine-hud** overview to Dynamic Sprint and Adaptive Elytra Fly. Dynamic Sprint shows its state, measured/output speed, active target, attainable maximum, recovery and corrections in the top-left. Adaptive Elytra Fly shows horizontal/vertical output, learned directional limits and server-glide state in the top-right.
- **Dynamic Sprint simplification:** replace the ground adaptive-speed control panels with three settings: acceleration, braking and dynamic speed. Vanilla Speed remains the non-Sprint target; Dynamic Speed is a finely adjustable percentage of a conservative 40 blocks/second engine maximum. Acceleration and braking now use hundredths-of-a-percent engine units, while collision, unloaded-chunk and correction safeguards remain always on internally.

## 0.23.9

- **Soft server-limit guidance:** remembered correction speeds no longer become permanent hard ceilings. Horizontal, ascent and descent limits cautiously cross prior rubberbands after twice the normal clean-flight delay with one step capped at **0.01 blocks/tick**, then resume ordinary learning speed. Corrections below 60% of settled clean movement retain temporary pause/backoff but are not learned as speed boundaries. Settled probes become the next warm start; another plausible correction backs off immediately. Horizontal recovery now learns from clean level Sprint flight, while vertical directions and acceleration remain independent.
- **Independent vertical speed engine:** add **vertical-cruise-max-speed** (default **35%**) and **vertical-sprint-max-speed** (default **100%**). Jump/Sneak now accelerates toward the selected percentage of the learned ascent or descent ceiling both with and without Sprint, independently of the horizontal percentages. Releasing Sprint brakes smoothly to vertical cruise; zero Meteor vertical speed remains the axis switch.
- **Percentage braking:** apply cruise/Sprint percentages to requested speed and keep terrain stopping speed as an absolute safety cap. Fractional horizontal and vertical cruise no longer starts braking at the full adaptive maximum's stopping distance.
- **Adaptive vertical flight:** learn ascent and descent limits independently instead of immediately requesting the horizontal Sprint ceiling vertically. Add **initial-vertical-speed** (default **0.75 blocks/tick**, about **15 b/s**) and **vertical-acceleration** (default **0.05 blocks/tick per tick**). Keep the percentage controls and terrain limits, smooth vertical release/reversal with braking capped at twice vertical acceleration, and retain immediate terrain/correction stops.
- **Correction learning:** use the last 20 ticks of actual movement and vertical intent to identify delayed climb/descent setbacks, reduce the relevant directional speed and vertical acceleration, and learn horizontal guidance from level-flight failures. Explore higher speeds only during sustained clean boosted flight at the relevant 100% Sprint setting. Save successful probes only after the feedback window stays clean. Relocations of more than 64 blocks retain temporary correction backoff without teaching speed guidance.
- **Server memory:** add **remember-server-limits**, enabled by default, to restore learned horizontal, ascent, descent and vertical acceleration speeds across toggles, reconnects and restarts. Keep settled starting speeds separate from correction guidance so learning can cautiously continue after reconnecting. Separate remote host/port/dimension and single-player world/dimension profiles. Store up to 128 recently used profiles with 180-day expiry in the versioned, checksummed, atomically replaced `meteor/comet/adaptive-flight-limits.dat`; migrate version 1 conservatively. Corrupt or unreadable memory starts fresh. Add **Forget This Server's Flight Limits** separately from the diagnostics reset, and show directional limits and correction guidance in **Flight Diagnostics**.
- **Validation:** build and all **635 tests** pass, including low-speed correction filtering, soft-guidance recovery, reconnect probing, independent horizontal/vertical percentages, smooth vertical Sprint release and fractional-speed terrain braking regressions. A prior headless Fabric check passed **620 assertions** against the release JAR fixture. Live Minecraft/server validation remains required for server-limit probing, vertical cruise/Sprint acceptance, remaining rubberbanding and movement feel.

## 0.23.8

- Upgrade Meteor's built-in **Speed → Vanilla** in place with an optional adaptive horizontal engine, enabled by default, and expose the same Speed instance in Comet. Add a 40 b/s ceiling, 35% cruise/100% held-Sprint targets, acceleration, braking, adaptive turning, a configurable brake key and sneak precision. Disabling **adaptive-speed** restores stock Vanilla; Strafe keeps its original engine.
- Bound terrain scans, brake for walls and missing chunks, and permit vanilla step/collision handling at crawl speed. Keep vertical physics, native ground/liquid/sneak pauses, pathing and Anchor control. Feed actual clipped movement back into momentum and advance acceleration once per player tick.
- Add correction pause/backoff/recovery, corresponding Timer boost backoff and cleanup, collapsed Speed setting groups and a Speed Diagnostics dropdown.
- **Speed validation:** build and all **585 tests** pass, including 22 new regressions for percentage/correction limits, smooth Sprint release, braking/reversal, normalized inputs, bounded obstacle/chunk checks, crawl movement and clipped jump headroom. The packaged JAR passes **97 headless Fabric assertions** against Meteor 26.2-35 for actual mixin application, shared module identity, settings persistence, event subscription, correction threading, Timer backoff/handoff and disconnect cleanup. Live movement feel and server acceptance still need an in-game check.
- Add **shulker-icons** in Comet. Shulker boxes in GUIs show their most common item by total quantity, with a configurable overlay scale and corner.
- Add ordered, named combo rules with searchable required-item and icon pickers, enable switches, priority controls and deletion. All selected item types must be present; extra contents are allowed. First match wins, otherwise the most-common icon is used. Combos save, reset and copy/paste with Meteor settings.
- Offer every item as a normal icon or a configurable flat-colour silhouette. Give each combo its own colour, preview the result in the editor/picker, and group matching block model shapes into a single solid-icon choice with searchable aliases. Use item texture alpha for the silhouette while discarding texture colour, lighting and glint colours; handle normal atlas and oversized GUI items independently of surrounding items. Legacy combos and automatic fallback icons remain normal.
- Replace the separate icon-style dropdown with a **Solid Colour** checkbox immediately beside **Select Colour**. On uses the chosen silhouette colour; off restores the original item design without discarding the chosen colour. Preserve old saved styles and invalidate cached overlays immediately on toggle/colour edits.
- Empty or unavailable contents show no overlay. Cache selections for up to 256 immutable container contents and invalidate them on rule changes.
- **Shulker validation:** build and all 585 automated tests pass. An isolated Fabric check passes 43 assertions, including actual GUI item extraction and normal/oversized composite calls, selected vertex colours, normal shader restoration, toggle persistence/migration, colour retention and cache invalidation. Its fixture records GPU submissions and skips an unused buffer allocation. A separate real offscreen OpenGL test passes 224 pixel checks of the shipped shaders: exact selected RGB, alpha, transparent gaps and restoring the normal texture with no leaked tint. Run it with `python3 scripts/check_shulker_icon_shader.py` on Linux with EGL/OpenGL. Live GUI interaction still needs an in-game check.

## 0.23.7

- **Adaptive cornering:** add **adaptive-turn-rate** (default **15**, range **0–45** degrees per tick). Above harmless speeds, turn existing momentum toward the requested heading instead of waiting for acceleration to cancel it. The rate adapts to actual speed: pure momentum steering up to twice **acceleration** (0.3 blocks/tick by default), a smooth blend by six times **acceleration**, then a rise to double the setting at the adaptive engine's current maximum, so faster flight turns harder rather than drifting further. Corners keep their speed; speed and vertical changes still use **acceleration**, reversals beyond 135° still brake first, and the terrain guard sweeps the turned path. With defaults, a 90° turn now takes 5 ticks and drifts 4.8 blocks at 35% cruise (was 20 ticks and 19 blocks), and 3 ticks and 8.0 blocks at the 118 blocks/second maximum (was 56 ticks and 160 blocks). Zero restores momentum steering.
- **Server glide guard:** vanilla servers keep simulating an unmodified elytra glide for a client-moved player. Each tick they move that glide from the reported position, deal fly-into-wall damage if it hits a wall faster than 0.3 blocks/tick, then restore the position. 0.23.6 stopped 0.35 blocks from walls, inside that reach, which is the likely cause of the small hit when stopping at a wall at any speed. Add **server-glide-guard** (on, with block avoidance) to follow that glide and keep walls out of its next move. It tracks takeoff, the rotation actually sent, attached and just-used rockets, corrections, motion and explosion packets. Braking now ends far enough away, a low-speed crawl stops at the glide's reach, and a hovering player steps back if the glide grows into a wall. Unloaded chunks in its path count as walls. Harmless glides keep full tight-space contact, and glancing and side contact are unaffected; a steady level glide is about 1.5 blocks/tick, so a wall you face is held about 1.6 blocks away after a few seconds of flight. When no reachable position is safe, the requested move is kept and the hit counted.
- **Diagnostics:** show the estimated server glide speed, glide-limited ticks and predicted server wall hits, plus a **Glide clearance** status.
- **Validation:** build and all 550 tests pass. Glide, rocket, low-speed zeroing, restitution and damage formulas follow Minecraft 26.2 bytecode. A pipeline reproduction shows 0.23.6's wall stop letting the simulated server glide deal 3–12 health, and none with the guard while holding into the wall for 20–30 seconds from hidden glide speeds of 0.18–1.48 blocks/tick. Further regressions cover smooth braking, stepping back, unloaded chunks, rockets, unavoidable boxed-in hits, harmless tight-space contact and the speed-scaled turn schedule. Not yet checked in-game: whether 6b6t keeps vanilla's server-side glide simulation (turn the guard off if walls are held back needlessly), and how the sharper high-speed turns feel and are accepted there.

## 0.23.6

- **Adaptive speed controls:** add **cruise-max-speed** (35%) and **sprint-max-speed** (100%), each adjustable from 0–100% of the adaptive engine's current output after terrain, braking and correction limits. Show the adaptive maximum and selected cruise/sprint speeds in blocks/second. Meteor's horizontal speed remains a zero/nonzero movement switch; normal vertical speed remains in Meteor.
- **Sprint vertical boost:** use the sprint ceiling for deliberate Jump/Sneak ascent and descent, including purely vertical movement. Retain acceleration, steering, reversal braking, terrain protection and correction backoff. Releasing Sprint restores cruise speed and Meteor's normal vertical input; Shift auto-hover can still take priority over descent.
- **Tight-space control:** add **safe-maneuvering**, enabled by default, to bypass block guarding and Meteor No Crash below Minecraft's wall-damage threshold of 0.3 blocks/tick (about 6 blocks/second). Permit crawl-speed movement after a faster step was blocked, including when the next acceleration request would otherwise block again. Apply the same bypass to extra SELF movements; retain unloaded-chunk protection throughout.
- **Validation:** release build and all 514 tests pass, including adaptive percentage scaling, intentional vertical Sprint, harmless wall release, guard reactivation and unloaded-chunk protection. A headless Fabric check verifies the packaged Elytra hook, real module registration, setting defaults/ranges and adaptive controls; its fixture skips an unused render-buffer allocation. Live Minecraft/server checks are still required for wall manoeuvring, physical keys, vertical sprint control and server acceptance.

## 0.23.5

- Add a **Comet** dropdown inside Elytra Fly, Storage ESP, Fast Use, Waypoints and Stash-Hunter settings, linking to their related Comet upgrades. Left-click toggles the upgrade; right-click opens its canonical settings screen. Reuse the registered instances so settings, binds and state stay shared with the Comet category.
- Retain the shared Comet/original category entries for Scaffold, Trajectories, Auto Totem and Inventory Tweaks. Keep standalone Comet tools in Comet.
- Initialize custom section headers before adding their contents, including Flight Diagnostics and Storage ESP Actions.
- **Validation:** build and all 500 tests pass. A headless Fabric check verifies the settings-screen hook, all five parent-module link sets, shared registered widget targets, native header expand/collapse, custom dropdown header order, and the original four shared menu entries. Pixel appearance still needs an in-game check.

## 0.23.4

- Add **Scaffold**, **Trajectories**, **Auto Totem** and **Inventory Tweaks** to the Comet menu as well as their original Meteor categories. Reuse each original module instance so settings, keybinds and enabled state are shared, with a single module registration and event subscription.
- Place the extra entries alongside related Comet modules: Trajectories with rendering/storage, Scaffold with building, and Auto Totem/Inventory Tweaks before diagnostics.
- **Validation:** build and all 500 tests pass. A headless Fabric check against the release JAR exercises the registration callback with module fixtures: original entries remain registered, both menus share identical module/settings instances, all 16 registrations are unique, and repeated sorting preserves the Comet order. Visual interaction still needs an in-game check.

## 0.23.3

- Start every Comet module's setting groups collapsed so settings open as clickable dropdown sections. Retain saved setting values and saved group expansion states. Show Block Coat's General section first.
- Put Adaptive Elytra Fly's diagnostics and reset button in a **Flight Diagnostics** dropdown and Fast Storage ESP's clear-history button in an **Actions** dropdown.
- Start Comet's added Scaffold and Trajectories sections collapsed too.
- **Validation:** build and all 500 tests pass. Check the release JAR version, retained menu-order hook and absence of retired modules. Dropdown appearance and interaction still need an in-game visual check.

## 0.23.2

- Remove **area-spiral-search** and **trail-hunter** from Comet.
- Group the Comet menu by purpose: flight and flight safety, storage and waypoints, building, then diagnostics. Retain that order after Meteor's automatic alphabetical sort; other categories keep their normal ordering.
- **Validation:** build and all 500 tests pass. A headless Fabric check against the release JAR verifies the sorting hook with shuffled menu fixtures, repeated sorting, unchanged alphabetical sorting in another category, and the absence of both removed module classes. The menu has not been visually checked in-game.

## 0.23.1

- **Sprint boost:** hold Sprint to ramp up with the configured acceleration toward the adaptive engine's maximum horizontal speed, beyond Meteor's normal cruising speed. Remove the instant acceleration bypass. Request the engine ceiling of 64 blocks per tick, then retain swept terrain stopping limits, steering, vertical smoothing and reversal braking. Apply correction backoff to the attainable sprint ceiling so the maximum-speed request cannot mask it. Releasing Sprint restores Meteor's normal horizontal ceiling.
- **Validation:** build and all 500 tests pass. Sprint regressions cover gradual acceleration past cruising speed to the adaptive maximum, steering, reversals, release braking, vertical limits, correction backoff/recovery and bounded terrain stopping. Fabric prelaunch verifies the packaged Elytra Fly hook against Meteor 26.2-35. Live Minecraft/server acceptance remains untested.

## 0.23.0

- **Scaffold:** optimize Meteor's existing module with one-pass nearest-support selection and an early-exit tower ceiling check. Add **Comet → sticky-switch** (off by default) to retain the block slot between placements. Add **confirmation-retries** (on by default) to pace repeated unconfirmed/rejected positions independently, with quick underfoot retries and no delay for new cells. Confirm through applied server block updates, with bounded history and world/module resets.
- **Trajectories:** retain Meteor's simulator and renderer while caching exact unchanged predictions within a tick. Add **Comet → optimize-trajectories** (on), **remote-trajectory-range** (128 blocks) and **trajectory-steps-per-frame** (8,000). Prioritize the local player, rotate remote work, split multishot allowances and hide stale paths that cannot be refreshed. Refresh fired starts each frame and invalidate on applied world/entity changes, chunk changes and border movement. Tight budgets may shorten or omit predictions; disabling optimization restores stock handling.
- **Inventory Tweaks:** schedule Steal/Dump actions on the client thread, retaining initial/normal/random delays without sleeping or catch-up bursts. Bind exact player/world/screen/menu identities; recheck source stacks, filters and pickup permissions before clicks and rotation callbacks. Cancel on container changes, disconnect, module changes, manual sorting or a held cursor stack. Coalesce duplicate requests and cap transfers at eight actions per client tick. Fix Steal Drop issuing no drop when Drop Backwards is off.
- **Validation:** build and all 496 tests pass, including 30 new regressions for support selection, retry state, transfer scheduling, prediction inputs, cache invalidation and simulation budgets. Fabric prelaunch verifies the packaged mixins against Meteor 26.2-35, including the private trajectory Path and applied-packet hooks. Live server acceptance, rendering and FPS measurements remain required.

## 0.22.3

- **auto-totem:** optimize Meteor's existing Combat module automatically. Short-circuit damage prediction in Strict mode, during configured elytra flight, and when health plus absorption is already at/below the threshold. Retain fresh prediction for all other Smart decisions, along with the existing delay and pop handler.
- **Packets:** equip with one normal offhand SWAP click from ordinary hotbar/main-inventory slots, preserving the cursor and returning the displaced item. Resolve source slots from the current menu; retain Meteor's original move handling for creative and unsupported/restricted layouts.
- **Validation:** build and all 466 tests pass, including six new regressions for decision equivalence, fresh threats and menu-slot resolution. Fabric prelaunch confirms the Auto Totem mixin applies against Meteor 26.2-35. Live Minecraft/server acceptance and FPS measurements remain required.

## 0.22.2

- **block-coat:** add **pause-items-reset-on-use**, enabled by default. Using a configured pause item on a block, entity or in air restarts its timeout, including after expiry. Continuous item use refreshes the timer. Only the hand being used counts; Block Coat's own automated placements do not reset it. Disable the toggle to retain the original hold-based countdown.
- **Validation:** build and all 460 tests pass, including seven new timer regressions for repeated uses, restarting after expiry, disabled/unrelated uses, first use, reset and indefinite pauses. Live Minecraft testing remains required.

## 0.22.1

- **block-coat:** add **pause-items-timeout**, defaulting to 5 seconds and adjustable from 0–60. Resume placement and automatic switching after the timeout even if a pause item remains held. Zero retains the pause until switching away. Continuous holding does not restart the timer; switching away and selecting a pause item again starts a fresh timeout. Uses elapsed real time and rechecks queued placements with the same timer.
- **Validation:** build and all 453 tests pass, including 12 new timeout regressions for expiry, repeated checks, release/reselect, duration changes, lifecycle reset and monotonic clock boundaries. Live Minecraft testing remains required.

## 0.22.0

- **block-coat:** add **liquid-filler**, enabled by default for TNT coating. Fill isolated plain water source blocks with TNT in normal and conservative TNT modes; skip sources touching another source on any of their six faces, including source water inside plants or waterlogged blocks. Recheck isolation before sending and retain reach, collision, redstone and placement pacing checks.
- **Controls:** add configurable **pause-items**, defaulting to flint and steel. Holding a listed item in either hand pauses every Block Coat placement and automatic switching, cancels unsent interactions and resumes automatically after switching away.
- **Validation:** build and all 441 tests pass, including 12 new source-isolation regressions covering all six neighbours, flowing water, source-bearing plants/waterlogging, unloaded neighbours, build heights and live state changes. Live Minecraft/server validation is still required.

## 0.21.1

- **Sprint boost:** hold the bound Sprint key during adaptive Vanilla flight to reach the current horizontal speed ceiling immediately. Preserve steering and vertical smoothing, brake through reversals, and retain configured speed limits, correction pauses/backoff and the swept terrain guard. Release Sprint for normal acceleration behavior.
- **Controls:** add **sprint-boost**, on by default. Read the physical bound key/button while Minecraft is focused with no screen open, so automatic or toggle-sprint state does not engage the boost.
- **Validation:** build and all 429 tests pass, including 17 new Sprint boost regressions covering steering, reversals, vertical smoothing, correction pauses/backoff and terrain limits. The Fabric mixin smoke check passes against Meteor 26.2-35. Live server acceptance still needs an in-game check.

## 0.21.0

- **adaptive-elytra-fly:** optional replacement for Meteor Vanilla movement, using full-vector acceleration and braking. Keeps Meteor's speed, takeoff, inventory, autopilot and firework controls and works with Comet's existing Vanilla flight planners. Other modes retain Meteor's handling; scripted landings and Shift auto-hover yield to their original controller.
- **Terrain pacing:** check the actual blended trajectory using swept player boxes, correct negative chunk coordinates and intermediate/body-width chunk checks. Brake within the loaded, clear corridor and leave a configurable margin. One bounded lookahead scan per movement tick; no chunk loading or additional movement packets. Descending floor contacts still reach Minecraft's collision solver for touchdown. Existing body overlaps after corrections/unloads temporarily yield to Meteor so the player can withdraw.
- **Correction recovery:** accept server corrections normally, pause briefly and lower a temporary speed ceiling, then recover gradually after a clean interval. Packet callbacks only queue atomic signals; world and movement work stays on the client thread.
- **Diagnostics:** show observed progress, correction ceiling/counts, ticks actually limited by chunks/blocks, query counts and frame times. Resetting diagnostics retains active correction backoff. Default 64-block lookahead and braking impose a conservative ceiling of about 118 blocks/second even in clear terrain; configured Meteor speeds remain upper bounds.
- **Validation:** build and all 412 tests pass, including 45 new controller, terrain, combined-flight and touchdown tests. A Fabric prelaunch smoke check confirms the required mixin applies to Meteor 26.2-35. Live Minecraft/6b6t checks are still needed for server acceptance, performance and other movement-addon interactions.

## 0.20.0

- **fast-storage-esp:** add a shader-only Storage ESP to Comet, using Meteor's container models and outline/glow pipeline. Retain GPU geometry for unchanged storage, cull offscreen batches and use an isolated storage depth buffer to reduce overlapping surfaces without terrain occlusion. Moving the player applies fading in the shader.
- **Updates:** reconcile loaded storage five times a second, invalidate changed cells and prepare models within a per-frame budget. Track double-chest interactions, opened colours, lid/shulker poses and resource/world/chunk lifecycles. Enabling it disables Meteor's original Storage ESP to avoid duplicate work.
- **Controls:** retain storage selection, shader appearance, colours, fading and opened-container controls; add optional render distance and rebuild time limits. Initial loading is progressive; real-world FPS and visual compatibility need in-game validation.
- **Validation:** build and all 367 tests pass, including 17 new cache and camera/frustum regressions. Both mask shader stages pass GLSL compilation and linkage; the packaged JAR includes the module and shader assets.

## 0.19.2

- **area-spiral-search:** add **paused** and optional **pause-key** controls to pause and resume the current route without restarting it. Pausing releases steering and take-off, preserves progress and suspends the route timeout. The module list shows **Paused**; manual movement during a pause does not advance the route. Resuming returns to the saved route point before continuing the spiral, with progress monitored during the return.
- **Flight:** ElytraFly stays enabled for manual flight while paused; pausing does not hover or land. Existing world, equipment and flight-controller stop conditions still apply. New activations start unpaused.
- **Validation:** build and all 350 tests pass, including four new pause/resume regression tests covering retained progress, centre approach, manual detours in both spiral directions and stationary resumes. Live Minecraft testing remains required.

## 0.19.1

- **block-coat:** keep TNT two blocks away from active or inactive redstone components in all directions, including diagonals and through walls. Covers wire, torches, repeaters, comparators, observers, switches, pressure plates, tripwire, powered rails, sculk sensors and trapped chests, while retaining the powered-target check.
- **Placement safety:** apply the buffer to coating, tunnel backfill, scaffold and Smart Jump, and recheck it before sending deferred interactions. Unloaded terrain inside the buffer defers TNT placement; other coating materials retain their normal behavior.
- **Validation:** build and all 346 tests pass, including 14 new redstone safety tests using real Minecraft block states. Live Minecraft/server testing remains required.

## 0.19.0

- **safe-fast-use:** new module in Comet with Meteor-style **mode** (All/Some), **items**, **blocks** and **cooldown** controls. Cooldown defaults to zero and takes precedence while enabled, including when Meteor's Fast Use is also on.
- **Shulkers:** **prevent-shulker-open**, on by default, stops repeated Use from opening a shulker placed during the same hold. Release and click again to open it normally. Supports either hand and all colours; sneaking still permits placement against a box.
- **Validation:** build and all 332 tests pass, including seven new shulker interaction regression tests. The JAR includes the registered module and cooldown mixin. Live Minecraft/server testing remains required for fast placement, both hands, deliberate opening and sneaking.

## 0.18.1

- **block-coat:** add optional **conservative-tnt**, off by default, to skip redundant surface TNT using resistance-aware blast estimates. Existing coating behavior, jump placement and scaffold remain available. Failed placements and removed TNT release their estimated coverage.
- **Settings:** put the toggle in an expanded **TNT Saving** section at the top of Block Coat's settings. Saved conservative TNT values from General carry forward. The distinct patch version makes this build identifiable in Fabric's mod list and log.

## 0.18.0

- **area-spiral-search:** new Meteor-only flight module that starts at your current X/Z, or flies to a custom centre, then follows a smooth outward spiral using ElytraFly. The first outward leg follows your facing; clockwise turns, 256-block spacing and a 5,000-block radius are the defaults. Route settings apply on activation.
- **Flight controls:** Vanilla and Packet hold the configured height with clearance over loaded terrain and optional take-off; Pitch40 steers an existing glide. Overworld and End supported. Refuses conflicting flight controllers and yields if one takes over. Restores the automatic ElytraFly controls it temporarily disables; ordinary stops leave ElytraFly on with those controls, without landing. Stops on completion, lost flight prerequisites, world changes or no movement, and does not resume after reconnecting.
- **Scope:** this flies a survey route; scanners can run separately. It does not detect bases or guarantee chunk delivery or complete coverage. In-game validation remains required.
- **Verification:** build and 300 tests pass, including 12 spiral tests for geometry, centre approach, progress, missed waypoints and bounded-turn simulated flights. The JAR contains the registered module and planner. Route progress monitoring stops an over-fast flight that circles without advancing.

## 0.17.0

- **trail-hunter:** trail-follow is renamed, since it now hunts for bases rather than following one trail. Its behaviour is unchanged. Meteor keeps settings and keybinds by module name, so set trail-hunter's settings again after upgrading. Route memory and the trail-lead log carry over, because their files don't depend on the module name. With **keep-searching** off it still follows a single trail.

## 0.16.0

- **home-packet-log:** records decoded player spawn, absolute position, teleport, relative movement, removal and tab removal packets to `meteor-client/comet/packet-logs/packet-<UTC time>-<id>.jsonl`. Optional exact player name/UUID filter; your own position corrections, teleport acknowledgements and outgoing `/home`/`/homecancel` markers are always included.
- **Capture details:** original position fields, relative flags, identities, entity IDs, dimensions, timestamps and record numbers. Snapshot positions and packet-only estimates are labelled separately. Removed identities remain available for late position packets; spawns replace reused IDs. Relative-movement codec bases are tracked separately from teleports.
- **Controls:** ordinary movement logging defaults on; optional type-only logging covers all incoming packets. Bounded background writes flush every second and stop at 64 MiB by default. Each activation creates a separate file. Shutdown drains accepted records and adds a summary. Leaving the world stops capture; settings apply to the next capture.
- **Interpretation:** these are decoded client-visible fields. Removal alone has an unknown destination and does not establish `/home`. A two-account in-game test is still required to check whether current 6b6t exposes a destination.
- **Verification:** build and 288 tests pass, including 27 new writer, position-decoding and real-packet replay tests. The JAR contains the recorder and its registered packet-ID accessor. Live Minecraft/6b6t testing remains required.

## 0.15.0

Trail Follow becomes an unattended base hunter. The other modules are unchanged.

- **Map-based hunting:** keep-searching now works from a local map of XaeroPlus classifications (trail, explicit new terrain, unknown) instead of arrival timing. The map is fed by new chunks and by the cache, read once a second. With keep-searching off, the old single-trail tracker is unchanged.
- **Any crossing angle:** inside a trail band, the flight fits the band's centreline and turns along it, whatever angle it crossed at. Before, a trail crossed at more than about 35° was crossed rather than followed, and the bot often escaped while still approaching one.
- **Centred following:** the flight fits the band's edges from cross-sections behind and ahead, and stays a learned half-width inside when only one edge is in view. It aims at the centreline 96 blocks ahead, and the axis follows gentle curves at up to about 0.75° per block. Before, it drifted to a wide band's edge and mistook the new terrain beside it for the trail's end.
- **Leads:** a trail followed for at least 192 blocks that meets explicit new terrain is reported as a possible base. Each trail end or fork goes to chat and to `meteor-client/comet/trail-leads/<world>.csv`. **mark-trail-ends** (default on) and **mark-forks** (default off) also add Meteor waypoints, which xaero-waypoints puts on Xaero's maps. Repeat sightings within 256 blocks are not reported again.
- **Search:** new **search-pattern**, `SPIRAL` (default) or `STRAIGHT`. `SPIRAL` circles 0,0 at the starting distance, widening by **spiral-spacing** (default 768) per turn, so it crosses trails radiating from spawn. After each trail it heads back to its circle at 45°. Search steers to unflown old chunks up to 135° either side, and ignores old chunks already seen from any recorded flight.
- **Outbound and highways:** **prefer-outbound** (default on) takes trails away from 0,0 and skips ones that only lead back to spawn. **avoid-highways** (default on) treats old chunks within 192 blocks of the axis and diagonal highways as unknown, so a trail that joins a highway is crossed rather than followed along it.
- **Frontier with 1.12 detection:** OldChunks' explicit "modern" result now counts as new terrain when trails are 1.12 chunks only. Trail ends are therefore recognised in modern areas where nothing is fresh. Palette NewChunks is no longer needed for that, and its warning is gone.
- **Direction-aware route memory:** each flown chunk records the direction it was flown in. Flying along or beside a remembered route in either direction, for at least 64 blocks (32 directly under it), is still rejected, but steep crossings are allowed. Before, a long session fenced itself in with its own paths and landed, and a trail crossing an old flight path was abandoned there. Route files move to version 2 (12 bytes per chunk); version 1 files still load, with their chunks blocking every direction except steep crossings. Primitive maps and a coarse region index cut memory and lookup cost.
- **No landing when boxed in:** when every heading repeats some history, the flight takes the least repetitive one and holds it. If the area is flown out, it flies 1,500 blocks outward and starts a new search circle. It lands only when route memory is full or cannot be saved.
- **Safety:** Vanilla and Packet climb to stay 24 blocks above the highest loaded terrain in the next 128 blocks. New **if-stuck** (default Disconnect): without 64 blocks of progress for 30 seconds it retries take-off; after 90 seconds it warns, or disconnects with AutoReconnect off. Chat reports trails found (with compass heading), leads and relocations, and stays quiet otherwise. It also warns once when chunks arrive slower than the flight, which hides trail ends. The module list shows the trail ends found this run.
- **Simulation:** a flight simulator in the test sources drives the module's own navigation through seeded worlds of radial trails with bends, branches, bases, noise and optional highways. Over 48 worlds × 30 minutes:
  - the new hunt reached about 450 bases, spent 63% of the flight over old chunks, and never landed;
  - the 0.14.0 logic reached 34 bases with 10% over old chunks, and 14 of its 48 flights would have landed.

  Whole-flight simulations now run in the test suite. In-game validation remains required.

## 0.14.0

- **Straight trail commitment:** replace recent-centre averaging with a robust forward line fit. Longitudinal support wins over side density; aim 96 blocks ahead with a bounded 12° steering correction. Keep an anchored trail bearing through gaps and timeouts, with axis updates bounded to 10° from the original direction so small turns cannot ratchet into a reversal.
- **Fresh frontier:** leave the committed line only for a recorded route obstruction or six distinct explicitly fresh chunks forming a three-lane, two-row wall within 96 blocks ahead. A far-off wall does not skip the remaining trail; a nearby frontier redirects Nether goals immediately. Unknown terrain and isolated fresh chunks do not terminate the trail. Palette NewChunks supplies fresh evidence; chat explains when it is disabled.
- **Offset returns:** add an approximately 128-block trail-corridor check, rejecting a repeat 100 blocks alongside the recorded path in either direction. Preserve forward extension and outward departure. Controlled escapes need both 128 blocks of displacement and 128 blocks of lateral separation before taking another trail.
- **Diagonal departures:** correct narrow crossing checks that could mistake the flight's own oblique tail for an obstruction. Retain the wider no-distance-grace protection. Existing saved route files remain compatible.
- **Verification:** 261 automated tests pass, including 36 new tests and expanded regression cases. Tests include actual moving tracker/planner/memory simulations, 100-block offset returns, and every departure heading at multiple chunk offsets. In-game validation remains required. This update focuses on flight; stash detection is unchanged.

## 0.13.0

- **trail-follow:** new **keep-searching** setting, on by default. Seek unflown old chunks automatically; after a trail ends, find another. If following old chunks would loop into a remembered route, hold a clear escape heading through new/unknown chunks until another unflown old route becomes available. Existing XaeroPlus cache highlights guide navigation but never count as newly arrived acquisition evidence.
- **Route history:** persist actual flown chunk corridors separately per server address/world folder and dimension. Direction-independent checks reject repeats from either end and nearby parallel passes. Interpolate fast movement, separate teleports/reconnects, and retain old entries without eviction. Save checksummed snapshots atomically in the background every 30 seconds and when stopping. Corrupt history refuses activation; save failure, capacity exhaustion or no clear checked exit starts landing.
- **Controls:** Land, Heading and Disconnect remain available with **keep-searching** off. Searching, following, seeking another trail and crossing new chunks have separate status text. Stash-Hunter remains optional.
- **Nether:** inspect the calculated Baritone path as well as its intended heading. Reject terrain detours through remembered routes, try other headings, and shorten the goal for landing if no checked exit remains.
- **Verification:** 225 automated tests pass, including 81 new exploration, route-memory/store, chunk-classification and calculated-path tests. In-game behaviour remains untested; see README for loop, restart, dead-end and reacquisition checks.

## 0.12.0

- **trail-follow:** independent XaeroPlus trail flight; Stash-Hunter is optional. Select 1.12-era OldChunks, positive palette non-fresh chunks, or either (default). Acquire 10 distinct chunks in 5 seconds, average the latest 20, ignore centres behind the player, circle after 5 seconds without a useful arrival, and give up after 30 seconds total silence. Duplicate loads and delayed cache classifications cannot create a fresh lock.
- **Flight:** shared `ElytraPilot` keeps the existing Stash-Hunter steering/take-off behaviour. Overworld uses ElytraFly; Nether uses Baritone goals 500 blocks ahead, throttled to 3 seconds with heading-change and endpoint-refresh thresholds. Flight conflicts release control.
- **Trail end:** default **Land** approaches a loaded dry landing area and descends in the Overworld, disabling ElytraFly after touchdown and restoring its saved settings. With no usable site it holds altitude. In the Nether, Baritone finishes its final route and landing. Alternatives: a set heading or disconnect (with AutoReconnect disabled). Chat reports where the trail ended and where the player landed.
- **Build:** XaeroPlus `2.36.6+fabric-26.2` is compile-only and suggested in Fabric metadata, never bundled. Absent, incompatible or disabled detection reports a message instead of crashing. Its synchronous detector order and cache semantics were checked against the release source.
- 144 automated tests, including 34 new tracker, route-update, landing-policy and optional-dependency tests. In-game singleplayer and 6b6t testing is still required; see README.

## 0.11.0

One new module. The other modules are unchanged.

- **elytra-log:** while you're flying, logs you out once the elytra you're wearing has **durability** or less left (default 20) and there's no spare in your inventory. With **elytra-replace** on, a spare counts if elytra-replace would swap it in. It turns itself off after logging you out, and turns off Meteor's AutoReconnect so you aren't put straight back in the air. Settings: **durability**.
- 110 tests.

## 0.10.0

One new module. The other modules are unchanged.

- **stash-hunter-elytrafly:** Stash-Hunter flies with Meteor's ElytraFly instead of Baritone's firework flight.
  - Stash-Hunter still chooses where to go. Comet steers towards its next point and works ElytraFly's forward, jump and sneak keys to hold Stash-Hunter's flight height in Vanilla and Packet modes. In Pitch40 it only steers.
  - It takes off from the ground and stops a Baritone flight Stash-Hunter has already started.
  - While it steers, it skips Stash-Hunter's "Baritone has no path" stuck check.

  Settings: **turn-speed**, **take-off**.
- **Build:** two optional mixins into Stash-Hunter (`ElytraController.driveTowards` and `StuckDetector`), applied only when it is installed. `fabric.mod.json` suggests `stash-hunter`. 106 tests.

## 0.9.0

One new module. The other modules are unchanged.

- **elytra-replace:** when the elytra you're wearing has **durability** or less left (default 10), it is swapped for the elytra in your inventory with the most durability left. This also works mid-flight. Chat warns once if there's no spare. Meteor's ElytraFly has its own **elytra-replace** option, but it only works while ElytraFly is on. This module works without it, so it also covers baritone-elytra flights, which turn ElytraFly off. Settings: **durability**.
- 102 tests.

## 0.8.0

One new module. The other modules are unchanged.

- **baritone-elytra:** with it on, a long `#goto` (or Meteor's waypoint Go To) is flown by Baritone's own elytra pathfinder, then walked to the exact goal after landing. It fills in what `#elytra` doesn't do:
  - starts the flight from a walking trip
  - takes off from flat ground
  - walks the rest after landing (Baritone drops the goal when the flight takes control)
  - puts on an elytra and takes the chestplate back after landing
  - keeps fireworks in the hotbar, because Baritone only fires them from there
  - pauses Meteor's ElytraFly

  Flights started by `#elytra` or another addon (such as Stash-Hunter) only get take-off help and firework restocking.

  Settings: **min-distance**, **take-off**, **equip-elytra**, **swap-back**, **restock-fireworks**.
- **Build:** Baritone `26.2-20260813.152229-1` from Meteor's Maven is a compile-only dependency and is not bundled. `fabric.mod.json` suggests `baritone-meteor`. 95 tests.

## 0.7.0

Two new waypoint modules. Block Coat and Smart Sponge are unchanged.

- **xaero-waypoints:** shows Meteor waypoints on Xaero's Minimap and World Map, in their own dimension and, with Meteor's opposite option, at the scaled Overworld/Nether position. Display-only; nothing is written to Xaero's waypoint files. Settings: **show-on-minimap**, **include-hidden**.
- **waypoint-delete:** press **delete-key** (default middle mouse button) to delete the Meteor or Xaero waypoint whose name and distance are showing.
- **Build:** Xaero's Minimap `fabric-26.2-26.6.0` is a compile-only dependency from Modrinth's Maven and is not bundled. `fabric.mod.json` suggests Xaero's Minimap and World Map. 79 tests.

## 0.6.2

Fewer packets per placement, and gentler recovery from rubber-banding. No new features.

- **Packets:** new **max-packets-per-second** limit (default 150). New **extra-rotation-packets** (default off), which limits rotated placement to one per tick so no separate rotation packets are sent. New **auto-switch = Sticky** (new default) holds the block instead of switching to and back for every placement. Ordinary support faces are now preferred over containers (which cost two extra sneak packets).
- **Rubber-banding:** placements the server ignores during a position correction are retried without growing the retry delay or slowing the pacer.
- **Settings:** **rotate** is honoured in every server mode; previously Adaptive and Conservative ignored it. **placement-priority** and **prefer-nearby** are merged into **placement-order**. The powered-target check now only applies when the coating block is TNT.
- **Smart Sponge:** scores spots before ray-casting supports, so far fewer ray casts are needed.
- **Status:** the module list shows packets per second and the number of position corrections.
- **Code:**
  - Confirmation, retry and timeout handling moved into `PlacementTracker` with its own tests.
  - Removed unused `JumpController`, `PositionStability`, `PlacementOrder.order`, `ModeGeometry.strip` and the tests that only exercised them.
- **Build:** Loom pinned to 1.17.21 and Meteor to snapshot `26.2-20261009.074120-35`. README rewritten to describe current behaviour, with history moved here. 71 tests.

## 0.6.1

- **Container supports** default changed back to **On** on request (it had been made opt-in, see below). **Narrow gap targeting** stays Off. Saved settings keep their values.
- **Server regression rollback.** After reports on 6b6t of rejections, gaps, position corrections and kicks, container supports and narrow-gap targeting were made opt-in. Smart Sponge returned to ordinary inset face samples. Conservative mode was recommended for retesting. This was not a verified diagnosis.
- **Smart Jump / tunnel fix.** Tunnel mode used to reject the directly-underfoot jump target for not being behind the player. Underfoot placement is now exempt from the direction filter, both during discovery and before sending.
- **Detection and recovery:**
  - Recently sent placements are watched for 200 ticks, and a later authoritative disappearance triggers a retry.
  - Repeated rejection and timeout delays grow from the retry delay up to 40 ticks.
  - Retry expiry refreshes the target right away.
  - Visibility failures get a three-tick cooldown.
  - **Prefer nearby** (default on) overrode older saved Farthest priority.
- **Performance.** Movement keeps the overlapping candidate region and reads only newly entered cells.

## Earlier

- The module was renamed from **tnt-coating** to **block-coat**; settings and keybinds from the old module name needed reapplying.
- **Coating block** setting added (default TNT).
- **External flight detection** replaced the older external-flight setting.
