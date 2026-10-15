# Comet by Boyonabike — 0.6.1

Meteor Client addon for Minecraft **26.2**. Open Meteor's own **Comet** category/dropdown and enable **block-coat**. The Fabric mod ID remains `tnt-coating` for upgrade continuity; remove the old JAR before installing `comet-0.6.1.jar`. Requires Meteor 26.2, Fabric Loader 0.19.3+, and Java 25+.

## Smart Sponge

Open **Comet → smart-sponge**. This is a separate module from Block Coat. Put **dry sponges** in your hotbar or offhand; Meteor Auto Replenish can refill the hotbar.

- Searches all directions in a configurable cube (**search-radius**, default 5), limited to visible, reachable placement supports and valid, unobstructed target cells.
- Scores valid positions using connected drainable water, including ordinary waterlogged blocks, water sources/flowing water, kelp and seagrass. Models vanilla's six-step absorption traversal and 64-cell absorption cap; water displaced by the sponge itself also counts. Waterlogged solids are drained by adjacent placement, not replaced or mined.
- Chooses the highest estimated removal count; ties prefer the closer target. This is a best-next-placement heuristic, not a globally minimum sponge solution. Water flow/refilling, server changes and custom block behaviour can alter results.
- Places one sponge, waits for a server block update (up to 40 ticks), lets changes settle, then rescans. **scan-interval** defaults to 5 ticks. Deferred placements recheck water, support visibility, reach and player/entity clearance.
- **break-wet-sponges**, default **off**: when on, mines reachable, server-confirmed wet sponges placed by this module during its current activation. Does not mine pre-existing or untracked sponges. Turn it off to leave wet sponges in place. Tracking clears on disable, world change or disconnect. Drops must be picked up normally; it does not move toward drops, dry sponges or refill inventory. It uses the currently held tool for mining.
- No unsupported air placements, wall penetration or extra reach. Obscured water may require moving to expose a suitable support. When both modules are enabled, Smart Sponge evaluates first and takes priority while a placement is queued or pending, for two ticks after confirmation while water settles, and during optional wet-sponge collection. Block Coat cancels unsent deferred interactions and yields all coating/scaffold/jump placement during that time. Once priority clears it resumes automatically. No water target or no usable sponge does not itself pause coating. Rejection, timeout, disable and world-reset paths release the placement reservation. Already-sent interactions cannot be recalled.

Absorption rules were checked against the local Minecraft 26.2 SpongeBlock implementation. Tests cover empty water regions, traversal depth, absorption cap, dry/solid barriers, duplicate counting and coverage scoring. Placement confirmations, automatic mining and flowing-water outcomes still require in-game testing.

## Block Coat modes

**Coating block** selects the block used by coating, scaffold, jump placement and tunnel backfill. Defaults to **TNT**. Put the selected block in your hotbar or offhand. Changing it cancels deferred placements and rebuilds the search; server confirmations match the selected block. Existing blocks of the selected type are treated as already coated and do not generate additional coating layers. Original terrain exclusions remain in effect. Blocks with special placement or gravity behaviour still follow Minecraft's normal rules.

The module is now named **block-coat** (displayed as Block Coat). Because the module name changed, reapply settings/keybinds from the previous tnt-coating module if needed.


