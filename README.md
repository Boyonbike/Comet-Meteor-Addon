# Comet by Boyonabike — 0.11.0

Meteor Client addon for Minecraft **26.2**. Open Meteor's **Comet** category and enable the modules you want: **block-coat**, **smart-sponge**, **xaero-waypoints**, **waypoint-delete**, **baritone-elytra**, **elytra-replace**, **elytra-log**, **stash-hunter-elytrafly**. Requires Meteor 26.2, Fabric Loader 0.19.3+ and Java 25+; **xaero-waypoints** also needs Xaero's Minimap, **baritone-elytra** needs Baritone, and **stash-hunter-elytrafly** needs Stash-Hunter. The Fabric mod ID stays `tnt-coating` for upgrade continuity; remove the old JAR before installing `comet-0.11.0.jar`.

Version history is in [CHANGELOG.md](CHANGELOG.md). Upgrading from 0.6.1? See [Upgrading](#upgrading-from-061).

## Block Coat

**Coating block** selects the block used by coating, scaffold, jump placement and tunnel backfill (default **TNT**). Keep it in your hotbar or offhand; Meteor's Auto Replenish can refill the hotbar. Changing it cancels deferred placements and rebuilds the search. Existing blocks of the selected type count as already coated. Blocks with special placement or gravity behaviour follow Minecraft's normal rules.

- **Normal coating:** one layer on eligible floor, wall and ceiling surfaces within the cube (radius 5 = 11×11×11). Feet/head-level targets must touch an eligible wall. Excludes blast-resistant/unbreakable terrain, waterlogged blocks, fluids and non-collidable vegetation.
- **Smart jump place (default on):** places a supported block directly below your current horizontal position once your body clears it, retrying while airborne (including descent) until one interaction is sent or you land. A server rejection allows another attempt in the same jump. Can use eligible terrain or an existing coating block as support. Surrounding placements yield until the jump is resolved. It never forces jumps or movement.
- **Scaffold (default off):** a strip below you that follows your horizontal movement, including diagonals. **Scaffold width** 1–9 (default 3); even widths have one extra cell on one side. The centre footpath, up to two blocks ahead, comes first and gets first use of every tick's budget; widening uses at most half of what remains, leaving the rest for coating. Coating keeps one pending slot free for the footpath. It cannot place without a reachable, visible support and cannot guarantee catching a fast-moving player over a gap.
- **Tunnel backfill (default off):** only targets at least 1.5 blocks behind your horizontal facing. Turning changes which side is behind; walking backwards, strafing or looking up/down do not. Facing is captured before Meteor aims at supports and rechecked before sending. Coats surfaces, not the tunnel interior, and still needs eligible terrain adjacency so repeated passes don't fill the tunnel. The behind-only rule also applies to scaffold; Smart Jump's underfoot placement is exempt. It applies outdoors too.
- **Flight:** coating works while flying (creative, elytra, Meteor Flight); these disable scaffold and smart jump. **Coat while flying** (default on) turns flight coating off, and also pauses ordinary airborne coating outside a detected jump. Enable **External flight detection** for another addon's flight, especially ground-spoofing flight; it marks flight as active (disabling scaffold even on the ground) but does not itself enable placement.

All modes keep player-body and entity collision checks, world-bound checks, visible-support and normal-reach checks. When the coating block is TNT, targets next to a redstone signal are skipped, because powered TNT ignites as it is placed. Nothing is ever placed in unsupported air, and flight never extends reach.

**Supports.** **Container supports** (default on, under Server Pacing) lets chests and other interactive blocks act as supports by sneak-using them: the server briefly receives a sneak input around the placement, while your local crouch and camera are untouched. These blocks only appear once the server confirms them. Because each costs two extra packets, an ordinary face is always preferred when one is usable. **Narrow gap targeting** (default off) adds near-edge face samples for gaps around chests and partial blocks. Neither overrides tunnel mode, player clearance or terrain rules, and a fully opaque wall can never be placed through.

## Smart Sponge

A separate module. Put **dry sponges** in your hotbar or offhand.

- Searches a cube (**search-radius**, default 5) for valid, unobstructed target cells with a visible, reachable support.
- Scores each spot by the connected drainable water it would absorb: water sources and flowing water, waterlogged blocks, kelp and seagrass. It models vanilla 26.2's six-step absorption traversal and 64-cell cap, counting water displaced by the sponge itself. Waterlogged solids are drained by adjacent placement, never replaced or mined.
- Places the highest-scoring reachable spot (ties go to the closer one). This is a best-next-placement heuristic, not a minimum-sponge solution; flowing water and server changes can alter results.
- Places one sponge, waits for the server update (up to 40 ticks), lets it settle, then rescans (**scan-interval**, default 5 ticks).
- **break-wet-sponges** (default off) mines only server-confirmed wet sponges this module placed during the current activation, with the currently held tool. Drops must be picked up normally.

When both modules are on, Smart Sponge takes priority while a sponge is queued or pending, for two ticks after confirmation while water settles, and while collecting wet sponges. Block Coat cancels its unsent interactions and resumes automatically afterwards. Having no water target or no sponge does not pause coating.

## Xaero Waypoints

A separate module, **xaero-waypoints**, that shows your Meteor waypoints on Xaero's Minimap and World Map. It needs Xaero's Minimap (built against 26.6.0). The World Map draws its waypoints through the minimap, so they appear there too, for whichever dimension the map is showing.

- Each waypoint appears in its own dimension. With Meteor's **opposite** option on, it also appears in the other of Overworld and Nether, at the same ×8 position Meteor uses. Its colour is the closest of Xaero's 21 waypoint colours, and its symbol is the first letter of its name.
- Adding, removing or editing a waypoint in Meteor shows up within half a second. They are display-only: Xaero never writes them to its waypoint files, and they disappear when the module is off. Change them in Meteor. Deleting one from Xaero's menus only hides it in Xaero ("Show Deleted 3rd-Party WPs" brings it back); use **waypoint-delete** or Meteor to delete it for real.
- **show-on-minimap** (default on). Xaero also draws minimap waypoints in the world, so you'll see Xaero's marker as well as Meteor's. Turn this off to keep them on the world map only, or turn off Meteor's **waypoints** module to see only Xaero's.
- **include-hidden** (default off) also shows waypoints whose Meteor visibility is off.

The module list shows how many waypoints are shown. Without Xaero's Minimap it says so and does nothing. If a later Xaero version changes the parts Comet uses, the module turns itself off with a chat message instead of crashing the game.

## Waypoint Delete

A separate module, **waypoint-delete**. Look at a waypoint until its name and distance appear, then press **delete-key** to delete it. The default is the middle mouse button; any key or mouse button, with modifiers, can be bound. Chat shows what was deleted and where.

- **Meteor waypoints** need Meteor's **waypoints** module on. They follow its rule for when a name shows: within **text-render-distance** pixels of the crosshair, not faded out up close, and within the waypoint's max visible distance. If several names show, the one closest to the crosshair is deleted.
- **Xaero waypoints:** the one Xaero highlights in the world, among your own waypoints (deathpoints included). It is removed the same way as from Xaero's menu and saved. Server waypoints and other mods' waypoints (such as Waystones) are left alone.
- A Xaero marker added by **xaero-waypoints** deletes the Meteor waypoint behind it.
- If a Meteor and a Xaero waypoint both show details, the one nearer the crosshair is deleted. Nothing happens while a screen is open or the HUD is hidden (F1).
- When something is deleted, the press is used up: middle click won't also pick a block or trigger Meteor's middle-click modules. When nothing is targeted, the press works as normal.
- There is no undo. Recreate the waypoint from the coordinates in chat.

## Baritone Elytra

A separate module, **baritone-elytra**. It needs Baritone: Meteor's `baritone-meteor` build, which Comet is built against. With it on, a `#goto` or `#path` longer than **min-distance** (default 300 blocks) is flown instead of walked, and Baritone walks the last stretch after landing. Meteor's waypoint **Go To** counts too. The flying is Baritone's own elytra pathfinder (the one behind `#elytra`): vanilla elytra physics and fireworks, steering around terrain, in the Overworld, Nether and End. Comet supplies what `#elytra` lacks for everyday trips.

- **Hand-off.** As soon as Baritone starts walking a long trip, it is given to the elytra pathfinder if all of these hold:
  - Baritone supports elytra flight on your CPU and operating system.
  - You have an elytra with at least Baritone's `elytraMinimumDurability` left, worn or (with **equip-elytra**) in your inventory.
  - You have more fireworks than Baritone's `elytraMinFireworksBeforeLanding`.
  - You are on the ground with 3 blocks of open air above your head and not in water or lava, or you are already gliding.

  Otherwise Baritone walks and chat says why. If something is above you, it keeps walking and takes off once there is open sky. A trip ends up walked when it is out of Baritone's elytra bounds, such as above the Nether roof with `elytraAllowAboveRoof` off.
- **take-off** (default on). On its own, Baritone only opens the elytra while falling fast, so on flat ground it waits forever. Comet jumps and opens the elytra near the top of the jump, sending the same packet as a double jump; Baritone then fires the first firework. It makes up to 3 attempts, 3 seconds apart, then walks. If Baritone finds no flight path within 10 seconds, it also walks. Comet doesn't do this when Baritone's `elytraAutoJump` is on, because Baritone then walks to a drop by itself.
- **Landing.** Baritone looks for a safe spot near the goal. If you land within min-distance of it (at least 128 blocks), Baritone walks the rest. Landing further away means Baritone landed early because fireworks or elytra durability ran low. Comet stops and says how far short you are; `#goto` again to carry on, which walks if you are still short of fireworks.
- **equip-elytra** (default on) puts on your best elytra for the flight. **swap-back** (default on) puts the chestplate it replaced back on after landing.
- **restock-fireworks** (default on) moves a stack of fireworks into the hotbar whenever the hotbar has none. Unless its `allowInventory` setting is on, Baritone fires fireworks only from the hotbar, yet counts the whole inventory when deciding whether to land. Without restocking it can run dry with fireworks left in the inventory. Comet uses an empty hotbar slot, otherwise slot 9 (8 if 9 is selected); the item there goes to where the fireworks were.
- Meteor's **ElytraFly** is turned off for the flight and back on afterwards. Baritone only works with vanilla elytra movement.
- `#goto` or `#goal` during a flight redirects it (Baritone does this), and Comet walks to the new goal after landing. `#stop` cancels a flight; in the air you keep gliding with nobody steering.
- Flights started some other way, with `#elytra` or by another addon such as Stash-Hunter, only get take-off help and firework restocking. Comet doesn't walk after them, change your elytra or chestplate, toggle ElytraFly or report how they ended; whatever started the flight handles those.
- Turning the module off stops a flight that hasn't taken off. One already in the air keeps going until it lands or you `#stop` it.

The module list shows `Taking off`, then the remaining distance in blocks.

Baritone's own elytra settings still apply; change them with `#set` or in Meteor's Baritone settings. Examples:
- `elytraFireworkSpeed` and `elytraConserveFireworks` control firework use.
- `elytraAllowAboveBuildLimit` (default on) flies Overworld and End trips longer than `elytraLongDistanceThreshold` (default 500 blocks) in a straight line above the build limit.
- `elytraAllowAboveRoof` (default off) keeps Nether flights below Y 128.
- `elytraPredictTerrain` (default off) only helps if you know the server's Nether seed.

## Elytra Replace

A separate module, **elytra-replace**. Once the elytra you're wearing has **durability** or less left (default 10), it is swapped for the elytra in your inventory with the most durability left. The worn one goes into that elytra's slot. It works mid-flight too: the chest slot always holds an elytra during the swap, so you keep gliding. Chat shows each swap.

- Only a worn elytra is replaced; a chestplate is left alone. Spares can be in the hotbar or main inventory, not the offhand.
- A spare needs more than **durability** left to be swapped in. If there is none, chat warns once for that elytra.
- It waits while a chest or other container is open, or while you're holding an item on the cursor, and swaps once that clears.
- For Baritone elytra flights, keep **durability** above Baritone's `elytraMinimumDurability` (default 5). The swap then happens before Baritone lands early to save the elytra.
- Meteor's ElytraFly has its own **elytra-replace** option, but it only works while ElytraFly is on, and **baritone-elytra** turns ElytraFly off during flights. This module works with or without ElytraFly.

The module list shows how many spare elytras have more than **durability** left.

## Elytra Log

A separate module, **elytra-log**. While you're flying, once the elytra you're wearing has **durability** or less left (default 20) and there's no spare elytra in your inventory, it logs you out. What's left is what you glide down with after logging back in, about one point per second of gliding.

- A spare counts only if it will be put on. With **elytra-replace** on, that's a spare with more than elytra-replace's **durability** left: elytra-replace swaps it in when it's due, so you stay online. With elytra-replace off, a spare needs more than this module's **durability**, and you swap it yourself. Like elytra-replace, it looks at the hotbar and main inventory, not the offhand.
- It only acts while you're gliding, or in the air with ElytraFly's Packet mode. Standing or walking with a worn-out elytra doesn't log you out.
- After logging you out it turns itself off, so opening the elytra after you rejoin doesn't log you out again. It also turns off Meteor's AutoReconnect, which would put you straight back in the air.
- You rejoin where you logged out, in mid-air. Open the elytra straight away and glide down.
- For Baritone elytra flights, keep **durability** above Baritone's `elytraMinimumDurability` (default 5), or Baritone lands before you're logged out.

## Stash-Hunter ElytraFly

A separate module, **stash-hunter-elytrafly**, for the Stash-Hunter addon. It is built against Stash-Hunter 0.26.2, the 26.2 port on its `main` branch. With it on, Stash-Hunter still decides where to go: grid strips, new-chunk trails and edge following. Meteor's **ElytraFly** does the flying, instead of Baritone's firework flight. Set ElytraFly's mode to Vanilla, Packet or Pitch40 and start Stash-Hunter as usual.

Stash-Hunter moves the player in one step, which either starts Baritone's elytra flight (fireworks) or sets your speed itself. While the module is on, Comet takes that step over. It turns you towards Stash-Hunter's next point at up to **turn-speed** degrees per tick (default 10) and works ElytraFly's keys. Each mode behaves differently:
- **Vanilla:** holds forward, plus jump or sneak to stay within 2 blocks of Stash-Hunter's flight height (`flightAltitude`, default 160). ElytraFly's **horizontal-speed** and **vertical-speed** set how fast.
- **Packet:** holds forward, plus jump whenever you're below the flight height, because Packet sinks whenever jump is up.
- **Pitch40:** only steers. ElytraFly keeps you between its **pitch40-lower-bounds** and **pitch40-upper-bounds** (default 180–220) rather than at Stash-Hunter's height. You must already be gliding above the upper bound, because Pitch40 can't take off.
- **Bounce** is for highways and doesn't suit this; chat says so.

Other behaviour:
- **take-off** (default on): when Stash-Hunter sets off from the ground, Comet jumps and opens the elytra, in Vanilla and Packet. It makes up to 3 attempts, as baritone-elytra does.
- On taking over, Comet stops any Baritone flight or walk Stash-Hunter has already started, and turns ElytraFly on if it is off. Stash-Hunter turns ElytraFly off itself when it stops.
- Keys Comet holds are given back to your keyboard when Stash-Hunter stops moving or the module is turned off.
- Stash-Hunter's **StuckDetector** treats "Baritone has no path" as being stuck and toggles ElytraFly every 10 seconds to recover. Baritone isn't flying here, so Comet skips that check while it steers. The detector's check for a gliding player who isn't moving still runs.
- Turning the module off mid-flight hands movement back to Stash-Hunter on its next tick. With Stash-Hunter's `useBaritonePathing` on (its default), that means Baritone's firework flight again.
- No rockets are fired unless ElytraFly's own **auto-pilot** with **use-fireworks** is on.
- The hooks attach only when Stash-Hunter is installed. If a later Stash-Hunter version renames the movement step, chat says so after 5 seconds of flight, and Stash-Hunter flies the way it did before.

The module list shows `Taking off`, then the distance to Stash-Hunter's next point (or `ElytraFly off`). Whether ElytraFly's Vanilla or Packet modes get past 6b6t's anticheat is untested; Pitch40 stays closest to normal elytra physics.

## Server pacing and packets

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

## Performance

Block updates refresh only the changed cell and its neighbours. Moving one block reuses the overlapping region and reads only newly entered cells (121 instead of 1,331 at radius 5). A full rescan still runs on settings changes, resets and every 40 ticks. Support points are cached and revalidated before sending. Ray casts are capped per tick, and positions that fail visibility wait three ticks. Smart Sponge scores all spots first and ray-casts supports only in score order. **placement-order** (default Nearest) sorts candidates, including retries, by distance.

## Upgrading from 0.6.1

- **placement-priority** and **prefer-nearby** are merged into **placement-order** (default Nearest). Old saved values are not carried over.
- **auto-switch** keeps your saved value (probably Silent); select **Sticky** to save two packets per placement.
- **rotate** now also applies in Adaptive and Conservative. If you had it saved off, those modes now place without rotating.
- New under Server Pacing: **max-packets-per-second**, **extra-rotation-packets**. With extra rotation packets off, Adaptive places at most one rotated block per tick (it previously could ramp to two).

## Build and tests

Run `./gradlew clean build` (`gradlew.bat clean build` on Windows). Output: `build/libs/comet-0.11.0.jar`. Gradle downloads the Java 25 toolchain if needed. Loom (1.17.21) and Meteor (snapshot build 35, `26.2-20261009.074120-35`) are pinned in `gradle/libs.versions.toml`, which explains how to move to a newer Meteor build. Xaero's Minimap (`fabric-26.2-26.6.0`) comes from Modrinth's Maven and Baritone (`26.2-20260813.152229-1`, the same jar as `baritone-meteor-26.2.jar`) from Meteor's Maven, both as compile-only dependencies; neither is bundled. The Stash-Hunter hooks are mixins that need nothing at build time and are skipped when Stash-Hunter isn't installed.

110 automated tests pass. They cover:
- placement tracking (confirmation, rejection backoff, timeouts, rubber-band losses, watched disappearances) and packet accounting
- coating rules, tunnel and flight policy, scaffold paths and facing
- face sampling, pacing, sponge absorption and priority, and addon metadata
- waypoint mirroring: dimensions, opposite-dimension scaling, hidden waypoints, colour matching and change detection
- Baritone elytra hand-off: when a trip is flown or walked, take-off timing and retries, and what happens after a flight ends
- elytra replacement: when the worn elytra is due, which spare is swapped in, and when there is none left and Elytra Log logs out
- ElytraFly steering: compass-correct facing, turning the short way round, and when to climb or descend

Nothing in this release has been run in Minecraft or on 6b6t. Placement timing, rotations, Sticky switching, container sneak-use and server acceptance all still need an in-game check, as do the Xaero markers, waypoint deletion, every part of Baritone Elytra (hand-off, take-off, restocking, chestplate swaps, landing and walking the rest), mid-flight elytra swaps, Elytra Log's logout, and Stash-Hunter ElytraFly (hooks, steering, height keeping and take-off).

Source follows the official Meteor addon template and API, under the included CC0 license.
