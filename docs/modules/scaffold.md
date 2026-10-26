# Meteor Scaffold upgrades

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **scaffold**. Open **Meteor → Comet → scaffold**.

Use Meteor's existing **Movement → Scaffold**. Meteor's own Scaffold tick runs unchanged, so its fixes keep applying; Comet only replaces the sort of fallback supports with a one-pass nearest selection (preserving Meteor's choice when supports are equally close) and stops the tower ceiling check at the first collision. Radius/air-place ordering and the original placement, rotation and tower settings remain available.

The module gains a **Comet** setting group:

- **sticky-switch** defaults on (with **auto-switch**). It holds the block slot while Scaffold places and switches back after a second without placing, or when Scaffold turns off. Without it, Meteor switches to the block and back for every placement, which is two slot packets each. If you pick another slot while the block is held, Scaffold returns to that one instead.
- **extra-rotation-packets** defaults off. Meteor rides the first rotated placement of a tick on your movement packet and sends every other one as its own rotation packet. With **air-place** and a high **blocks-per-tick**, that tripled the movement packets the server saw and caused long rollbacks on 6b6t. Off, Scaffold places at most one rotated block per tick, nearest first (up to 20 blocks a second). **blocks-per-tick** still applies with **rotate** off.
- **setback-pause** defaults to 10 ticks. After a server setback Scaffold places only the block under you for this long. Servers often set you back again within a few ticks, and other placements in that window only add packets. Zero disables it.
- **confirmation-retries** defaults on. Repeated attempts at an unconfirmed/rejected position wait 2, 4, then at most 8 ticks. Positions near the predicted player feet retry after 1, then at most 2 ticks. New bridge cells never wait for another cell. Applied server block updates release successful attempts; client prediction alone does not count as confirmation. Disable this setting to retain Meteor's attempt timing.
- **log-setbacks** defaults on. Every server setback (rubber-band) while Scaffold is on adds one JSON line to `meteor-client/comet/scaffold-setback-logs`. Each Scaffold session gets its own file, opened at the first setback and capped at 4 MB; chat shows the path when it opens. A line holds where the server put you and how far (`correction*`), ping, sneak/sprint/jump input, tower state, Scaffold's settings, `extraRotationPacketsLastSecond`, and `pauseTicksLeft` (above zero when the previous setback's pause was still running). It also has the move the server rejected (`rejected*`: speed, vertical speed, on-ground flag, yaw/pitch snap of the sent rotation, blocks placed that tick) and how many ticks back the target position was (`targetTicksAgo`). `supportPlacedTicksAgo` shows whether you were standing on a block Scaffold had just placed. A 20-tick `timeline` lists each reported position relative to the target, with its ground flag, sent rotation and placements.

Retry history is bounded and clears on module/world changes. Live server testing is still needed for placement acceptance, rejected-block recovery, tower behaviour and actual frame/packet improvements.
