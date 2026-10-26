# Build and tests

[Documentation index](../README.md) · [Project README](../../README.md)

Run `./gradlew clean build` (`gradlew.bat clean build` on Windows). Output: `build/libs/comet-0.26.26-mc26.1.2.jar` and the matching `build/distributions/comet-0.26.26-mc26.1.2-source.zip`. `./gradlew test` runs the automated tests on their own. Gradle downloads the Java 25 toolchain if needed. Loom (1.17.21) and Meteor (snapshot build 42, `26.1.2-20260810.135545-42`) are pinned in `gradle/libs.versions.toml`, which explains how to move to a newer Meteor build. Xaero's Minimap (`fabric-26.1.2-26.6.0`) comes from Modrinth's Maven and Baritone (`26.1-20260423.173134-2`, the same jar as `baritone-meteor-26.1.jar`) from Meteor's Maven, as compile-only dependencies; neither is bundled. CI runs the same build on every push and pull request.

Automated tests cover:

- Meteor compatibility: every Comet upgrade to a Meteor module fits the pinned Meteor build's bytecode, and removed methods, changed constants, missing injection points and missing classes are reported
- shulker icons: total item quantities across slots, deterministic ties, complete and partial combos, rule priority, disabled rules, empty contents, per-rule styles and colours, model geometry identity and colour isolation between draws
- retained Storage ESP batches, 10,000-entry unchanged stashes, chunk unload/reload identity, camera movement at world-border coordinates and frustum bounds
- TNT redstone buffers around inactive components, vertical and diagonal bounds, unloaded terrain, build-height edges, changing power, non-TNT placement, palette-cleared sections and section memoisation
- shulker opening protection during held Use, empty hands after the last placement, fresh clicks, sneak placement, unrelated containers and reset boundaries
- packet capture ordering, asynchronous drain, completion callbacks, queue overflow, UTF-8 file limits, flushing and disk failures; exact movement decoding and real packet replays for late teleport coordinates, relative flags, codec bases, filters, respawns, command markers and malformed data
- placement tracking (confirmation, rejection backoff, timeouts, rubber-band losses, watched disappearances) and packet accounting
- coating rules, tunnel and flight policy, scaffold paths and facing, and one-pass nearest-support selection
- face sampling, pacing, sponge absorption and priority, and addon metadata
- chat tools: command templates, server settings and their command-tree filtering, teleport timers, incoming requests with the server's own answer commands, player-chat detection, home names and friend highlighting
- inventory transfers: delays, coalescing, cancellation and abandoning an action whose rotation never runs
- wall-clock speed sampling under Timer, teleports and pauses
- dub counting: whole and half dubs, up to the largest chest count
- waypoint mirroring: dimensions, opposite-dimension scaling, hidden waypoints, colour matching and change detection
- Baritone elytra hand-off: when a trip is flown or walked, take-off timing and retries, and what happens after a flight ends
- Elytra Log: when the worn elytra is due and whether a spare can still replace it
- Auto Lobby: nearest loaded portal selection across chunk sections, horizontal and vertical bounds, negative coordinates, unloaded chunks, palette gating and stable tie-breaks
- adaptive ElytraFly: recorded flight feel (sixteen scripted flights under defaults and the user's saved settings must reproduce their per-tick movement and boost ramps exactly), setbacks that only rewind boost, overdrive and the shared 3D server limit; adaptive cruise/Sprint speed caps, boost caps that stop the fixed curve, maneuver mode's 3D cap (only when climbing and turning together, and re-ramping from the cap afterwards), Sprint acceleration and vertical boost, smooth vertical release and adjustable vertical reversal; low-speed wall manoeuvring and recovery after guarding, preserved steering, reversals and unloaded-chunk protection; bounded swept-body terrain checks, negative coordinates, blended turns and floor touchdown
- Highway Fly auto-set-home and webhooks: fixed-timer pacing, the server-cooldown buffer, auto-detect scheduling including the retry hold for refusals without a usable countdown, mention parsing and webhook payload content
- Auto Wasp: the server's per-packet limits (including two packets in one tick), VolytraFly's horizontal and vertical boost curves with caps, the 3D limit while climbing or descending (its hold, the ramps' shares and recovery from it), collision-only speed loss, setback rewind and hold, pinned and teleport corrections, settling after elytra swaps, paced takeoff, ping-aware target prediction, worn-out elytra and chestplate choice with swap hysteresis, the server-glide guard and steering against walls and unloaded chunks, and navigation endpoints

Automated tests pass against Meteor 26.1.2-42. In-game verification is still pending for the new Auto Set Home timing mode, its retry delay, its HUD and the webhook test action, placement timing, rotations, Sticky switching, container sneak-use and server acceptance, as well as the Xaero markers, waypoint deletion, every part of Baritone Elytra (hand-off, take-off, restocking, chestplate swaps, landing and walking the rest), Elytra Log's logout, Auto Wasp's pursuit, takeoff, chest swaps, setback recovery and server-glide guard, and Auto Lobby's waiting-area portal walk and resume.

## Documentation checks

From the repository root, run `python3 scripts/check_docs.py` and `python3 scripts/test_check_docs.py`. Source archives include the public `docs/` guides.