- **Normal coating:** one layer on eligible floor, wall and ceiling surfaces within the configured cube (radius 5 means 11×11×11). Feet/head-level targets must touch an eligible wall. Exclude blast-resistant/unbreakable terrain, waterlogged blocks, fluids and non-collidable vegetation. Existing selected blocks do not generate new normal-coating layers.
- **Smart jump place (default on):** prioritise a supported target directly below the player's current horizontal position once the body clears it. Retry scheduling while airborne, including descent, until one interaction is sent or the player lands. A server rejection allows another attempt while still airborne. Uses eligible terrain or existing TNT as support, without the ordinary terrain-adjacency/feet-head restrictions. Surrounding interactions yield while the jump opportunity is unresolved. No forced jumps or movement.
- **Scaffold (default off):** creates a strip below the player, following the horizontal movement trajectory including diagonals. **Scaffold width** is configurable from 1–9, default 3. The centre footpath is considered first up to two blocks ahead, before widening. Successive rows form the strip as the player moves. Even widths have one extra cell on one side. Can extend from existing TNT without direct terrain adjacency. Footpath placements get first use of every tick, including a one-block budget. Widening uses at most half the remaining capacity, leaving the rest for coating. Normal coating reserves one pending slot for scaffold when the pending window permits. Footpath rotations have higher priority than coating rotations. Does not place without a reachable, visible support, and cannot guarantee catching a fast-moving player over a gap.
- **Tunnel backfill (default off):** only permits targets at least 1.5 blocks behind the player’s horizontal facing direction. Walking backwards or strafing does not change which side is behind; turning does. Looking up/down does not change the horizontal filter. The restriction is rechecked before deferred interactions send, using facing captured before Meteor temporarily aims at support blocks. Coats surfaces, not the entire tunnel interior. Relaxes feet/head wall contact behind you and permits TNT as clickable support. Still requires adjacency to eligible terrain so repeated passes do not fill the tunnel solid. While tunnel mode is enabled, its behind-only restriction also applies to scaffold targets. Smart Jump’s directly-underfoot placement is exempt from the direction filter; normal body clearance, support, reach, flight, pacing and sponge-priority checks still apply. Turn tunnel mode off for forward scaffold building. This directional coating rule also applies outdoors; it does not classify enclosed tunnels.
- **Flight:** surface coating works airborne, including creative flight, elytra and Meteor Flight. These detected flight states disable scaffold and smart jump placement. Use **Coat while flying** to turn flight coating on/off (default on). This is rechecked immediately before deferred interactions execute. Off also pauses ordinary airborne movement outside a detected jump. Enable **External flight detection** when using another addon's flight, especially ground-spoofing flight; this separate switch marks flight as active but does not enable placement. Undetected ordinary airborne movement also suppresses scaffold unless it belongs to a detected jump. External flight detection disables scaffold even on the ground while enabled. The old external-flight setting is replaced: re-enable the newly named detection setting if you use it.

All modes retain player-body/entity collision checks, valid world bounds, powered-target exclusion, visible clickable support checks and normal interaction reach. Scaffold/jump can use existing TNT as support; otherwise support must satisfy the terrain block rules. They cannot place in unsupported air. Flight does not increase reach. Use Meteor's Auto Replenish for hotbar refill.

## Pacing and performance

Start with **Server mode = Adaptive**, **Server rate cap = 2**, **Max pending = 6**, **Blocks per tick = 8**, **Radius = 5**. Adaptive begins at one interaction per tick, ramps after confirmations, backs off on rejection and pauses after position corrections. Conservative caps at one interaction per tick and two pending. Fast uses the configured batch rate. Paced modes require rotation, reserve 0.15 blocks of reach margin and avoid unconfirmed TNT supports. Jump priority does not bypass server pacing or cooldowns.

Local updates refresh the changed cell and neighbours. Movement between block positions retains the overlapping candidate region and reads only newly entered cells. Full scans remain for settings/reset recovery and every 40 ticks. A persistent queue, cached/rechecked support points, and bounded discovery work avoid exhaustive visibility checks each tick. Pending blocks await authoritative server updates. The preview shows checked targets and pending interactions.

## Build and validation

Run `./gradlew clean build` (`gradlew.bat clean build` on Windows). Output: `build/libs/comet-0.6.1.jar`. Uses the Java 25 toolchain and Meteor snapshot 26.2-35.

75 automated tests pass, covering core geometry, forward-before-width ordering, diagonal/negative-coordinate paths, flight toggling and ground-spoof detection, strip widths/orientation, behind-player classification, facing-direction turns/cardinal directions/yaw wrapping, pacing, metadata, coating rules and existing jump-controller regression tests. The module's new live movement scheduling is compiled against the real APIs but has not been exercised in Minecraft. Live placement timing, flight interoperability and 6b6t compatibility need in-game testing.

Check a jump while moving sideways, crossing an edge with scaffold widths 1/3/4, turning a corner, tunnel coating behind you, and switching into creative/elytra/Meteor Flight with scaffold enabled. Confirm no scaffold placements occur during flight. Reach, server rejection, latency and rotation timing can prevent completing a placement before landing.

