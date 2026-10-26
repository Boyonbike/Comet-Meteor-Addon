# DEVILS Auto Wasp

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **auto-wasp-devils**. Open **Meteor → Comet → auto-wasp-devils**.

Enable **Comet → auto-wasp-devils** to pursue the nearest loaded player with elytra.
This is the DEVILS Auto Wasp port: it retains A* obstacle routing, local avoidance,
emergency braking, stuck recovery, target movement prediction, a three-axis offset,
and the Nether flight-height cap: nothing above y 123 in the Nether counts as open.
It jumps and opens equipped wings like a second press of Jump near the top of the jump,
and retries a refused open after half a second (a tenth in water or lava) instead of
every few ticks. Meteor's stock Auto Wasp remains available and links to this port in
its settings.

**How fast it flies.** Like Adaptive Elytra Fly's boost, pursuit flies to what a vanilla
server accepts, taking 6b6t's limits to be the same. The server only checks how far each
move packet travels: one packet a tick may cover about 17 blocks, but when lag or ping jitter delivers two in the same server
tick the second fails above 12.25 blocks per tick (245 b/s). It never checks slowing down
or turning. So:

- **horizontal-max-bps** (default **240**, up to **300**) caps level flight. 240 is the
  fastest that is never set back for distance, even when packets bunch. Up to 300 is
  faster but set back whenever two packets arrive together, which the ramp then rewinds.
  The old DEVILS default was 40 b/s; set **horizontal-max-bps** to 40 for roughly the old
  pace.
- **3d-max-bps** (default **180**) caps total 3D speed while pursuit climbs or descends
  (faster than 1 b/s), and for half a second after, so a target bobbing up and down cannot
  make it flicker. Keep it below **horizontal-max-bps**, so climbs and dives fly slower than
  level flight. While it applies, both boost ramps are held to it, the vertical one keeping
  its share, and afterwards they speed up again from it along the curve instead of
  snapping back to the horizontal limit.
- Pursuit boosts on VolytraFly's curve: horizontally from **60 b/s** at once, gaining
  **ramp-step** (default **0.3**) per tick at rest and tapering towards 300 b/s, so it
  reaches 240 b/s in about 3.5 seconds. **vertical-max-bps** (default and maximum **60**)
  caps climbing and descending, which boost from 30 b/s each time a climb turns into a
  descent or back.
- Because slowing down is never checked, pursuit flies at the ramp's speed and lands on
  each waypoint, or on the target, on the final tick instead of braking early. Slowing
  down there keeps the ramp's speed for the next stretch; as in VolytraFly, only a real
  collision, a setback or the 3D limit costs ramp speed. Its distance to the next waypoint caps each
  tick's move along the route, so it does not overshoot into terrain the route has not
  checked.
- While the target is in plain sight, pursuit flies straight at it, instead of at a
  waypoint that can be a whole path refresh (up to 1.6 seconds) old.
- A server setback rewinds both ramps by **setback-rewind-ticks** (default **15**, under
  1% near top speed) and holds there for **setback-hold-ticks** (default **1**). When two
  setbacks in a row put you back on the same spot, pursuit glides with plain vanilla
  physics for **pinned-glide-ticks** (default **10**, 0 disables) until the server accepts
  a move. A correction more than 64 blocks away is a teleport and starts a new route.
- After an elytra goes on, pursuit leaves the new glide to vanilla physics for a quarter
  of a second, so the server settles it before boosting.
- The local obstacle avoidance from DEVILS keeps the sideways push it was tuned for at
  2 blocks/tick (40 b/s), rather than scaling it up with **horizontal-max-bps**.

The module list shows **3D limited**, **Unpinning**, **Settling glide**, **Glide
clearance** or **Taking off** beside the target while they apply.

**predict-movement** (default on) leads the target by its movement over your ping plus
the three ticks other players' movement takes to show, at most 8 ticks, measured from
where the client shows it rather than the velocity the server last sent. When flying
straight at the target, pursuit also matches its speed instead of trailing behind.

**min-distance** (default 0, up to 20) holds a horizontal standoff from the target so
you can place end crystals instead of ramming it. The aim is unchanged: prediction,
offset, avoid-landing and the safe corridor still choose the height, and the pathfinder
still plans to the same target. When you are closer than the standoff, pursuit holds a
ring that many blocks from the target at the aim's height instead of following a route
closer; 0 disables. Horizontal offset does not move the ring; vertical offset still
changes its height. If the ring is blocked it may hold rather than route around it.

**Server Glide.** A vanilla server keeps simulating an ordinary glide from each position
you report, and a wall hit in that hidden move deals damage however slowly you were
really moving. **server-glide-guard** (default on) follows that glide, including rockets,
corrections, motion and explosion packets, and keeps walls and unloaded chunks out of
its next move. **steer-server-glide** (default on) reports a level heading away from a
nearby wall, so the hidden glide turns away and pursuit can still fly close to walls.
Your camera does not move; other players see your head turned away while it steers.
These are Auto Wasp's own settings and code, independent of Adaptive Elytra Fly's.

**only-friends** restricts pursuit to friends. With **friend-filter** enabled,
turning **only-friends** off instead selects enemies; with both off, any valid
player can be selected. **keep-searching** defaults on and reacquires targets.
Turn it off to use **action-on-target-loss** (disable, choose another target, or disconnect).

**auto-chest-swap** defaults on: it equips a chestplate near the target when solid
terrain is close below, and equips elytra again for pursuit. It uses your inventory
without chat commands and pauses swaps in containers, with a carried cursor item,
or during Comet tool/weapon inventory loans. Turn it off to manage equipment manually.
An elytra with 1 durability left cannot glide, so it counts as missing and is swapped
for a usable one. Once the chestplate is on, every chest-swap range is
**chest-swap-hysteresis** (default **1.5**) blocks wider before the elytra goes back on,
so a target at the edge of a range does not swap you back and forth; 0 swaps back at the
same ranges.
The module disables active stock Auto Wasp, Elytra Fly and Baritone Elytra when it
starts so movement controllers do not compete. Keep those modules off during pursuit.
It pauses movement control for Freecam and disables on leaving a world. Route
searches run on the client thread with a 4 ms budget; past it, pursuit follows the
best partial route found so far instead of stalling the tick.

Credits are always shown at the bottom of the module settings: **ThianYong-hub and
DEVILS contributors**, Meteor Development, GNU GPL v3, the upstream source and revision,
and Comet port details. The full licence and provenance are bundled in the JAR as
**LICENSE** and **THIRD_PARTY_NOTICES.md**. Build with Java 21 using
`sh gradlew build`; distribute the matching **build/distributions/*-source.zip**
alongside the JAR to supply the modified corresponding source and build inputs.
The port has regression coverage for navigation, the speed ramp, setbacks, takeoff,
chest swaps, prediction and the server-glide guard; in-game pursuit, inventory swaps
and 6b6t's acceptance of these defaults still need gameplay verification.
