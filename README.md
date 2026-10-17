# Comet by Boyonabike — 0.22.2

Meteor Client addon for Minecraft **26.2**. Open Meteor's **Comet** category and enable the modules you want: **adaptive-elytra-fly**, **fast-storage-esp**, **safe-fast-use**, **block-coat**, **smart-sponge**, **xaero-waypoints**, **waypoint-delete**, **baritone-elytra**, **elytra-replace**, **elytra-log**, **stash-hunter-elytrafly**, **area-spiral-search**, **trail-hunter**, **home-packet-log**. Requires Meteor 26.2, Fabric Loader 0.19.3+ and Java 25+; **xaero-waypoints** also needs Xaero's Minimap, **baritone-elytra** needs Baritone, and **stash-hunter-elytrafly** needs Stash-Hunter. **trail-hunter** needs XaeroPlus (and Baritone in the Nether); it works without Stash-Hunter. The Fabric mod ID stays `tnt-coating` for upgrade continuity; remove the old JAR before installing `comet-0.22.2.jar`.

Version history is in [CHANGELOG.md](CHANGELOG.md). Upgrading from 0.6.1? See [Upgrading](#upgrading-from-061).

## Adaptive Elytra Fly

Enable **adaptive-elytra-fly** in **Comet**, with Meteor's **Elytra Fly** set to **Vanilla**. If Vanilla is selected, enabling Comet's module in a world also enables Elytra Fly. Its **horizontal-speed**, **vertical-speed**, **fall-multiplier**, takeoff, autopilot, firework and inventory settings remain in Meteor. Horizontal speed is blocks per client tick: `3` requests about 60 blocks/second at the normal tick rate. Comet supplies its own acceleration and braking instead of Meteor's scalar acceleration settings.

Comet limits changes to the whole movement vector, including turns, and checks the actual blended trajectory before moving. It uses swept player boxes rather than a centre ray, floors negative chunk coordinates correctly, and checks intermediate chunks and the player's width. During adaptive control, missing chunks are always guarded, even if Meteor's **no-unloaded-chunks** is off. Descending contact with floor shapes below the feet is resolved by Minecraft so it can still land; walls and ceilings stay guarded. If a correction or unload leaves the current body already overlapping missing terrain or a block, **Terrain overlap** yields to Meteor until it is clear, allowing retreat instead of trapping every direction.

- **acceleration** (default **0.15**) and **braking** (default **0.3**) are changes in blocks/tick per tick. More braking permits a shorter stopping distance; higher acceleration makes steering sharper.
- **sprint-boost** defaults on. Hold your bound **Sprint** key while gliding and moving horizontally to skip the horizontal speed ramp up to the current adaptive ceiling. Meteor's configured horizontal speed, correction backoff/pauses and terrain stopping limits still apply. Steering and vertical movement remain smoothed; reversing brakes before boosting in the new direction. Release Sprint to return to normal acceleration behavior; it does not force a slower cruising speed. This uses the physical Sprint key, including a rebound mouse button, and only works while Minecraft is focused with no screen open. Automatic Sprint and toggle-sprint state do not activate it.
- **lookahead** (default **64**, range **8–64** blocks) and **terrain-margin** (default **0.35**) set the known-clear stopping corridor. The speed ceiling reserves the next movement plus stopping distance: `v + v² / (2 × braking) <= clear distance - margin`. Even in open terrain, the finite horizon imposes a ceiling: about 118 blocks/second with the defaults. Increase braking only after checking server acceptance and steering.
- **avoid-blocks** defaults on. Turning it off skips block collision reads unless Meteor's **no-crash** is on; chunk checks still run. Comet replaces Meteor's fixed-distance No Crash ray while it controls flight.
- **correction-backoff** (default **0.75**) multiplies a temporary speed ceiling after each received player-position correction, down to 10% of the configured request. **correction-pause** defaults to **5** ticks. After **recovery-delay** (**60** clean ticks), **recovery-rate** restores **0.0025** of the ceiling per tick. Corrections are accepted normally; Comet does not cancel them or add movement packets. Teleports received during controlled flight also count.

The module list shows current activity and observed horizontal progress over about one second. The settings panel shows the temporary ceiling, corrections, ticks actually limited by chunks or blocks, query counts and frame times. Frame average is smoothed; peak and frames over 50 ms are counted since activation/world change or **Reset Flight Diagnostics**. Resetting diagnostics does not remove correction backoff. These readings help distinguish server corrections, terrain pacing and rendering stalls; the controller does not change graphics settings or Minecraft's chunk renderer.

Terrain work is bounded: a current-body overlap check and one lookahead scan per movement tick, at most 40 swept segments including refinement, with non-loading chunk checks before block reads. Extra SELF movements in the same tick use a short guard without advancing acceleration again. No whole-world chunk scans or background chunk generation are performed.

Area Spiral Search, Trail Hunter and Stash-Hunter's Comet integration continue to steer through their normal Meteor keys/settings, so adaptive control applies to their Vanilla flights too. Comet's scripted landings and Meteor's Shift auto-hover yield to Meteor's original handling. Packet, Pitch40, Bounce, Freecam, non-gliding movement and stopped fluid flight keep their original handling. Disabling adaptive control leaves Meteor's Elytra Fly enabled and immediately returns its movement to Meteor. Baritone's normal firework flight is unaffected.

Start with Timer at **1×**, Vanilla horizontal speed **1.5–2.5** and low vertical speed, then compare retained progress and the diagnostics as you increase speed. These are starting values, not a verified 6b6t speed cap. Automated checks cover motion, stopping corridors, correction recovery and touchdown; live Minecraft/6b6t validation is still required for server acceptance, actual frame performance and interaction with other movement addons.

## Fast Storage ESP

Enable **fast-storage-esp** in Meteor's **Comet** category for shader outlines and glow around storage. This uses the actual container models, including double chests, with through-wall rendering. Enabling it switches off Meteor's original Storage ESP to avoid doing the same rendering twice. Its settings are independent; configure the colours and storage selection in this module.

Unchanged models stay in GPU buffers, grouped into 8-block cells. Offscreen groups are skipped; a separate storage depth buffer rejects hidden storage surfaces while keeping terrain transparent to ESP. Nearby fading happens in the shader, so moving the camera or player does not rebuild the stash. Storage changes and lid/shulker animation poses are checked five times a second, and changed geometry is prepared over several frames. New stashes appear progressively while their cache fills.

The usual **storage-blocks**, **shape-mode**, **fill-opacity**, **width**, **glow-multiplier**, colours, **fade-distance**, **tracers**, **hide-opened** and **opened-color** controls are available. **Clear Opened Containers** resets your interaction history. Unloading chunks, leaving a world, disabling the module and reloading resources release the corresponding caches.

- **max-distance** defaults to **0**, which includes all loaded storage. Set a range such as **128** or **256** blocks to reduce the work further.
- **rebuild-budget-ms** defaults to **2** milliseconds per frame, with a maximum of 128 models prepared per frame. Lowering it spreads loading over more frames; a single model or GPU upload can exceed the time target.
- Tracers default off. Wider outlines and higher resolutions still increase shader cost.

Automated checks cover cache invalidation and camera transforms. Live Minecraft testing is still needed to measure FPS on a large stash and check the shader appearance with your graphics setup.

## Safe Fast Use

Enable **Safe Fast Use** (**safe-fast-use**) in Meteor's **Comet** category to use it in place of Meteor's Fast Use. Hold Use to place blocks quickly. With **prevent-shulker-open** on (default), a shulker placed during that hold will not open from repeated use: release Use and click again to open it normally. This covers all shulker colours and either hand. Sneaking still lets you place against a shulker.

- **mode**: **All** (default) speeds up every item; **Some** speeds up selected **items**, plus all block items when **blocks** is on (default off).
- **cooldown**: delay between uses in ticks, default **0**. Items outside the Some filter keep the normal four-tick delay.
- **prevent-shulker-open**: turn off to allow the normal repeated interaction with newly placed shulkers.

Safe Fast Use's cooldown takes precedence while it is enabled, including when Meteor's original Fast Use is also on. Live Minecraft/server testing is still required, particularly placement from each hand, release and reclick, and sneaking against a box.

## Home Packet Log

**home-packet-log** records player packets for checking whether the server exposes another player's teleport destination. It needs only Meteor.

1. Join with the recording account near a second account you control.
2. Set **player** to the second account's exact name or UUID, or leave it blank to record all visible players. Enable the module before the test.
3. Run `/home` on the second account, wait a few seconds, then turn the recorder off. For comparison, you can record on the teleporting account too; its own `/home` command, position correction and acknowledgement are included.
4. Open the path printed in chat: `<minecraft>/meteor-client/comet/packet-logs/packet-<UTC time>-<id>.jsonl`. Each line is one JSON record.

Settings apply when the next capture starts:

- **player:** exact name or UUID, case-insensitive. World resets and the recording account's own corrections, acknowledgements and `/home`/`/homecancel` markers are always included.
- **relative-moves** (default on): include ordinary movement and rotation. Positions still update internally when this is off.
- **other-packet-types** (default off): also record the type of every incoming packet, including ones unrelated to the selected player. Their other payloads are omitted.
- **max-file-mib** (default 64): stop at this file limit. Each activation creates a new file; delete old captures yourself when finished.

Look for `entity_position_sync` or `entity_teleport` with the target's UUID and a distant **absolute_position**. That establishes that those coordinates were transmitted to your client; the packet alone does not establish which command caused the move. **entity_removed** records only the last received position, with **destination: "unknown"**. A tab removal is separate and does not establish why a player disappeared.

Records include original position/velocity fields, relative flags, packet class/type, time in Unix milliseconds, elapsed nanoseconds, sequence number, dimension and player identity. Interpret **position_change** with **relative_flags**. **reconstructed_position** is derived from earlier data, with **position_basis** set to `packet`, `client_snapshot` or `packet_estimate`. Relative teleports use an estimate because vanilla applies relative axes to its current interpolated client position. Absolute fields and flags remain as received. Relative movement uses Minecraft's fixed-point rounding and a separate codec base.

Capture reads packets before vanilla handling without cancelling or changing them. Existing players are snapshotted at activation; capture a player arriving after enabling when comparing exact relative movement, since packets might already be queued at activation. Removed identities remain available for late packets; new spawns replace reused IDs, and logins/dimension changes clear entity tracking in order. Same-dimension respawns preserve the other players. Caches hold up to 4,096 player entities and 8,192 tab names; unknown or evicted IDs cannot be matched to a player filter.

Writes use a bounded 8,192-record background queue and flush once a second. Turning the module off drains accepted records and writes a summary asynchronously; allow a moment before reading the file or closing Minecraft. The summary lists written/dropped records and the stop reason. Missing records, decoder/I/O errors and abrupt client shutdown limit what a capture establishes. Leaving the world stops capture; enable it again after joining. These are decoded client-visible fields, not raw wire bytes. In-game validation on 6b6t is still required.

## Block Coat

**Coating block** selects the block used by coating, scaffold, jump placement and tunnel backfill (default **TNT**). Keep it in your hotbar or offhand; Meteor's Auto Replenish can refill the hotbar. Changing it cancels deferred placements and rebuilds the search. Existing blocks of the selected type count as already coated. Blocks with special placement or gravity behaviour follow Minecraft's normal rules.

- **Normal coating:** one layer on eligible floor, wall and ceiling surfaces within the cube (radius 5 = 11×11×11). Feet/head-level targets must touch an eligible wall. Excludes blast-resistant/unbreakable terrain, waterlogged blocks, fluids and non-collidable vegetation; the optional Liquid Filler handles isolated source water separately.
- **Conservative TNT (default off):** open Block Coat's **TNT Saving** section, at the top of its settings, and enable **conservative-tnt** to save TNT during surface coating, including tunnel backfill. It estimates blast coverage using vanilla TNT rays at their weakest strength, accounting for block resistance, fluids and shielding. A placement is skipped only when every eligible terrain block beside it is covered by existing or queued TNT. Failed, cancelled or removed placements release their coverage. Smart Jump and scaffold still place their required blocks; other coating materials keep their normal behavior. This is a local coverage estimate at the placed positions, not a guaranteed minimum-TNT solution: primed TNT movement, explosion order, server changes and custom explosion rules can alter the result. The module still never ignites TNT. You can also read its value with `.settings block-coat conservative-tnt`, or enable it with `.settings block-coat conservative-tnt true` (use your Meteor command prefix if different).
- **Liquid Filler (default on):** **liquid-filler** replaces isolated plain water source blocks with TNT when **coating-block** is **TNT**, in both normal and conservative TNT modes. A source is isolated when none of its six face neighbours contains source water, including waterlogged blocks and aquatic plants. Diagonal sources and adjacent flowing water are allowed. Joined sources, flowing-water targets, lava, waterlogged blocks and plants are left alone. Filler uses the same scan radius, reach, tunnel direction, collision checks, redstone buffer and placement budget; it ignores the surface-type and terrain-height filters and can use visible solid supports such as bedrock or obsidian. Existing blast coverage does not suppress filling. Source isolation is checked again before sending; unknown neighbour chunks defer filling. Smart Sponge priority and the pause while you are in water still apply.
- **Pause items:** edit **pause-items** in General to choose items that pause all Block Coat placement and automatic switching while held in either hand. **Flint and steel** is included by default. **pause-items-timeout** defaults to **5 seconds** (adjustable from 0–60): coating resumes after this time even if a listed item is still held. Set it to **0** to pause until you switch away. Switching away resumes immediately; a fresh hold starts a new timeout. **pause-items-reset-on-use** defaults to **on**: using a listed item on a block, entity or in air restarts the countdown, even after it has expired. Continuous use (such as drawing a bow) keeps the timer refreshed; automatic Block Coat placements do not. Disable this toggle to count only from when you start holding an item. Changing directly between listed items keeps the current timer unless one is used. Selecting a listed item cancels queued, unsent placements. Clear the list to disable this pause. Applies to every auto-switch mode and to normal/conservative TNT, filler, scaffold and Smart Jump; the timeout leaves the selected auto-switch mode in control of switching.
- **Smart jump place (default on):** places a supported block directly below your current horizontal position once your body clears it, retrying while airborne (including descent) until one interaction is sent or you land. A server rejection allows another attempt in the same jump. Can use eligible terrain or an existing coating block as support. Surrounding placements yield until the jump is resolved. It never forces jumps or movement.
- **Scaffold (default off):** a strip below you that follows your horizontal movement, including diagonals. **Scaffold width** 1–9 (default 3); even widths have one extra cell on one side. The centre footpath, up to two blocks ahead, comes first and gets first use of every tick's budget; widening uses at most half of what remains, leaving the rest for coating. Coating keeps one pending slot free for the footpath. It cannot place without a reachable, visible support and cannot guarantee catching a fast-moving player over a gap.
- **Tunnel backfill (default off):** only targets at least 1.5 blocks behind your horizontal facing. Turning changes which side is behind; walking backwards, strafing or looking up/down do not. Facing is captured before Meteor aims at supports and rechecked before sending. Coats surfaces, not the tunnel interior, and still needs eligible terrain adjacency so repeated passes don't fill the tunnel. The behind-only rule also applies to scaffold; Smart Jump's underfoot placement is exempt. It applies outdoors too.
- **Flight:** coating works while flying (creative, elytra, Meteor Flight); these disable scaffold and smart jump. **Coat while flying** (default on) turns flight coating off, and also pauses ordinary airborne coating outside a detected jump. Enable **External flight detection** for another addon's flight, especially ground-spoofing flight; it marks flight as active (disabling scaffold even on the ground) but does not itself enable placement.

All modes keep player-body and entity collision checks, world-bound checks, visible-support and normal-reach checks. When the coating block is TNT, powered targets are skipped and a **two-block buffer in every direction** is kept around redstone components, even when they are off. This includes wire, torches, repeaters, comparators, observers, levers, buttons, pressure plates, tripwire, powered rails, sculk sensors and trapped chests. The buffer includes diagonals, vertical offsets and circuits behind walls. Unloaded terrain inside that buffer defers TNT placement until it can be checked. The check applies to coating, tunnel backfill, scaffold and Smart Jump, and runs again before a queued placement is sent. Other coating materials keep their normal behavior. Nothing is ever placed in unsupported air, and flight never extends reach.

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

## Area Spiral Search

**area-spiral-search** flies a smooth outward spiral using Meteor's **ElytraFly**, starting at your exact current X/Z position. It needs only Meteor and works in the Overworld and End. Put on a usable elytra, choose Vanilla, Packet or Pitch40 in ElytraFly, face the direction you want to start, and enable the module. Comet enables ElytraFly if needed.

- **custom-center** (default off): use the block centres at **center-x** and **center-z** instead. The flight reaches that centre before starting its spiral.
- **spiral-spacing** (default 256, minimum 128 blocks): distance between successive turns. **search-radius** (default 5,000, minimum 64 blocks): distance from the centre at which steering stops. **clockwise** defaults on, as viewed on a north-up map. The first outward leg follows your facing at activation. Centre, spacing, radius and direction are fixed for that activation.
- **flight-height** (default 200): target Y in Vanilla and Packet, raised when needed to stay 24 blocks above loaded terrain ahead. **turn-speed** defaults to 10 degrees per tick. **take-off** (default on) jumps and opens the elytra in these modes. Pitch40 receives yaw only and needs an existing glide; enabling ElytraFly in Pitch40 also needs enough height for its configured bounds. Bounce and Nether flight are refused.
- **paused**: turn on to release spiral steering and take-off while preserving this route's centre, direction and progress. Turn off to resume; **pause-key** optionally binds the same toggle to a key or mouse button (unbound by default, works with no screen open). The module list shows **Paused**. ElytraFly stays on and flight/gliding continues: control or land manually as needed. Pausing does not hover or land. The route timeout is suspended, and manual movement while paused does not count as search progress. Resuming first returns to the saved route point, then continues the spiral, and refreshes take-off attempts. A new activation always starts unpaused.

Choose spacing for your render distance and keep speed low enough to turn and load chunks. This module supplies a route; it does not detect bases or guarantee every chunk loads or is covered. To record storage along the flight, run Meteor's StashFinder or another scanner separately.

Stop Trail Hunter, Stash-Hunter flights and Baritone trips before starting. Area Spiral Search yields if one takes over later; a Baritone takeover also disables ElytraFly. It temporarily disables ElytraFly's **autoPilot** and **autoTakeOff**, restoring their previous values when it releases them. Other ElytraFly settings stay as you chose them.

Completion or switching the module off releases steering and leaves ElytraFly on with its previous automatic controls. Take over or land manually as needed; the module does not land. It also stops on death, an unusable elytra, ElytraFly being disabled or its mode/automatic controls changing, leaving or changing worlds, or 30 seconds without eight blocks of progress along the route or towards the centre or saved resume point while unpaused. Flight conflicts and the other stop conditions still apply while paused. This also catches circling when flight is too fast to make a tight turn: lower ElytraFly speed or increase spacing or turn speed. Toggling it on again starts a fresh route; reconnecting does not resume it. The module list shows the current spiral radius and target radius.

## Trail Hunter

**trail-hunter** (called trail-follow before 0.17.0) is an unattended base-hunting flight. It searches for unflown XaeroPlus old-chunk trails, follows each one outward to where it meets new terrain (often a base), logs that spot, and goes on to find the next trail. It does not need Stash-Hunter. To record the storage it flies over, run Meteor's StashFinder or Stash-Hunter's scanner alongside it. Install **XaeroPlus 2.36.6 for 26.2** with its matching **World Map 1.47.0**, **Minimap 26.6.0** and Fabric API. Other XaeroPlus versions are refused with a chat message. Comet bundles none of these mods.

Put on an elytra, face roughly the way you want to circle, and enable the module. In the Overworld, select ElytraFly Vanilla, Packet or Pitch40 first; Comet turns ElytraFly on if needed. Pitch40 needs you already gliding above its upper height bound. In the Nether, install Baritone with a native elytra pathfinder for your system and keep fireworks in the hotbar. **baritone-elytra** can restock fireworks and help take-off, but Trail Hunter doesn't need it. Also consider **elytra-replace** and **elytra-log** for long flights.

### How it hunts

With **keep-searching** on (the default):

- **A map, not arrival timing.** It keeps a local map of each chunk's XaeroPlus classification: trail, explicit new terrain, or unknown. New chunks feed the map as they arrive, and the cache within 384 blocks (512 in the Nether) is read once a second. Unknown chunks never count as either trail or new terrain.
- **Search.** Between trails, it steers towards the strongest visible band of unflown old chunks, up to 135° either side of its search heading. Old chunks the bot has already seen don't attract it. That means anything within about 112 blocks of a recorded flight: an earlier session's, or this session's once it has flown 160 blocks past them. With nothing worth approaching, it follows **search-pattern**.
- **Take a trail at any angle.** Once inside a band (four of the nine surrounding chunks are trail), it fits the band's centreline. It then turns along the band's axis, whatever angle it crossed at, and takes the direction with more unflown trail ahead. With **prefer-outbound** on, ties go to the direction away from 0,0. A band whose only unflown direction points within 60° of spawn is skipped, because trails lead out from spawn to bases.
- **Follow the centre.** Each tick it fits the band's two edges from cross-sections behind and ahead. When only one edge is in view (a band wider than view distance), it stays a learned half-width inside it. It aims at the centreline 96 blocks ahead, with at most 15° of correction. The axis turns at most about 0.75° per block, so gentle curves are followed and a hairpin is not. Trail ahead is measured lengthwise: each 16-block step counts once, however many chunks are in it. So a dense patch beside the trail cannot pull the flight off a long straight continuation.
- **Gaps and ends.** Unknown terrain ahead is a gap: it holds the bearing for up to 384 blocks. A trail ends only when explicit new terrain closes the band ahead: three 16-block rows of at least three non-trail lanes each, and no trail, within 144 blocks. A single fresh chunk or an unknown hole is not an end.
- **Leads.** A trail followed for at least 192 blocks that ends like this is a **trail end**, often a base. Chat reports it, and it's appended to `<minecraft>/meteor-client/comet/trail-leads/<world>.csv` with time, kind, x, z, heading and trail length. With **mark-trail-ends** (default on), it also adds a Meteor waypoint "Trail end N", which **xaero-waypoints** shows on Xaero's maps. A second strong branch where a trail splits is logged as a **fork**; **mark-forks** (default off) adds a waypoint for it too. A lead within 256 blocks of an earlier one of the same kind, from any session, is not reported again.
- **Leave and search again.** After a trail ends, it flies 96 blocks out through new terrain, preferring a small turn and the outbound side, then searches again.

**search-pattern** decides where to fly when no trail is in view:

| Value | Between trails |
| --- | --- |
| `SPIRAL` (default) | Circles 0,0 at your starting distance, in whichever direction is nearer the way you face, and widens by **spiral-spacing** (default 768 blocks) each turn. Trails radiate from spawn, so circling crosses them instead of running alongside them. After following a trail out to its end, the search heads back to its circle at 45° and crosses more trails on the way. Within 1,000 blocks of 0,0 it flies straight. |
| `STRAIGHT` | Holds your starting heading. |

**avoid-highways** (default on) ignores old chunks within 192 blocks of the axis and diagonal highways, more than 1,000 blocks from spawn. A trail that joins a highway is crossed as a gap and picked up on the other side, rather than followed along the highway for thousands of blocks.

### Never repeating a flight

Route memory records every chunk actually flown, and the direction it was flown in. A heading is rejected when a remembered route runs along or beside it, in either direction:
- within 8 chunks to either side and within about 50° of its direction, for at least 64 blocks along it;
- or directly underneath it (within 2 chunks) for 32 blocks.

The check covers 256 blocks ahead in the Overworld and 512 in the Nether. Crossing an old route at a steep angle is allowed, so a long session cannot fence itself in. Bands next to a recorded flight are not taken again.

When every heading repeats some history, the flight takes the least repetitive one and holds it for 192 blocks. If that happens four times within 3,000 blocks, the area has been flown out. It then flies 1,500 blocks outward along the least-flown heading and starts a new search circle there. It lands only when route memory is full or saving it fails.

Route files from 0.13 and 0.14 have no directions. Their chunks keep blocking every direction, except crossings steep enough for the geometry alone to show it.

### Detection

**trail-chunks** selects the detection signal:

| Value | Signal |
| --- | --- |
| `OLD_112` | XaeroPlus OldChunks: its heuristic for chunks generated in 1.12.2 or earlier. |
| `NON_FRESH` | A positive non-fresh result from Palette NewChunks. An unknown result never counts. |
| `EITHER` (default) | Either positive result, using whichever detectors are enabled. |

Enable the selected detector in XaeroPlus; with `EITHER`, at least one must be enabled. Explicit new terrain, which ends trails and gives bands their edges, comes from two places. One is a fresh Palette NewChunks result. The other is OldChunks' "modern" result, used when trails are 1.12 chunks only (`OLD_112`, or `EITHER` with Palette NewChunks off). OldChunks files every chunk it scans as either old or modern, so with `OLD_112` trail ends are recognised even in well-travelled modern areas where nothing is fresh.

These are generation-history heuristics, not proof of player activity. Broad non-fresh detection can include older grid scans and your own flights. XaeroPlus normally preserves a cached fresh result on a revisit; enabling rescan or clearing caches can change this. If broad detection is noisy on 6b6t, select `OLD_112`.

### Flight and safety

- **Overworld:** Pitch40 receives yaw only and uses its own height bounds. Vanilla and Packet also receive forward, climb and descent input to hold **flight-height** (default 200). They climb higher when needed to stay 24 blocks above the highest loaded terrain in the next 128 blocks. **turn-speed** defaults to 10 degrees per tick. **take-off** uses the same steering and take-off helper as stash-hunter-elytrafly.
- **Nether:** Baritone gets a point 500 blocks ahead at **nether-height** (default 80), normally at most once every 3 seconds. A new route needs a heading change of at least 10 degrees, or the existing endpoint to be within 200 blocks so a straight trail can continue. Baritone chooses its actual path through terrain. Every half-second, Comet also checks the next 512 blocks of that calculated path against route memory, skipping the part already flown. An unsafe detour triggers an immediate alternative, and nearby headings at that spot are rejected until the player has moved 128 blocks. ElytraFly stays off during and after this flight.
- **if-stuck** (default Disconnect): without 64 blocks of progress for 30 seconds, it retries take-off once. After 90 seconds it either warns, or disconnects with AutoReconnect turned off so an unattended player isn't left standing.
- **Chunk delivery:** trail ends can only be seen once the terrain ahead has loaded. If the chunk 48 blocks ahead is still missing in more than a third of checks over 30 seconds, chat says once to lower ElytraFly's speed. In simulation, flying at 2.5 times the chunk delivery rate lost almost every trail-end lead.
- **Status:** the module list shows the current activity (seeking, following with the trail length so far, crossing a gap, leaving a trail or a flown-out area) and how many trail ends this run has found.

### Single-trail mode

With **keep-searching** off, the flight follows one trail using arrival timing instead of the map. It locks after **lock-chunks** distinct trail chunks arrive within **lock-window** (defaults 10 in 5 seconds), fitting a forward line through them. It circles to recover after **lost-after** (default 5 seconds), and acts on **trail-end** after **give-up-after** (default 30 seconds) of total silence:

- **Land, Overworld:** level the pitch, temporarily use slow Vanilla flight, approach a loaded dry 3×3 landing area within 64 blocks while holding altitude, then descend. Unsafe surfaces, fluids, blocked descent columns and blocked approaches are rejected. If there is no usable area, it holds position and retries. On touching the ground it turns ElytraFly off, restores its saved settings and reports the landing coordinates. This is a controlled descent, not a terrain pathfinder. Turning the module off during landing also disables ElytraFly before restoring its settings.
- **Land, Nether:** stop extending the route and let Baritone choose and reach its landing spot. If every checked exit is blocked by history, the goal first moves to the current position, so the unsafe distant path is not followed during landing. Baritone's flight is not cancelled just because it changed its destination to that spot. Coordinates are reported after touchdown.
- **Heading:** keep flying on **fallback-heading** until the module is turned off. Minecraft yaw: 0 south, 90 west, -90 east, 180 north.
- **Disconnect:** turn off AutoReconnect, stop this module, and disconnect with the trail-end coordinates.

Continuous hunting also lands this way when route memory is full or cannot be saved.

### Other modules, worlds and files

Trail Hunter refuses to start during a Stash-Hunter flight or an existing Baritone trip. It releases control if Stash-Hunter or another Baritone trip takes over. Stash-Hunter's scanner can run without a `.sh start` flight: in the inspected 26.2 source, its scan interval runs independently of its flight controller. Leave `.sh start` and `.sh trail` stopped while Trail Hunter flies. Comet does not enable the scanner or change its settings.

Turning Trail Hunter off manually releases its inputs and cancels its own Nether route. The Nether landing phase, including a flight that has already changed to Baritone's landing destination, is left to finish. After a Nether flight, enable ElytraFly again yourself when ready. Leaving or changing worlds disables the module and keeps the saved route memory. Changing detection or **keep-searching** starts a fresh search without forgetting flown routes.

Route history is saved under `<minecraft>/meteor-client/comet/trail-routes/` as checksummed binary files. The lead log sits beside it in `trail-leads/`. Both are named with a hash of the server address (or singleplayer world folder) plus the dimension, so different dimensions and worlds have separate histories. Reusing the same server address or world folder keeps history across reconnects and client restarts. Connecting through a different server alias gives a different history. Only flights made while this module is active are recorded.

Background saves happen every 30 seconds and when the module stops, so an abrupt client crash can lose the most recent unsaved interval. Files are replaced atomically. Unreadable or corrupt existing history stops activation rather than silently starting empty. History never evicts old entries; at one million recorded chunks, the module lands. There is no automatic history reset.

This is local navigation using known classifications and flown corridors. It cannot see a trail before XaeroPlus classifies it, promise that an unseen trail exists, or guarantee how the server carries out a physical turn. The bridge reads classifications on the next client tick, after XaeroPlus's synchronous chunk listeners finish. Cache-startup retries keep the original chunk receipt time. Source checks: [XaeroPlus 2.36.6 release](https://github.com/rfresh2/XaeroPlus/releases/tag/2.36.6), [chunk event dispatch](https://github.com/rfresh2/XaeroPlus/blob/5bb9236657d7165f4b8c17f92beb88243e3e7276/common/src/main/java/xaeroplus/mixin/client/mc/MixinClientPlayNetworkHandler.java), [OldChunks](https://github.com/rfresh2/XaeroPlus/blob/5bb9236657d7165f4b8c17f92beb88243e3e7276/common/src/main/java/xaeroplus/module/impl/OldChunks.java), [PaletteNewChunks](https://github.com/rfresh2/XaeroPlus/blob/5bb9236657d7165f4b8c17f92beb88243e3e7276/common/src/main/java/xaeroplus/module/impl/PaletteNewChunks.java), and [Stash-Hunter scanner](https://github.com/Papxx/Stash-Hunter26.2-by-_og3/blob/main/src/main/java/com/stashhunter/stashhunter/modules/StashHunterModule.java).

### Simulation

The navigation is developed against a flight simulator in the test sources, which drives the same code the module uses. The simulator models:
- view-distance chunk loading capped like Paper's send rate;
- XaeroPlus classifying each chunk once and keeping the result;
- cache sampling once a second;
- a player that turns at the pilot's rate.

The simulated worlds have wide radial trails from spawn with bends, branches and a base at every trail end, plus cross-country trails, modern traffic, old-chunk noise and holes, and optionally highways. Over 48 seeded worlds and 30 simulated minutes each:
- `SPIRAL` hunting reached about 450 bases, spent about 63% of the flight over old chunks, and never needed to land.
- `STRAIGHT` reached about 125 bases.
- The 0.14.0 navigation, run in the same worlds, reached 34 bases with 10% of the flight over old chunks, and 14 of the 48 flights would have landed early.
- In worlds with highways, **avoid-highways** raised the count from about 325 to 515.

Run `TrailSimTune`, `TrailSimReport` or `TrailSimRender` (which draws a flight to a PNG) from the test classes to evaluate a change. The simulator is a model of radial trails, not of 6b6t's real layout.

### In-game checks still required

1. On 6b6t Overworld with `OLD_112`, fly a known trail and confirm it centres on the band, follows its bends and reports a trail end where it meets modern terrain. Compare against your existing old-chunk highlights.
2. Cross a trail at a steep angle and confirm it turns along it, outbound. Cross a highway and confirm it is crossed, not followed.
3. Run unattended for an hour: check the lead log and waypoints, that chat stays quiet between leads, and that **if-stuck** behaves (for example by landing it in water).
4. Test Land from Pitch40, Vanilla and Packet in single-trail mode, including no dry landing area, blocked approaches and cancelled landing. Verify saved ElytraFly settings are restored and that it is off after touchdown.
5. In the Nether, verify route updates stay throttled, straight trails extend, detours into remembered routes are rejected, and Baritone finishes landing without route resets. Check take-off and firework supply.

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

Run `./gradlew clean build` (`gradlew.bat clean build` on Windows). Output: `build/libs/comet-0.22.2.jar`. Gradle downloads the Java 25 toolchain if needed. Loom (1.17.21) and Meteor (snapshot build 35, `26.2-20261009.074120-35`) are pinned in `gradle/libs.versions.toml`, which explains how to move to a newer Meteor build. Xaero's Minimap (`fabric-26.2-26.6.0`) comes from Modrinth's Maven and Baritone (`26.2-20260813.152229-1`, the same jar as `baritone-meteor-26.2.jar`) from Meteor's Maven, as compile-only dependencies. XaeroPlus (`2.36.6+fabric-26.2`) is also compile-only from Modrinth; none is bundled. The Stash-Hunter hooks are mixins that need nothing at build time and are skipped when Stash-Hunter isn't installed.

429 automated tests pass. They cover:

- retained Storage ESP batches, 10,000-entry unchanged stashes, chunk unload/reload identity, camera movement at world-border coordinates and frustum bounds
- TNT redstone buffers around inactive components, vertical and diagonal bounds, unloaded terrain, build-height edges, changing power and non-TNT placement
- shulker opening protection during held Use, empty hands after the last placement, fresh clicks, sneak placement, unrelated containers and reset boundaries
- packet capture ordering, asynchronous drain, queue overflow, UTF-8 file limits, flushing and disk failures; exact movement decoding and real packet replays for late teleport coordinates, relative flags, codec bases, filters, respawns, command markers and malformed data
- placement tracking (confirmation, rejection backoff, timeouts, rubber-band losses, watched disappearances) and packet accounting
- coating rules, tunnel and flight policy, scaffold paths and facing
- face sampling, pacing, sponge absorption and priority, and addon metadata
- waypoint mirroring: dimensions, opposite-dimension scaling, hidden waypoints, colour matching and change detection
- Baritone elytra hand-off: when a trip is flown or walked, take-off timing and retries, and what happens after a flight ends
- elytra replacement: when the worn elytra is due, which spare is swapped in, and when there is none left and Elytra Log logs out
- adaptive ElytraFly: Sprint boost with preserved steering, reversal, vertical and terrain/correction limits; full-vector acceleration and release braking, correction pauses/backoff/recovery, immediate stock speed limits, bounded swept-body terrain checks, negative coordinates, blended turns, loaded-edge stopping and floor touchdown
- ElytraFly steering: compass-correct facing, turning the short way round, and when to climb or descend
- area spiral search: centre approach, clockwise/counter-clockwise geometry, turn spacing, physical progress without skipping rings, high-speed waypoint crossings, pause/resume with manual detours and bounded-turn flights to the radius limit
- single-trail following: straight and curved trails, forks, sparse noise, duplicate loads, delayed classifications, negative coordinates, gaps, recovery, exhaustion, reset, bounded history, and robust line direction
- Nether route throttling, extension on straight trails, heading wrap, and landing approach/hold/descent decisions
- loading the XaeroPlus bridge when XaeroPlus is absent from the runtime classpath, and explicit fresh, modern, negative and unknown chunk classifications
- trail hunting: taking a band at any crossing angle, outbound choice and skipping inward-only trails, centring from fitted edges, gaps versus explicit ends, lead rules, ignoring already-seen trails, forced exits, relocation, and the spiral search and its return
- direction-aware route memory: retracing in either direction or alongside versus steep crossings, brief hops, high-speed interpolation, touchdown/teleport/reconnect boundaries, version 1 and 2 files, atomic saves, corruption detection and world/dimension file separation
- calculated Baritone path detours, consumed prefixes, one-time departure grace, and bounded rejection of unsafe headings
- the lead log and highway geometry
- whole-flight simulations: trails crossed at every angle followed to their base, wide-band centring, bends, realistic worlds hunted without landing, and noise never followed

A Fabric prelaunch check confirms the adaptive flight mixin applies to Meteor 26.2-35. No gameplay in this release has been tested in Minecraft or on 6b6t. Placement timing, rotations, Sticky switching, container sneak-use and server acceptance all still need an in-game check, as do the Xaero markers, waypoint deletion, every part of Baritone Elytra (hand-off, take-off, restocking, chestplate swaps, landing and walking the rest), mid-flight elytra swaps, Elytra Log's logout, Stash-Hunter ElytraFly (hooks, steering, height keeping and take-off), Area Spiral Search (turning, centre approach, completion and handing control back), and Trail Hunter (live XaeroPlus detection, server steering, recovery, landing and Nether route updates).

Source follows the official Meteor addon template and API, under the included CC0 license.
