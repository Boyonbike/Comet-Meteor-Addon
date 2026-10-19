# Changelog

## Unreleased

- **Removed Dynamic Sprint:** Comet no longer upgrades Meteor's **Speed**. Its Vanilla mode is stock Meteor again, Speed is no longer listed under Comet, and the **Comet Dynamic Sprint** settings and **dynamic-sprint-engine** HUD element are gone.
- **Meteor build compatibility:** before applying an upgrade to a Meteor module, check from bytecode that the installed Meteor still has every method, field, constant and injection point it uses. A changed build now skips just that upgrade (the stock module keeps working, is not listed under Comet, and chat names it on joining) instead of crashing at startup; Adaptive Elytra Fly refuses to start without its hook. Require Meteor 26.1.2 build 42 or newer. A build-time test checks every Meteor upgrade against the pinned build.
- **Scaffold:** run Meteor's own Scaffold tick again instead of a copy, so upstream fixes apply; Comet now only replaces the fallback-support sort with a one-pass nearest selection, stops the tower check at the first collision, and adds sticky switching and confirmation retries around Meteor's placement.
- **HUD elements:** replace the fixed engine overlays and the **player-speed-hud** module with movable, scalable HUD elements in a **Comet** group: **adaptive-elytra-fly-engine** and **player-speed**. The **engine-hud** settings are removed. Player speed now uses real time, so Timer no longer skews them, and teleports no longer spike the reading.
- **Adaptive Elytra Fly:** write flight memory on a background thread, so saving after a correction never stalls a movement tick. Turning off **remember-server-limits** keeps the session's learned limits. Skip diagnostics formatting during play. Document **close-object-speed** and its Sprint override.
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
