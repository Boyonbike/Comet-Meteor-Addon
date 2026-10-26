# Baritone Elytra

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **baritone-elytra**. Open **Meteor → Comet → baritone-elytra**.

A separate module, **baritone-elytra**. It needs Baritone: Meteor's `baritone-meteor` build, which Comet is built against. With it on, a `#goto` or `#path` longer than **min-distance** (default 300 blocks) is flown instead of walked, and Baritone walks the last stretch after landing. Meteor's waypoint **Go To** counts too. Baritone's own elytra pathfinder (the one behind `#elytra`) plans the route and steers around terrain in the Overworld, Nether and End. Comet adds adjustable flight, takeoff help and inventory handling.

Expand **Elytra Fly** in Baritone Elytra's settings:

- **elytra-fly** (default on) powers the entire flight with Adaptive Elytra Fly's movement engine, with no fireworks needed or consumed. Comet jumps and opens the wings, boosts automatically during travel, slows the final approach and controls descent through touchdown. Baritone chooses the route and landing spot. Turn it off for vanilla flight physics, which requires fireworks.
- **use-adaptive-settings** (default on) matches Adaptive Elytra Fly's configured boost speed caps and ramp steps, even while that module is off. Its acceleration, braking, turn assistance, maneuver cap and setback recovery also apply. Terrain and unloaded chunks can slow flight. Baritone does not send split moves, so the ceiling is 340 b/s even if Adaptive Elytra Fly allows more.
- Turn **use-adaptive-settings** off to set **speed-bps** (default 300), **vertical-speed-bps** (default 60), **boost-ramp-step** (default 0.3) and **vertical-ramp-step** (default 0.5) separately. These use the same curves as Adaptive Elytra Fly: lowering a speed cap stops the ramp sooner without changing its shape. Baritone's pitch sets the climb or descent slope within the vertical cap.

The adjustable mode uses Baritone's movement heading, including with free look, and stays active through approach and landing. It yields when fresh steering is unavailable. It needs usable elytra, but your rocket inventory can be empty. While this mode is enabled in a world, Comet temporarily disables Baritone's low-firework reserve check and normal firework boosts; it also blocks forced rocket use before item consumption. Your previous Baritone settings are restored when the mode or module is disabled or you leave the world. Low elytra durability and normal destination landing still apply. Live server flight and route tracking need in-game verification.

- **Hand-off.** As soon as Baritone starts walking a long trip, it is given to the elytra pathfinder if all of these hold:
  - Baritone supports elytra flight on your CPU and operating system.
  - You have an elytra with at least Baritone's `elytraMinimumDurability` left, worn or (with **equip-elytra**) in your inventory.
  - With **elytra-fly** off, you have more fireworks than Baritone's `elytraMinFireworksBeforeLanding`. Adjustable flight skips this requirement.
  - You are on the ground with 3 blocks of open air above your head and not in water or lava, or you are already gliding.

  Otherwise Baritone walks and chat says why. If something is above you, it keeps walking and takes off once there is open sky. A trip ends up walked when it is out of Baritone's elytra bounds, such as above the Nether roof with `elytraAllowAboveRoof` off.
- **take-off** (default on). On its own, Baritone only opens the elytra while falling fast, so on flat ground it waits forever. Comet jumps and opens the elytra near the top of the jump, sending the same packet as a double jump. Adjustable flight then supplies the movement; vanilla mode uses fireworks. It makes up to 3 attempts, 3 seconds apart, then walks. If Baritone finds no flight path within 10 seconds, it also walks, and the log records Baritone's status for diagnosis. Comet doesn't do this when Baritone's `elytraAutoJump` is on, because Baritone then walks to a drop by itself.
- **Landing.** Baritone looks for a safe spot near the goal. With **elytra-fly** on, Comet slows the approach to its landing waypoint, centers over the selected column and descends gently without rockets, keeping terrain checks and normal collision handling for touchdown. If you land within min-distance of the goal (at least 128 blocks), Baritone walks the rest. Landing further away can mean elytra durability ran low, or fireworks ran low in vanilla mode. Comet stops and says how far short you are; `#goto` again to carry on once supplies are restored.
- **equip-elytra** (default on) puts on your best elytra for the flight. **swap-back** (default on) puts the chestplate it replaced back on after landing.
- **restock-fireworks** (default on, shown only in vanilla mode) moves a stack of fireworks into the hotbar whenever the hotbar has none. Adjustable flight skips restocking entirely. Unless its `allowInventory` setting is on, vanilla Baritone fires fireworks only from the hotbar, yet counts the whole inventory when deciding whether to land. Without restocking it can run dry with fireworks left in the inventory. Comet uses an empty hotbar slot, otherwise **restock-slot** (default 9; the slot beside it while that one is selected); the item there goes to where the fireworks were.
- Meteor's **ElytraFly** is paused for the flight and restored afterwards so it does not replace Baritone's steering. The adjustable mode runs its own instance of Adaptive Elytra Fly's engine.
- `#goto` or `#goal` during a flight redirects it (Baritone does this), and Comet walks to the new goal after landing. `#stop` cancels a flight; in the air you keep gliding with nobody steering.
- Flights started some other way, with `#elytra` or by another addon, also get take-off help and adjustable flight without rockets when **elytra-fly** is on, or firework restocking in vanilla mode. Meteor ElytraFly is paused while adjustable flight is selected and restored afterwards. Comet doesn't walk after these flights, change your elytra or chestplate, or report how they ended; whatever started the flight handles those.
- Turning the module off stops a flight that hasn't taken off. One already in the air keeps going until it lands or you `#stop` it; ElytraFly, paused for that flight, stays off until you land so it cannot fight Baritone's steering, then turns back on.

The module list shows `Taking off`, the remaining distance in blocks during travel, then `Landing` during controlled descent.

Baritone's own elytra settings still apply; change them with `#set` or in Meteor's Baritone settings. Examples:
- `elytraFireworkSpeed` and `elytraConserveFireworks` control firework use in vanilla mode. Adjustable flight temporarily overrides `elytraFireworkSpeed` and `elytraMinFireworksBeforeLanding` while enabled.
- `elytraAllowAboveBuildLimit` (default on) flies Overworld and End trips longer than `elytraLongDistanceThreshold` (default 500 blocks) in a straight line above the build limit.
- `elytraAllowAboveRoof` (default off) keeps Nether flights below Y 128.
- `elytraPredictTerrain` (default off) only helps if you know the server's Nether seed.
