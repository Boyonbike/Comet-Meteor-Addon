# Auto Boat Fly

[Documentation index](../README.md) · [Project README](../../README.md)

Auto Boat Fly is Comet's continuous fixed-heading AFK boat controller. It owns the heading, forward movement,
helper activation and safety logouts, and drives with the same engine as [Boat Fly](boat-fly.md). It does not
route to coordinates, find boats or board automatically, and needs neither Quiettee Utils nor Angle Calculator.

## Start a trip

1. Sit in the boat's driver seat and open **Auto Boat Fly**.
2. Set **Trip → heading**, or press **Use current heading** to copy the way you face.
   Minecraft yaw is 0 south, 90 west, -90 east and ±180 north.
3. Check **Helpers**, **Food** and **Safety**, carry enchanted golden apples, and enable the module.
   Automatic travel starts immediately along the heading.
4. **Pause for positioning** hands steering back to you, exactly as in Boat Fly: WASD, jump to rise and sprint
   to descend. **Start automatic travel** resumes the heading. **start-pause-trip-key** does the same.

Mouse look does not change the travel heading. Angle Calculator is suspended while Auto Boat Fly drives and its
enabled preference returns when the trip stops. Enabling Auto Boat Fly turns Boat Fly off, and Highway Fly
and Auto Boat Fly hand over to each other when switching.

## Settings

| Section | Settings |
| --- | --- |
| **Trip** | **heading**, **start-pause-trip-key** |
| **Controls**, **Speed**, **Phasing** | Shared with Boat Fly; see [its settings](boat-fly.md#settings) |
| **Helpers** | **lobby-recovery**, **auto-set-home** |
| **Food** | **auto-eat**, **eat-health**, **eat-hunger**, **eating-failure-timeout-seconds** |
| **Safety** | Boat Fly's lava avoidance plus the logouts below |
| **Webhooks** | **webhook**, **webhook-url**, **mention**, **status-interval-minutes** |
| **Correction Recovery**, **Diagnostics** | Shared with Boat Fly |

The module screen shows the trip state, the heading next to the way you face, carried eGaps, a row for each
helper and the [Flight Dashboard](flight-dashboard.md) with a **Settings** button, the speed-fallback buttons,
and **Send test webhook** while webhooks are on.

## Helpers and food

Auto Boat Fly manages AutoWalk (Simple/Forwards), AutoEat, AutoSetHome and AutoLobby, and only during
automatic travel. Configure home names, cooldowns and override confirmation in **AutoSetHome**, and portal
recovery, login and reconnect in **AutoLobby**. Homes pause while recovering. AutoLobby temporarily controls
portal direction and movement, then the trip resumes its heading. If recovery leaves you on foot, it can walk
to the portal but waits for the driver seat before resuming. Servers must allow mounted portal travel.

With **auto-eat** on, **eat-health** (default 16) and **eat-hunger** (default 19) start AutoEat; either is
enough. eGaps and inventory searching are allowed. AutoEat pauses while a screen is open or the cursor holds
an item, preventing unsafe inventory clicks. Keep food in the hotbar or offhand, or leave an empty hotbar slot:
Meteor AutoEat needs one to move food out of the main inventory. At full hunger, ordinary food that cannot be
eaten is skipped so it cannot hide usable eGaps.

Borrowed AutoWalk, AutoEat and Angle Calculator settings and enabled preferences are restored when the trip
stops and are never written into saved profiles, nor are movement modules paused for the ride. Independently
enabled Comet helpers keep their own enabled preference.

## Safety logout

Auto Boat Fly ends automatic travel and disconnects when an enabled check triggers:

| Setting | Default behaviour |
| --- | --- |
| logout-on-low-egaps / egap-reserve | Log out at zero carried eGaps; raise the reserve to keep some |
| minimum-health | Log out at 8 red health points or below, excluding absorption; 0 disables |
| logout-in-lava | Log out if the player or boat is actually in lava |
| dismount-timeout-seconds | Log out after 30 seconds out of the driver seat outside recovery; 0 disables |
| no-progress-timeout-seconds | Log out after 60 seconds without a new furthest point at least one block along the heading; 0 disables |
| world-wait-timeout-seconds | Log out after 30 seconds waiting for an unexpected world or server to return; 0 disables |
| recovery-timeout-seconds | Log out after 300 seconds of total lobby/reconnect waiting; 0 disables; shown with lobby-recovery on |
| eating-failure-timeout-seconds | Log out after 15 seconds of eating demand without consumption or improved health/hunger; 0 disables |

Moving backwards or sideways, or rubberbanding to an old position, cannot reset forward progress. Health and
death packets are tracked per connection, so stale packets cannot affect a new session. Health, lava and death
protection runs even while waiting in an unexpected world. Supply counts include eGaps in the hotbar, main
inventory and offhand, but not ordinary golden apples or items inside containers. Death also ends the trip.

Recovery suspends supply and travel-timeout checks; health and lava checks stay active. A fresh join has a
short inventory-settling period. An unexpected world or server holds travel until the trip world returns, a new
trip is started, or its wait deadline expires. Recovery uses one deadline across stages and reconnect attempts,
which clears only after five seconds of ready driver-seat travel with new forward progress. A connection that
stays up for five minutes resets reconnect backoff.

A safety logout clears the boat recovery session and disables AutoReconnect, so the same unsafe trip cannot
restart immediately. The dashboard and webhook report the reason.

## Recovery and profiles

A brief loss of the driver seat keeps the trip marker during the dismount grace period; AutoWalk pauses and an
unexpected disconnect can still enter AutoLobby recovery. Pausing, stopping or a safety logout ends ownership.
Disconnecting with **lobby-recovery** off ends the trip.

Restarting from a saved profile needs a valid Auto Boat Fly marker, lobby recovery on and, when connected, the
same server; a saved toggle alone cannot start a trip. Paused trips and trips without a matching marker save as
inactive. The marker keeps the heading, including equivalent ±180° north headings. Singleplayer markers are bound
to a hash of the save directory, so another save in the same dimension cannot resume the trip.

## Dashboard and webhooks

Turn on [Flight Dashboard](flight-dashboard.md) to watch the trip from a browser. While Auto Boat Fly runs the
page shows its heading, facing, speed and speed mode, distance, trip and active time, eGaps, food, health,
helper and recovery states, logout limits, home schedule and events, and keeps a safety-logout reason visible
after disconnect.

**Webhooks** posts trip events and periodic status reports to Discord with heading, distance, speed and eGaps.
**mention** pings on safety logout, death and other attention events.

## Upgrading

Auto Boat Fly was called Boat Fly before the manual module was split out. Saved settings, the enabled state and
in-progress recovery markers carry over automatically. Dashboard settings move to Flight Dashboard.

## AutoLobby by itself

AutoLobby can also stay independently enabled. Without a saved trip it recovers toward **target-dimension**
(default `minecraft:the_nether`). Completing standalone recovery does not start Highway Fly or a boat trip.
