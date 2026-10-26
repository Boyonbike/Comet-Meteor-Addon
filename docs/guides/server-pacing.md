# Server pacing and packets

[Documentation index](../README.md) · [Project README](../../README.md)

**Server mode:** Adaptive (default) starts at one placement per tick, ramps up after confirmations up to **server-rate-cap** (default 2) with at most **max-pending** (default 6) unconfirmed, and backs off on rejections. Conservative caps at one per tick and two pending. Fast uses **blocks-per-tick** without confirmation pacing. Adaptive and Conservative also keep a 0.15-block reach margin and avoid unconfirmed blocks as supports.

Every placement costs packets. The game itself sends about 40 per second while you move.

| Part of a placement | Packets |
|---|---|
| Use + swing | 2 |
| Switching to the block: Silent (to and back) / Sticky or Normal / already held or offhand | 2 / 1 / 0 |
| Each rotated placement after the first in the same tick | +1 rotation packet |
| Container support (sneak on, sneak off) | +2 |

- **rotate** (default on) aims at the support before placing, in every server mode. Turn it off only to test whether the server checks rotation.
- **extra-rotation-packets** (default off). Meteor sends the tick's first rotation inside the normal movement packet, and every further one as its own packet, which an anticheat can read as an extra tick. Off limits rotated placement to one per tick; turn it on to allow server-rate-cap/blocks-per-tick above one while rotating.
- **auto-switch = Sticky** (default) switches to the block once and holds it while placing, then switches back after one second idle (unless you picked another slot meanwhile). Silent switches to and back for every placement.
- **max-packets-per-second** (default 150) skips placements that would push everything you sent in the last second above the limit. ViaVersion's default sustained limit is 200.

**Position corrections (rubber-banding).** After a correction, Block Coat discards unsent placements and pauses for **correction-cooldown** (default 10 ticks). The server ignores placements that arrive before your client accepts its correction, so blocks already sent can disappear. Those are retried after the cooldown without growing the retry delay or slowing the pacer. Genuine rejections and timeouts still back off from **retry-delay** (default 3 ticks) up to 40 ticks, and a confirmation resets that. Recently placed blocks are watched for 200 ticks; if one disappears, it is retried.

The module list shows queued and pending placements, surfaces, packets per second and position corrections since enabling or the last world change (teleports such as /home count too). If placement stops at the packet limit, it shows `Packet limit`.

Suggested starting point on 6b6t: Adaptive, Sticky, extra rotation packets off, max packets 150. If you still rubber-band, compare standing still with walking, and container supports on with off. Compare the correction count over a few minutes each.
