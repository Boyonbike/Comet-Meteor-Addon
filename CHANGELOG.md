# Changelog

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