Source follows the official Meteor addon template and Meteor API, under the included CC0 license.

## Chest supports and narrow gaps

Block Coat skips interactive supports by default. Optional **Container supports** enables sneak-use for chests and similar blocks, restoring server input state afterward. Optional **Narrow gap targeting** enables near-edge samples. It defaults off, restoring ordinary inset face points and reducing discovery ray casts. A far-side target still requires a visible support face within normal reach; a completely opaque wall cannot be placed through. This does not override tunnel mode, player clearance or terrain exclusion rules. Smart Sponge retains its own support rules. Narrow-gap geometry is regression-tested; chest interaction and server acceptance have not been tested in-game.

When explicitly enabled, chest sneak-use sends the server input/use/restore sequence without changing local shift state or running local container-open prediction. The normal Minecraft interaction sequence is allocated through a registered game-mode accessor. Local crouch/camera state is untouched; the server still receives a temporary sneak state, so server-side or remote animation behaviour depends on timing. Placement appears after the server update. Runtime hook and visual behaviour still need an in-game check.

Sponge/coating coordination is automatic; enable both modules. The same priority applies regardless of Block Coat’s selected material. Regression tests cover queued reservations, confirmation settling, consecutive sponge operations and reset release. Live server timing remains untested.

## 0.6.1 server regression rollback

After reports of rejection, gaps, corrections and kicks on 6b6t, both chest-related behaviours are opt-in under Server Pacing: **Container supports = Off**, **Narrow gap targeting = Off**. Defaults use the earlier non-container, inset-face targeting path; no extra sneak-input packets are sent for it. Smart Sponge also defaults to ordinary inset face samples. Sponge priority and the remaining modes are retained. For an initial server retest use **Server mode = Conservative**, which caps interactions at one per tick and two pending; saved mode settings are not overwritten. This rollback is not a verified diagnosis or guarantee of server acceptance.

## 0.6.1 Smart Jump / tunnel fix

Tunnel mode previously rejected the directly-underfoot target for not being behind the player. Its direction filter now exempts Smart Jump underfoot placement, both during discovery and deferred send validation. Forward coating and scaffold remain excluded in tunnel mode. Three regression tests cover this exception and preservation of the ordinary tunnel restriction. No live Minecraft/6b6t test was run.

## 0.6.1 detection, recovery and nearby ordering

- **Prefer nearby = On** by default overrides older saved Farthest priority. Eligible retries use the same distance order as fresh targets. Turn it off to use the older Placement priority setting. Ordering refreshes on candidate changes and every five ticks; the queue cursor still advances between refreshes.
- Recently sent placements are observed for up to 200 ticks while within the radius. A later authoritative disappearance, including after initial confirmation, triggers a fresh local check and delayed retry. This also covers recent blocks removed by another player; updates do not identify why a block disappeared.
- Repeated rejection/timeout delays grow from the configured retry delay up to 40 ticks. Confirmation resets the failure count. Retry expiry refreshes the target instead of waiting for a periodic full scan. Normal support, visibility, movement and server pacing checks still apply.
- Movement retains overlapping candidates. One axis-aligned block step at radius five checks 121 entering cells instead of rereading 1,331 target cells. This is a world-read reduction, not an in-game FPS benchmark; the coordinate loop and neighbour checks still have cost.
- Visibility failures get a three-tick discovery cooldown, cleared on relevant block changes and scan-origin movement. Cached successful support points are still revalidated before sending. Server position corrections retain their cooldown; client-predicted ghost blocks are never force-deleted to permit a retry.

75 tests pass. Regression coverage includes bounded retry delays, near-first ordering and cube overlap counts. End-to-end server rollback/placement timing and FPS have not been measured on 6b6t. Existing sponge priority, Smart Jump tunnel exception and opt-in chest behaviour remain.

## Container default in 0.6.1

**Container supports now defaults On**, as requested. **Narrow gap targeting remains Off**. Saved settings retain their values: if upgrading with Container supports saved Off, enable it once in Block Coat → Server Pacing. This default supersedes the earlier rollback defaults described above.
