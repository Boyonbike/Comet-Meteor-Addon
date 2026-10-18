# Comet by Boyonabike — 0.23.9

Meteor Client addon for Minecraft **26.2**. Open Meteor's **Comet** category. Modules appear in this order, grouped by purpose:

- **Flight:** **adaptive-elytra-fly**, **baritone-elytra**, **stash-hunter-elytrafly**, **elytra-replace**, **elytra-log**.
- **Ground movement:** Meteor's built-in **speed**, with adaptive controls.
- **Rendering, storage and waypoints:** **fast-storage-esp**, **shulker-icons**, **trajectories**, **xaero-waypoints**, **waypoint-delete**.
- **Building:** **scaffold**, **block-coat**, **smart-sponge**, **safe-fast-use**.
- **Combat and inventory:** **auto-totem**, **inventory-tweaks**.
- **Diagnostics:** **home-packet-log**.

Requires Meteor 26.2, Fabric Loader 0.19.3+ and Java 25+; **xaero-waypoints** also needs Xaero's Minimap, **baritone-elytra** needs Baritone, and **stash-hunter-elytrafly** needs Stash-Hunter. Area Spiral Search and Trail Hunter have been removed. The Fabric mod ID stays `tnt-coating` for upgrade continuity; remove the old JAR before installing `comet-0.23.9.jar`.

**Speed**, **Scaffold**, **Trajectories**, **Auto Totem** and **Inventory Tweaks** are available in both Comet and their original Meteor categories. Both entries open and toggle the same module, sharing settings, keybinds and enabled state. Other Comet upgrades are also linked from a **Comet** dropdown inside the original module's settings:

| Original module | Comet upgrades in its dropdown |
| --- | --- |
| Elytra Fly | Adaptive Elytra Fly, Baritone Elytra, Stash-Hunter ElytraFly, Elytra Replace, Elytra Log |
| Storage ESP | Fast Storage ESP |
| Fast Use | Safe Fast Use |
| Waypoints | Xaero Waypoints, Waypoint Delete |
| Stash-Hunter (when installed) | Stash-Hunter ElytraFly |

Expand **Comet**, then left-click an upgrade to toggle it or right-click it to open its full settings. These links use the same module instances as the Comet category, so changes apply from either location.

Right-click a Comet module to open its settings, then click a section header or arrow to expand its dropdown. Setting sections start collapsed; saved section states are respected when loading a configuration. Adaptive Elytra Fly also has a **Flight Diagnostics** dropdown, and Fast Storage ESP puts **Clear Opened Containers** under **Actions**. The **Comet** sections added to Meteor's Scaffold and Trajectories also start collapsed.

