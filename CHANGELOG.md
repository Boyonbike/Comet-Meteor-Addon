# Changelog

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