Version history is in [CHANGELOG.md](CHANGELOG.md). Upgrading from 0.6.1? See [Upgrading](#upgrading-from-061).

## Adaptive Speed

Open **Movement → Speed** or **Comet → Speed** and select **Vanilla** mode. **Comet Adaptive Speed → adaptive-speed** is on by default. Turn it off to restore Meteor's original Vanilla movement; **Strafe** retains Meteor's movement either way.

The adaptive engine uses **adaptive-max-speed** instead of **vanilla-speed**. Its default ceiling is **40 blocks/second**, reduced by forward clearance, braking distance and server corrections. **Comet Speed Controls** sets **cruise-max-speed** to **35%** and **sprint-max-speed** to **100%** of that current maximum: in open terrain, up to **14 b/s** cruising and **40 b/s** while holding Sprint. Speeds assume 20 client ticks per second; Speed's **timer** can change real-time movement. Set **adaptive-max-speed** to zero to stop horizontal control immediately.

- **acceleration**, **braking** and **adaptive-turn-rate** control ramp-up, release/reversal braking and speed-dependent heading assistance. Releasing Sprint brakes toward the cruise target. Diagonals are normalized and pathing keeps its requested heading.
- Bind **brake-key** to hold a horizontal brake even with movement keys pressed. Jumping, falling and landing keep Minecraft's vertical physics.
- Meteor's **when-sneaking**, **only-on-ground** and **in-liquids** settings still apply. When sneaking is allowed, **sneak-max-speed** adds a 25% precision multiplier and keeps vanilla ledge protection. Flight, climbing, riding, Freecam, open screens and loss of window focus suspend adaptive control.
- **Comet Speed Terrain** bounds forward scans to **lookahead** (32 blocks by default, at most 64). Missing chunks always limit movement without being loaded. **avoid-blocks** slows for solid obstacles; **safe-maneuvering** allows vanilla collisions and steps at up to 6 b/s after braking.
- **Comet Speed Recovery** pauses and reduces the ceiling after server position corrections, then gradually restores it after clean ticks. Speed's Timer boost also backs off and is released during pauses, disable and mode/world changes.

Expand **Speed Diagnostics** to see the current maximum, cruise/Sprint targets, measured movement, corrections and terrain query counts. The engine advances acceleration once per player tick; extra movement events recheck their new corridor. Actual collision and ledge clipping feed back into the controller so it does not keep full momentum against a wall. Live movement feel and server acceptance still need an in-game check.

## Shulker Icons

Enable **Comet → shulker-icons** to add a small item icon over shulker boxes in inventory and container GUIs, including creative slots and the carried stack. By default it shows the item with the greatest total quantity inside the box, summed across slots. Ties use the first occupied slot. Empty boxes, or boxes whose contents the server does not send, show no icon. All shulker colours work.

The overlay defaults to half size in the top-right corner. Change **icon-scale** and **icon-corner** in General to adjust it.

Under **Combos**, click **Edit Combos → Add Combo**. Expand the combo, give it a name, select **required-items**, then click **Select Icon**. A match needs at least one of every selected item; extra contents are allowed. For example, require a netherite helmet, chestplate, leggings and boots, plus a totem, then select a diamond sword as the icon for that kit. Matching uses item types, ignoring damage, enchantments and custom names.

Each combo has a **Select Colour** button with a **Solid Colour** checkbox immediately beside it:

- **Unchecked:** use the original item design. **Select Icon** offers every normal item icon, including every block variant.
- **Checked:** use a flat silhouette in the selected colour, with a live preview. Blocks with matching loaded model geometry share one picker entry; block texture and colour variants do not repeat the same shape. Searching a grouped block's name still finds that shared entry. Distinct model shapes remain separate. The list is rebuilt when the picker opens to respect resource-pack changes.

Changing the colour stores it; the checkbox controls whether to use it. Switching the checkbox off restores the original item texture and keeps the chosen colour for next time. Changes refresh the preview and cached overlays immediately. The selected colour belongs to the combo, so two kits can use the same shape in different colours. Existing saved styles migrate to the corresponding checkbox state, and the automatic most-common-item fallback keeps its normal item icon.

Combos override the most-common item. The first enabled matching combo wins; **Move Up** and **Move Down** change priority. Disable or delete individual combos as needed. An empty required-items list never matches. With no matching combo, the normal most-common icon appears. Rules save with Meteor configurations and participate in module copy/paste and reset.

## Scaffold optimization

Use Meteor's existing **Movement → Scaffold**. Comet selects the nearest fallback support in one pass, preserving Meteor's ordering when supports are equally close, and stops the tower ceiling check at the first collision. Radius/air-place ordering and the original placement, rotation and tower settings remain available.

The module gains a **Comet** setting group:

- **sticky-switch** defaults off. Enable it with **auto-switch** to keep the block selected between placements, reducing slot switch packets. The block remains selected when Scaffold stops or is disabled; choose another slot manually when finished.
- **confirmation-retries** defaults on. Repeated attempts at an unconfirmed/rejected position wait 2, 4, then at most 8 ticks. Positions near the predicted player feet retry after 1, then at most 2 ticks. New bridge cells never wait for another cell. Applied server block updates release successful attempts; client prediction alone does not count as confirmation. Disable this setting to retain Meteor's attempt timing.

Retry history is bounded and clears on module/world changes. Live server testing is still needed for placement acceptance, rejected-block recovery, tower behaviour and actual frame/packet improvements.

## Trajectories optimization

Use Meteor's existing **Render → Trajectories**. Comet retains Meteor's projectile simulator, item filters, multishot and rendering settings. Unchanged predictions can be reused within the same client tick; changed aim, origin, item components, charge, movement or collision state requires a new prediction. Cached paths restart their drawing origin each frame, and fired projectiles retain an interpolated start.

The module gains a **Comet** setting group:

- **optimize-trajectories** defaults on. Turn it off to return to Meteor's original rendering/simulation behaviour.
- **remote-trajectory-range** defaults to **128** blocks, adjustable from **8–512**. Applies to other players and fired projectiles; your own held item is always included.
- **trajectory-steps-per-frame** defaults to **8,000**, adjustable from **128–64,000**. This bounds actual simulator steps across all paths, including when Meteor's **simulation-steps** is set to zero. Your held prediction gets priority; remaining work is shared between remote entities with a rotating starting order. Multishot shares its allowance between its three paths.

When the budget is tight, paths can be shorter or temporarily omitted. A stale collision result is hidden if there is no budget to refresh it. The budget limits simulation steps, not milliseconds or drawing cost; enabling position boxes and displaying many paths still increases rendering work. Actual FPS and visual behaviour need an in-game check.

## Inventory Tweaks transfers

Use Meteor's existing **Misc → Inventory Tweaks** and its Steal/Dump controls. Comet schedules transfer clicks on the client thread instead of sleeping on a background worker. Initial, normal and random delays retain their millisecond settings, with actions occurring on the next eligible frame or client tick. A stalled frame does not cause overdue clicks to burst all at once.

Each transfer belongs to one exact screen, menu, player and world. Closing/switching the container, leaving the world, disabling the module, starting a manual sort or picking up an item on the cursor cancels pending work. The source stack, filter and slot pickup permission are checked again immediately before a click, including delayed rotation callbacks. Repeated inventory-content packets coalesce into the current job. Zero-delay transfers share a maximum of eight actions per client tick across frames and requests.

Steal-drop works with or without **drop-backwards**; the original non-backwards path did not issue the drop. Live Minecraft testing remains needed for your server's click acceptance, repeated container changes and sort/transfer interactions.

## Auto Totem optimization

Comet automatically optimizes Meteor's existing **auto-totem** in the **Combat** category. Enable and configure that module normally; its Smart/Strict modes, health threshold, explosion/fall checks, elytra option, item count, Offhand lock and delay after a pop remain in Meteor.

Strict mode skips damage prediction. Smart mode also skips it when the configured elytra condition already applies or health plus absorption is already at/below the threshold. Otherwise it runs Meteor's current damage prediction on every eligible tick; threat checks are never cached or throttled.

Equipping from an ordinary hotbar/main-inventory slot uses one normal offhand SWAP click instead of two pickup clicks (three when returning a displaced offhand item). The swap preserves the cursor and returns the old offhand item to the source slot. Source slots are resolved from the open menu's actual player inventory, so chests and shulkers do not use the player's own inventory-menu slot numbers. Creative mode and unsupported/restricted slot layouts retain Meteor's original move handling. No equip action is sent when a totem is already in the offhand.

Regression checks cover decision equivalence and menu-slot resolution, and a Fabric prelaunch check verifies the mixin against Meteor 26.2-35. Live server testing is still needed for swap acceptance and repeated pops; this does not guarantee survival or a measured FPS improvement.

## Adaptive Elytra Fly

Enable **adaptive-elytra-fly** in **Comet**, with Meteor's **Elytra Fly** set to **Vanilla**. If Vanilla is selected, enabling Comet's module in a world also enables Elytra Fly. Set cruising and sprint maximum speeds in Comet's **General** dropdown. Meteor's **horizontal-speed** must be above zero to enable horizontal movement; its numeric value no longer sets the adaptive cruise speed. Meteor's **vertical-speed** still sets normal climb/descent input, subject to Comet's learned directional limits. Its **fall-multiplier**, takeoff, autopilot, firework and inventory settings remain available. Comet supplies its own acceleration and braking instead of Meteor's scalar acceleration settings.

Comet limits changes to the whole movement vector and checks the actual blended trajectory before moving. Above harmless speeds it also turns existing momentum toward your heading, so fast corners stop sliding sideways (**adaptive-turn-rate**). It uses swept player boxes rather than a centre ray, floors negative chunk coordinates correctly, and checks intermediate chunks and the player's width. During adaptive control, missing chunks are always guarded, even if Meteor's **no-unloaded-chunks** is off. Descending contact with floor shapes below the feet is resolved by Minecraft so it can still land. At higher speeds, walls and ceilings stay guarded; **safe-maneuvering** lets you move against them slowly, including from harmless block contact. If a correction or unload leaves an unsafe starting overlap or missing terrain, **Terrain overlap** yields to Meteor until it is clear, allowing retreat instead of trapping every direction.

- **acceleration** (default **0.15**) and **braking** (default **0.3**) are changes in blocks/tick per tick. More braking permits a shorter stopping distance; higher acceleration makes low-speed steering sharper. Vertical changes also obey **vertical-acceleration**.
- **adaptive-turn-rate** (default **15**, range **0–45** degrees per tick) is heading assistance that adapts to speed. Up to twice **acceleration** (0.3 blocks/tick, about 6 blocks/second, by default) steering stays pure momentum for precise tight-space control. Assistance blends in by six times **acceleration** (about 18 blocks/second), then rises with actual speed to double the setting at the adaptive engine's current maximum, so faster flight turns harder instead of drifting further. Corners keep their speed, speed changes still use **acceleration**, and reversals of more than 135° still brake first. With defaults, a 90° turn takes 5 ticks with about 5 blocks of sideways drift at 35% cruise, and 3 ticks with about 8 blocks at the 118 blocks/second maximum; plain momentum needed about 1 second and 19 blocks, and 2.8 seconds and 160 blocks. Zero restores momentum steering.
- **cruise-max-speed** (default **35%**) and **sprint-max-speed** (default **100%**) each range from **0–100%** of the adaptive engine's current attainable maximum. The scale comes from the engine's output after terrain, braking and learned correction limits, so **100%** selects everything it currently allows and lower percentages select a fraction of that speed. Before server learning, default settings and clear level terrain permit about **118 blocks/second**, making default cruise about **41 blocks/second**. Vertical Sprint uses its own learned climb/descent ceiling, also scaled by the selected percentage and bounded by terrain. The diagnostics show the current maximum, both selected horizontal speeds and separate learned vertical limits in blocks/second.
- **sprint-boost** defaults on. Hold your bound **Sprint** key while gliding to ramp toward **sprint-max-speed** using the configured **acceleration**. Sprint also boosts deliberate Jump/Sneak climb and descent, including when moving only vertically. Ascent and descent are learned separately, starting at **initial-vertical-speed** (**0.75 blocks/tick**, about **15 blocks/second**) on an unknown server. Clean boosted flight with **sprint-max-speed** at **100%**, near a directional limit, explores higher speeds in small steps of at most **0.05 blocks/tick**, separated by at least **40** clean ticks or **recovery-delay**, whichever is longer. **recovery-rate** controls this growth; zero disables it. Lower percentages use the current learned limit without raising it. Releasing movement brakes to a stop; releasing Sprint returns to **cruise-max-speed** and Meteor's normal vertical input. A zero Meteor horizontal speed still disables horizontal movement. This uses the physical Sprint key, including a rebound mouse button, and only works while Minecraft is focused with no screen open. Automatic Sprint and toggle-sprint state do not activate it.
- **vertical-acceleration** (default **0.05 blocks/tick per tick**) smooths climbing, descending, releasing vertical input and changing vertical direction. Vertical braking uses up to twice this rate, bounded by **braking**. A vertical correction can reduce both the relevant directional ceiling and the acceleration rate. Terrain stops, correction pauses and hard learned limits still take effect immediately.
- **lookahead** (default **64**, range **8–64** blocks) and **terrain-margin** (default **0.35**) set the known-clear stopping corridor. The speed ceiling reserves the next movement plus stopping distance: `v + v² / (2 × braking) <= clear distance - margin`. Even in open terrain, the finite horizon imposes a ceiling: about 118 blocks/second with the defaults. Increase braking only after checking server acceptance and steering.
- **avoid-blocks** defaults on. Turning it off skips block collision reads unless Meteor's **no-crash** is on; chunk checks still run. Comet replaces Meteor's fixed-distance No Crash ray while it controls flight.
- **safe-maneuvering** defaults on. At a full movement-vector speed below Minecraft's wall-damage threshold of **0.3 blocks/tick** (about **6 blocks/second**), bypass the block guard and Meteor's No Crash so Minecraft can resolve contact and let you turn or slide through tight spaces. This also applies after the guard has slowed you down: if the next acceleration step would be blocked, a crawl-speed fallback permits movement along or away from the wall. Extra movements in the same tick use the same low-speed bypass. Unloaded-chunk protection stays active. Turn this off to keep block guarding at low speed. **server-glide-guard** can still hold you back from a wall you face; see below.
- **server-glide-guard** defaults on and works with block avoidance (**avoid-blocks** or Meteor's **no-crash**). A vanilla server keeps simulating an ordinary elytra glide for you: every tick it moves that glide from your reported position, then puts you back. If that hidden move hits a wall faster than 0.3 blocks/tick, the server deals fly-into-wall damage however slowly you were really moving. 0.23.6 stopped 0.35 blocks from walls, inside that reach, which is the likely cause of the small hit when stopping at a wall. Comet now follows the server's glide (from takeoff, your sent rotation, attached rockets, corrections, motion and explosion packets) and keeps walls out of its next move: braking ends far enough away, a low-speed crawl stops at its reach, and if the hidden glide grows into a wall while you hover, you step back. Unloaded chunks in its path count as walls. A steady level glide reaches about 1.5 blocks/tick, so after a few seconds of flight a wall you face is held about 1.6 blocks away; glancing and side contact are unaffected. Full contact remains available while the hidden glide is harmless, such as shortly after takeoff, after a correction or after a harmless contact. If no reachable position is safe, the hit is counted instead of freezing you. Turn this off if a server does not simulate player glides.
- **correction-backoff** (default **0.75**) multiplies a temporary speed ceiling after each received player-position correction, down to 10% of the configured request. **correction-pause** defaults to **5** ticks. After **recovery-delay** (**60** clean ticks), **recovery-rate** restores **0.0025** of that temporary ceiling per tick. The engine also examines the last **20** ticks of actual motion and vertical input, so a delayed correction after releasing Jump/Sneak still lowers the relevant ascent/descent limit and vertical acceleration; level-flight corrections lower the horizontal limit. These learned ceilings do not automatically recover into a speed the server already rejected. Corrections are accepted normally; Comet does not cancel them or add movement packets. Teleports received during controlled flight still trigger temporary backoff, but relocations of more than **64 blocks** do not teach a permanent speed limit.
- **remember-server-limits** defaults on. Keep learned horizontal, ascent, descent and vertical acceleration limits across module toggles, reconnects and game restarts. Cleanly proven starting speeds are stored separately from ceilings learned after rejection: reconnecting resumes from proven speeds and can continue learning upward within those ceilings. Remote profiles are separated by server host, port and dimension; single-player profiles use world and dimension. A successful speed probe must remain clean for the feedback window before it is saved. **Forget This Server's Flight Limits** in **Flight Diagnostics** clears the current profile and restarts learning at the configured initial limits. The bounded memory file, `meteor/comet/adaptive-flight-limits.dat`, keeps up to **128** recently used profiles, expires learned data after **180 days**, checks corruption and replaces saves atomically. Unreadable or damaged memory starts fresh.

The module list shows current activity and observed horizontal progress over about one second. The settings panel shows the adaptive maximum, selected cruise/sprint speeds, temporary correction ceiling, learned horizontal/up/down limits and vertical acceleration, whether server memory is active, corrections, ticks actually limited by chunks or blocks, the estimated server glide speed with its limited ticks and predicted server wall hits, query counts and frame times. Frame average is smoothed; peak and frames over 50 ms are counted since activation/world change or **Reset Flight Diagnostics**. Resetting diagnostics preserves correction backoff and learned server limits. These readings help distinguish server corrections, terrain pacing and rendering stalls; the controller does not change graphics settings or Minecraft's chunk renderer.

Terrain work is bounded: a current-body overlap check and one lookahead scan per movement tick, at most 40 swept segments including refinement, with non-loading chunk checks before block reads. A blocked move can add a short chunk-only check for the crawl fallback. Following the server's glide costs one short collision check per gliding tick. The guard adds two per moving tick while that glide could hurt, and at most 26 in total when it has to shorten a move or step back near a wall. Extra SELF movements in the same tick use a short guard without advancing acceleration again. No whole-world chunk scans or background chunk generation are performed.

Stash-Hunter's Comet integration continues to steer through normal Meteor keys/settings, so adaptive control applies to its Vanilla flights too, using Comet's **cruise-max-speed** for horizontal speed. Comet's scripted landings and Meteor's Shift auto-hover yield to Meteor's original handling; Shift auto-hover can take priority over boosted descent. Packet, Pitch40, Bounce, Freecam, non-gliding movement and stopped fluid flight keep their original handling. Disabling adaptive control leaves Meteor's Elytra Fly enabled and immediately returns its movement to Meteor. Baritone's normal firework flight is unaffected.

Start with Timer at **1×**, a low **cruise-max-speed** percentage and low Meteor vertical speed, then compare retained progress and the diagnostics as you increase speed. Keep Meteor's horizontal speed above zero. These are starting settings, not a verified 6b6t speed cap. Live Minecraft/6b6t validation is still required for server acceptance, actual frame performance and interaction with other movement addons.

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

Run `./gradlew clean build` (`gradlew.bat clean build` on Windows). Output: `build/libs/comet-0.23.9.jar`. Gradle downloads the Java 25 toolchain if needed. Loom (1.17.21) and Meteor (snapshot build 35, `26.2-20261009.074120-35`) are pinned in `gradle/libs.versions.toml`, which explains how to move to a newer Meteor build. Xaero's Minimap (`fabric-26.2-26.6.0`) comes from Modrinth's Maven and Baritone (`26.2-20260813.152229-1`, the same jar as `baritone-meteor-26.2.jar`) from Meteor's Maven, as compile-only dependencies. XaeroPlus (`2.36.6+fabric-26.2`) is also compile-only from Modrinth; none is bundled. The Stash-Hunter hooks are mixins that need nothing at build time and are skipped when Stash-Hunter isn't installed.

628 automated tests cover:

- shulker icons: total item quantities across slots, deterministic ties, complete and partial combos, rule priority, disabled rules, empty contents, per-rule styles and colours, model geometry identity and colour isolation between draws
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
- adaptive ElytraFly: adaptive cruise/sprint percentages, Sprint acceleration and vertical boost, smooth vertical release/reversal, independent ascent/descent learning, delayed correction feedback, conservative speed probes and per-server persisted limits; low-speed wall manoeuvring and recovery after guarding, preserved steering, reversals, correction limits and unloaded-chunk protection; bounded swept-body terrain checks, negative coordinates, blended turns and floor touchdown
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

A Fabric prelaunch check confirms the adaptive flight mixin applies to Meteor 26.2-35. No gameplay in this release has been tested in Minecraft or on 6b6t. Placement timing, rotations, Sticky switching, container sneak-use and server acceptance all still need an in-game check, as do the Xaero markers, waypoint deletion, every part of Baritone Elytra (hand-off, take-off, restocking, chestplate swaps, landing and walking the rest), mid-flight elytra swaps, Elytra Log's logout, Stash-Hunter ElytraFly (hooks, steering, height keeping and take-off).

Source follows the official Meteor addon template and API, under the included CC0 license.
