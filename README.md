# Comet by Boyonabike

[![Build](https://github.com/Boyonbike/Comet-Meteor-Addon/actions/workflows/build.yml/badge.svg?branch=1.21.11)](https://github.com/Boyonbike/Comet-Meteor-Addon/actions/workflows/build.yml)
[![Licence](https://img.shields.io/badge/licence-GPL--3.0--only-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-3c8527.svg)](https://www.minecraft.net/)
[![Meteor Client](https://img.shields.io/badge/Meteor%20Client-build%2082-8e44ad.svg)](https://meteorclient.com/)

Comet is a Meteor Client addon for Minecraft **1.21.11** covering movement, building,
storage, chat and server-aware tooling. It includes the DEVILS Auto Wasp port and
adaptive elytra flight, TNT surface coating, Scaffold and container improvements,
Chat Tools with homes, teleports and clan management, storage ESP, Speedmine with
optional bedrock mining, Xaero waypoints, Discord call-to-arms and stash reports,
and a set of HUD elements. Every module and its settings are documented below.

> **Unofficial addon.** Comet is not affiliated with or endorsed by Meteor
> Development, Mojang, or any server. Servers may treat some automated, movement or
> informational features as cheating; use them only where the server's rules allow it.

## Install

1. Install **[Fabric Loader](https://fabricmc.net/use/installer/) 0.19.3 or newer**
   for Minecraft 1.21.11.
2. Install **[Meteor Client](https://meteorclient.com/)** build 82 or newer. Both
   need **Java 21**.
3. Download the JAR for your Minecraft version from
   **[Releases](https://github.com/Boyonbike/Comet-Meteor-Addon/releases)**, or
   [build it from source](#build-and-tests).
4. Put the JAR in your `.minecraft/mods` folder and start the game.
5. Open Meteor's GUI and select the **Comet** category.

The Fabric mod ID stays `tnt-coating`. Remove any older Comet JAR before installing
a new one; two copies cannot load together. **xaero-waypoints** also needs
[Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) and **baritone-elytra**
needs Meteor's [Baritone](https://meteorclient.com/) build; every other module
works with Meteor alone.

## Version branches

| Branch | Minecraft | Java | Meteor Client | JAR |
| --- | --- | --- | --- | --- |
| [`26.1.2`](https://github.com/Boyonbike/Comet-Meteor-Addon/tree/26.1.2) (default) | 26.1.2 | 25 | build 42 | `comet-0.25.20-mc26.1.2.jar` |
| [`26.2`](https://github.com/Boyonbike/Comet-Meteor-Addon/tree/26.2) | 26.2 | 25 | build 35 | `comet-0.25.20-mc26.2.jar` |
| [`1.21.11`](https://github.com/Boyonbike/Comet-Meteor-Addon/tree/1.21.11) | 1.21.11 | 21 | build 82 (vendored) | `comet-0.25.20-mc1.21.11.jar` |

Each branch is a complete, independently buildable tree with its own README,
changelog and JAR. Features and the release number are kept in step.

## Modules

Open Meteor's **Comet** category. Modules appear in this order, grouped by purpose:

- **Flight and movement:** **adaptive-elytra-fly**, **auto-wasp-devils**, **baritone-elytra**, **elytra-log**, **angle-calculator**.
- **Rendering, storage, waypoints and chat:** **fast-storage-esp**, **dub-counter**, **shulker-icons**, **no-hurt-cam**, **anti-coords**, **xaero-waypoints**, **waypoint-delete**, **chat-tools**, **call-to-arms**, **stash-report**.
- **Building:** **scaffold**, **block-coat**, **smart-sponge**, **speedmine**, **safe-fast-use**, **anti-spawn-set**.
- **Combat and inventory:** **auto-tool**, **auto-weapon**, **inventory-tweaks**, **auto-replenish**.
- **Allays:** **allay-farm**, **allay-shulker-killer**. See [Allays](#allays).
- **HUD elements** (Meteor's HUD editor, **Comet** group): **adaptive-elytra-fly-engine**, **player-speed**, **speed-3d**, **tp-home-countdown** and **dub-counter**. See [HUD elements](#hud-elements).

**Scaffold**, **Auto Tool**, **Auto Weapon**, **Inventory Tweaks** and **Auto Replenish** are available in both Comet and their original Meteor categories. Both entries open and toggle the same module, sharing settings, keybinds and enabled state. Other Comet upgrades are also linked from a **Comet** dropdown inside the original module's settings:

| Original module | Comet upgrades in its dropdown |
| --- | --- |
| Elytra Fly | Adaptive Elytra Fly, Baritone Elytra, Elytra Log |
| Auto Wasp | Auto Wasp Devils |
| Storage ESP | Fast Storage ESP |
| Fast Use | Safe Fast Use |
| Waypoints | Xaero Waypoints, Waypoint Delete |

Expand **Comet**, then left-click an upgrade to toggle it or use its **Settings** button to open the full settings directly. Right-clicking the upgrade still works too. These links use the same module instances as the Comet category, so changes apply from either location.

Right-click a Comet module to open its settings, then click a section header or arrow to expand its dropdown. Setting sections start collapsed; saved section states are respected when loading a configuration. Adaptive Elytra Fly also has a **Flight Diagnostics** dropdown, and Fast Storage ESP puts **Clear Opened Containers** under **Actions**. The **Comet** section added to Meteor's Scaffold also starts collapsed.

**Newer Meteor builds.** Before applying an upgrade to a Meteor module, Comet checks that the installed Meteor still has every method, field and code location the upgrade attaches to. If a Meteor build has changed one, that upgrade alone is skipped: the stock module keeps working, it is not listed under Comet, and chat names the skipped upgrades when you join a world (details are in the log). Adaptive Elytra Fly refuses to start if its Elytra Fly hook was skipped. Builds older than 82 are rejected by Fabric with a clear message.

Version history is in [CHANGELOG.md](CHANGELOG.md). Phase, Elytra Replace and the
Trajectories upgrade were removed in 0.25.0; see the
[0.25.0 changelog](CHANGELOG.md#0250). Upgrading from 0.6.1? See
[Upgrading](#upgrading-from-061).

<details>
<summary><strong>Contents</strong></summary>

- [Install](#install)
- [Version branches](#version-branches)
- [Modules](#modules)
  - [DEVILS Auto Wasp](#devils-auto-wasp)
  - [Movement and player utilities](#movement-and-player-utilities)
  - [Advanced Auto Replenish](#advanced-auto-replenish)
  - [Anti Coords](#anti-coords)
  - [Shulker Icons](#shulker-icons)
  - [Scaffold optimization](#scaffold-optimization)
  - [Inventory Tweaks transfers](#inventory-tweaks-transfers)
  - [Auto Tool and Auto Weapon inventory use](#auto-tool-and-auto-weapon-inventory-use)
  - [Adaptive Elytra Fly](#adaptive-elytra-fly)
  - [Fast Storage ESP](#fast-storage-esp)
  - [Dub Counter](#dub-counter)
  - [Call to Arms and Stash Report](#call-to-arms-and-stash-report)
  - [Safe Fast Use](#safe-fast-use)
  - [Allays](#allays)
  - [Block Coat](#block-coat)
  - [Smart Sponge](#smart-sponge)
  - [Speedmine](#speedmine)
  - [Xaero Waypoints](#xaero-waypoints)
  - [Waypoint Delete](#waypoint-delete)
  - [Chat Tools](#chat-tools)
  - [Proxy tools](#proxy-tools)
  - [Baritone Elytra](#baritone-elytra)
  - [Elytra Log](#elytra-log)
  - [HUD elements](#hud-elements)
  - [Server pacing and packets](#server-pacing-and-packets)
- [Performance](#performance)
- [Upgrading from 0.6.1](#upgrading-from-061)
- [Build and tests](#build-and-tests)
- [Contributing](#contributing)
- [Licence and credits](#licence-and-credits)

</details>

## DEVILS Auto Wasp

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

## Movement and player utilities

These three modules are original Comet implementations; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party provenance.

- **no-hurt-cam** removes the camera tilt and shake when damaged while it is on.
- **angle-calculator** aims your view at the centre of the configured **to** block, using its X/Z coordinates. **level-pitch** defaults on and keeps pitch at zero. Riding also turns the vehicle. This controls your view; it does not move you automatically.
- **anti-spawn-set** cancels outgoing bed and respawn-anchor interactions before the server sees them. **block-beds**, **block-anchors** and **notify** default on. It blocks every interaction with those blocks, including sleeping, anchor charging and explosive use in other dimensions.

## Advanced Auto Replenish

Comet upgrades Meteor's existing **auto-replenish** in place. Its Player and Comet entries share one toggle, keybind and configuration. Existing **min-count**, **delay**, **offhand**, **unstackable**, **same-enchants**, **search-hotbar** and **excluded-items** settings remain in use, including Meteor's Auto Totem offhand lock.

- **stack-preference** chooses **SmallStacks** (default), **FullStacks** or **FirstMatch** across eligible inventory stacks. **smart-refill** waits until a remaining stack has one item; empty tracked slots can still be restored.
- **pause-on-use** defaults on. **max-refills-per-tick** defaults to 1 and allows up to 5 transfers per refill tick, subject to Meteor's existing delay.
- **refill-all-stackable** defaults on. Turn it off to select categories: blocks, food, tools, weapons, projectiles, ender pearls, potions, totems, golden apples and fireworks. Tools and weapons default off. Tools always keep their exact item/material; replacing broken or consumed unstackable items also requires Meteor's **unstackable** setting.
- **respect-custom-names** defaults on and requires the same custom name when replacing an emptied slot. Partly filled stacks always require matching item components, including names, enchantments and potion data, so incompatible stacks are never swapped over remaining items. **same-enchants** and **excluded-items** still apply.

Transfers use Meteor's targeted inventory clicks without opening the inventory. Comet replaces the original shift-click and delayed-click queue with direct transfers to the intended slot, so an earlier empty hotbar slot cannot redirect a refill. Transfers pause for open containers, carried cursor items and Comet's temporary tool/weapon loans. Manual screen changes reset tracked slots through Meteor's original lifecycle. These features have automated build and compatibility coverage; in-game movement, camera effects and server inventory acceptance still need verification.

## Anti Coords

Enable **Comet → anti-coords** to make your coordinates as hard as possible to work out from what you show.

**Textures**, adapted from [NoTextureRotations](https://github.com/rfresh2/NoTextureRotations) by rfresh2. Grass, stone, sand and many other blocks pick a rotated or alternative texture from their own coordinates, and flowers, grass, bamboo and dripstone pick a small offset the same way. No world seed is needed: a screenshot or video showing enough of these blocks can be checked against positions until one fits.

- **mode**: **Fixed** (default) gives every block the same rotation and offset, so they reveal nothing. **Keyed** keeps vanilla's variety: each block's place within a 256-block square is hashed with SipHash-2-4 under a secret key, with a separate key derived for each server and dimension. Without the key the textures reveal nothing about where you are; even with it, only your place within that square. The key is saved in `meteor-client/comet/anti-coords.key`, so textures stay the same between sessions. Delete it to get a new one.
- **hide-rotations** and **hide-offsets** both default on. Your own movement, and the integrated server in singleplayer, still collide with the real bamboo and dripstone offsets, so you stay in sync with the server. Your crosshair targets the offsets you see.
- Toggling the module or changing these settings rebuilds the loaded chunks.
- Meteor's **no-render → no-texture-rotations** is a simpler version that also moves your collisions; leave it off while using Anti Coords.

**Screenshots.** With **clean-screenshots** on (default), the screenshot key saves only the world. The capture is taken after the world is drawn and before anything 2D, so it leaves out vanilla's HUD, F3, chat, open menus and toasts, Meteor's HUD and its waypoint labels, nametags and logout spots, and any other mod's overlay. The hand, block outline and vanilla name tags are hidden as F1 hides them. F1 on its own still shows Meteor's waypoint labels, nametags and logout spots. The HUD disappears for the one frame being captured. Outside a world, screenshots work as usual.

Automated tests check the key hashing against Guava's SipHash-2-4 and every Anti Coords hook against Minecraft 1.21.11; in-game rendering still needs verification.

## Shulker Icons

Enable **Comet → shulker-icons** to add a small item icon over shulker boxes in inventory and container GUIs, including creative slots and the carried stack. By default it shows the item with the greatest total quantity inside the box, summed across slots. Ties use the first occupied slot. Empty boxes, or boxes whose contents the server does not send, show no icon. All shulker colours work.

The overlay defaults to half size in the top-right corner. Change **icon-scale** and **icon-corner** in General to adjust it.

Under **Combos**, click **Edit Combos → Add Combo**. Expand the combo, give it a name, select **required-items**, then click **Select Icon**. A match needs at least one of every selected item; extra contents are allowed. For example, require a netherite helmet, chestplate, leggings and boots, plus a totem, then select a diamond sword as the icon for that kit. Matching uses item types, ignoring damage, enchantments and custom names.

Each combo has a **Select Colour** button with a **Solid Colour** checkbox immediately beside it:

- **Unchecked:** use the original item design. **Select Icon** offers every normal item icon, including every block variant.
- **Checked:** use a flat silhouette in the selected colour, with a live preview. Blocks with matching loaded model geometry share one picker entry; block texture and colour variants do not repeat the same shape. Searching a grouped block's name still finds that shared entry. Distinct model shapes remain separate. The list is rebuilt when the picker opens to respect resource-pack changes.

Changing the colour stores it; the checkbox controls whether to use it. Switching the checkbox off restores the original item texture and keeps the chosen colour for next time. Changes refresh the preview and cached overlays immediately. The selected colour belongs to the combo, so two kits can use the same shape in different colours. Existing saved styles migrate to the corresponding checkbox state, and the automatic most-common-item fallback keeps its normal item icon.

Combos override the most-common item. The first enabled matching combo wins; **Move Up** and **Move Down** change priority. Disable or delete individual combos as needed. An empty required-items list never matches. With no matching combo, the normal most-common icon appears. Rules save with Meteor configurations and participate in module copy/paste and reset; the **Reset** button beside **Edit Combos** asks before deleting every combo.

If the solid-colour shader cannot be compiled (for example under an incompatible resource pack), coloured combos show their normal item icon and the log says why; the shader is retried after the next resource reload.

## Scaffold optimization

Use Meteor's existing **Movement → Scaffold**. Meteor's own Scaffold tick runs unchanged, so its fixes keep applying; Comet only replaces the sort of fallback supports with a one-pass nearest selection (preserving Meteor's choice when supports are equally close) and stops the tower ceiling check at the first collision. Radius/air-place ordering and the original placement, rotation and tower settings remain available.

The module gains a **Comet** setting group:

- **sticky-switch** defaults on (with **auto-switch**). It holds the block slot while Scaffold places and switches back after a second without placing, or when Scaffold turns off. Without it, Meteor switches to the block and back for every placement, which is two slot packets each. If you pick another slot while the block is held, Scaffold returns to that one instead.
- **extra-rotation-packets** defaults off. Meteor rides the first rotated placement of a tick on your movement packet and sends every other one as its own rotation packet. With **air-place** and a high **blocks-per-tick**, that tripled the movement packets the server saw and caused long rollbacks on 6b6t. Off, Scaffold places at most one rotated block per tick, nearest first (up to 20 blocks a second). **blocks-per-tick** still applies with **rotate** off.
- **setback-pause** defaults to 10 ticks. After a server setback Scaffold places only the block under you for this long. Servers often set you back again within a few ticks, and other placements in that window only add packets. Zero disables it.
- **confirmation-retries** defaults on. Repeated attempts at an unconfirmed/rejected position wait 2, 4, then at most 8 ticks. Positions near the predicted player feet retry after 1, then at most 2 ticks. New bridge cells never wait for another cell. Applied server block updates release successful attempts; client prediction alone does not count as confirmation. Disable this setting to retain Meteor's attempt timing.
- **log-setbacks** defaults on. Every server setback (rubber-band) while Scaffold is on adds one JSON line to `meteor-client/comet/scaffold-setback-logs`. Each Scaffold session gets its own file, opened at the first setback and capped at 4 MB; chat shows the path when it opens. A line holds where the server put you and how far (`correction*`), ping, sneak/sprint/jump input, tower state, Scaffold's settings, `extraRotationPacketsLastSecond`, and `pauseTicksLeft` (above zero when the previous setback's pause was still running). It also has the move the server rejected (`rejected*`: speed, vertical speed, on-ground flag, yaw/pitch snap of the sent rotation, blocks placed that tick) and how many ticks back the target position was (`targetTicksAgo`). `supportPlacedTicksAgo` shows whether you were standing on a block Scaffold had just placed. A 20-tick `timeline` lists each reported position relative to the target, with its ground flag, sent rotation and placements.

Retry history is bounded and clears on module/world changes. Live server testing is still needed for placement acceptance, rejected-block recovery, tower behaviour and actual frame/packet improvements.

## Inventory Tweaks transfers

Use Meteor's existing **Misc → Inventory Tweaks** and its Steal/Dump controls. Comet schedules transfer clicks on the client thread instead of sleeping on a background worker. Initial, normal and random delays retain their millisecond settings, with actions occurring on the next eligible frame or client tick. A stalled frame does not cause overdue clicks to burst all at once.

Each transfer belongs to one exact screen, menu, player and world. Closing/switching the container, leaving the world, disabling the module, starting a manual sort or picking up an item on the cursor cancels pending work. The source stack, filter and slot pickup permission are checked again immediately before a click, including delayed rotation callbacks. Repeated inventory-content packets coalesce into the current job. Zero-delay transfers share a maximum of eight actions per client tick across frames and requests.

Steal-drop works with or without **drop-backwards**; the original non-backwards path did not issue the drop. A transfer waiting on a drop-backwards rotation is abandoned after one second if the rotation never happens, so the job cannot stall. While riding (when Minecraft sends no rotation packets) drop-backwards drops without turning around. Live Minecraft testing remains needed for your server's click acceptance, repeated container changes and sort/transfer interactions.

## Auto Tool and Auto Weapon inventory use

Meteor's existing **Auto Tool** and **Auto Weapon** modules gain **use-inventory**, enabled by default. Their normal scoring now includes the 27 main-inventory slots as well as the hotbar. Auto Tool retains its enchantment preference, lists, anti-break, delay and switch-back behavior; Auto Weapon retains its sword/axe preference, damage threshold and anti-break behavior.

When the winning item is outside the hotbar, Comet swaps it with the currently selected hotbar stack. The displaced stack stays in that exact inventory slot until the action is done. Auto Tool restores the pair half a second after mining stops: neither Attack held nor a block being broken. This also keeps the tool in hand between consecutive blocks when Baritone or another module mines without the Attack key. Auto Weapon's **inventory-min-gain** (default **1** damage) only pulls a main-inventory weapon that beats your best hotbar weapon, as Meteor scores it, by at least that much; smaller upgrades are not worth delaying the hit. Auto Weapon waits for the server to apply the weapon's equipment attributes and for its attack cooldown before sending the hit, then restores the pair after a short display delay. Extra clicks during that wait do not queue extra hits. Changing the selected slot, losing the target or disabling Auto Weapon cancels the pending hit and restores the loan; an unconfirmed swap times out instead of attacking with stale damage. Disabling either module also restores its active loan. Container SWAP clicks leave the cursor untouched, and open chest or shulker layouts are resolved through their actual player-inventory slots.

Only one temporary loan can exist at a time. A new loan first restores the old one. If the player or another module manually changes either endpoint, Comet abandons its remembered exchange instead of moving unrelated items. Live server testing is still needed for click/attack acceptance and very high latency.

## Adaptive Elytra Fly

Enable **adaptive-elytra-fly** in **Comet**, with Meteor's **Elytra Fly** set to **Vanilla**. If Vanilla is selected, enabling Comet's module in a world also enables Elytra Fly. Set horizontal and vertical cruise/Sprint speed caps, in blocks per second, in Comet's **General** dropdown. Meteor's **horizontal-speed** and **vertical-speed** act as zero/nonzero switches for their respective axes; their numeric values no longer set adaptive movement speed. Meteor's **fall-multiplier**, takeoff, autopilot, firework and inventory settings remain available. Comet supplies its own acceleration and braking instead of Meteor's scalar acceleration settings.

**smart-elytra-swap** (General, on by default): wear a chestplate while grounded, including immediately after landing. Jump normally, then release and press **Jump** again in the air: Comet equips a usable elytra before Minecraft opens the wings in that same attempt. Searches the hotbar, main inventory and offhand, choosing the most durable usable elytra or the chestplate with the most armor, then toughness and durability. The displaced item returns to the source slot, so a full inventory works. Skips broken wings and Curse of Binding candidates. Swaps pause while another container is open or an item is on the cursor. Requires Meteor Elytra Fly to be active in Vanilla mode. Held or toggled Sprint waits for **5 gliding ticks** (a quarter of a second at 20 TPS) after a chestplate-to-elytra swap before either adaptive Sprint or unlimited boost can engage; waiting on the ground does not spend this delay. Manual swaps and swaps by other modules use the same delay.

Add the **adaptive-elytra-fly-engine** HUD element for a live overview of flight state, measured speed, 3D/horizontal/vertical output, attainable maximum, unlimited boost ramps, setbacks and the simulated server glide. Place it anywhere in Meteor's HUD editor.

**Deterministic flight.** The same keys and terrain always fly the same way. A server position correction (setback) keeps your momentum and only rewinds the boost ramps by **boost-rewind-ticks** (**15**) ticks of acceleration, as VolytraFly does, losing under 1% near top speed. Nothing is learned from setbacks and nothing slows cruise afterwards. A correction more than 64 blocks away (a command or portal) stops controlled movement instead. After the rewind, both ramps hold that speed for **boost-setback-hold** (**1**) tick before accelerating again, as VolytraFly's acceleration delay does: on 6b6t, accelerating on the very first move after a setback gets that move rejected too, pinning you to one spot with a setback every round trip. If two setbacks in a row still put you back on exactly the same spot, you are pinned: for **pinned-glide-ticks** (**10**, half a second; 0 disables) Comet leaves movement to Minecraft's own elytra glide, which the server accepts, then boosts again from the start speed. Setbacks that keep coming every few ticks still add up: each costs a rewind, so boost settles just under a speed the server really refuses and climbs straight back once they stop. 0.24.0 removed the learned server limits, correction backoff, pause and recovery, server memory and the boost auto-cap: on 6b6t most setbacks come from lag and ping jitter rather than speed, so learning from them made flight unpredictable.

**Unlimited boost** (on by default, **Unlimited Boost** dropdown): holding Sprint flies VolytraFly's speed system instead of the adaptive engine maximum. Horizontal speed jumps to 60 b/s (2.999 blocks/tick) or your current speed if faster, then gains `boost-ramp-step × (300 b/s − speed) / 300 b/s` per tick, tapering towards 300 b/s (14.999 blocks/tick; reaching 285 b/s takes about 7 seconds). Climbing or descending with Sprint does the same from **boost-vertical-start-speed** (**1.5** blocks/tick) towards 60 b/s. These curves are fixed. **boost-max-bps** (**300**) and **vertical-boost-max-bps** (**60**) are speed caps: boost accelerates along the unchanged curve and stops at the cap, instead of tapering towards it, and a setback rewinds from the cap along the same curve. The terrain, chunk and server-glide guards still apply, using unified braking (below). Only a real collision costs ramp speed: guard clipping at walls and chunk edges resumes full ramp speed once clear. Blocked close to an object, boost falls back to the close-object crawl and resumes as soon as the way is clear, without releasing Sprint; Sprint's close-object override applies only with **unlimited-boost** off. Releasing Sprint brakes down to cruise instead of snapping. Turn **unlimited-boost** off to restore adaptive Sprint.

**How fast a server allows.** A vanilla server measures each move packet from where you were at the start of its tick and sets you back when `distance² − serverVelocity² > 300 × packets that tick`. One packet a tick allows about **17.3 blocks/tick** (346 b/s). When two of your packets reach the server in the same tick, through lag or ping jitter, the second fails above **12.25 blocks/tick** (245 b/s). That bunching, not speed itself, is the rubber-banding at 300 b/s, which is why boosting through it works. Boost's total 3D speed is always held to **17 blocks/tick**, so a climb or dive at top speed shares the limit instead of crossing it. 6b6t's own anticheat is unknown, so compare setback logs (below) when trying these:

- **overdrive** (off by default): raise the boost curve's top to **340 b/s** (17 blocks/tick) instead of 300 b/s; **boost-max-bps** still caps it, so set it to 340 too. Under the vanilla rule it rubber-bands no more often than 300 b/s.
- **split-moves** (off by default, experimental): while boosting above 200 b/s, also report a half-step position each tick, just before vanilla's own. The server then allows twice the distance per tick (**480 b/s** with overdrive) and 300–340 b/s survives two ticks of packets arriving together. An anticheat that counts movement packets may set you back or kick you instead.

Comet limits changes to the whole movement vector and checks the actual blended trajectory before moving. Above harmless speeds it also turns existing momentum toward your heading, so fast corners stop sliding sideways (**adaptive-turn-rate**). It uses swept player boxes rather than a centre ray, floors negative chunk coordinates correctly, and checks intermediate chunks and the player's width. During adaptive control, missing chunks are guarded even if Meteor's **no-unloaded-chunks** is off, unless **fly-into-unloaded-chunks** is on. That Terrain setting (default off) treats chunks your client has not loaded yet as open air, so boost is not held back by how fast the server sends chunks; blocks there are invisible to every guard, including the server-glide guard, so terrain only the server sees can set you back or deal wall damage. Descending contact with floor shapes below the feet is resolved by Minecraft so it can still land. At higher speeds, walls and ceilings stay guarded; **safe-maneuvering** lets you move against them slowly, including from harmless block contact. If a correction or unload leaves an unsafe starting overlap or missing terrain, **Terrain overlap** yields to Meteor until it is clear, allowing retreat instead of trapping every direction.

- **acceleration** (default **0.15**) and **braking** (default **0.3**) are changes in blocks/tick per tick. More braking permits a shorter stopping distance; higher acceleration makes low-speed steering sharper. Vertical changes also obey **vertical-acceleration**.
- **Unified braking.** One policy brakes for released keys, walls, chunk edges and the ground, as hard as needed and no harder. Around cruise speed it is **braking** (0.3); with unlimited boost on, planned braking rises with the square of speed just enough that a stop from the boost curve's top (300 b/s, whatever **boost-max-bps** is) fits the lookahead (about 0.34 at 41 b/s, 0.61 at 118 b/s, 2.3 at 300 b/s); with it off, braking stays constant, exactly as before. When something is closer than planned, the guard commits to the gentlest constant braking that still stops before it, up to an instant stop. With defaults: from 40 b/s braking peaks at about 0.3 blocks/tick per tick; a wall first seen 64 blocks ahead at 300 b/s takes about 1.7 (35 b/s per tick, over 17 ticks); one seen 30 blocks ahead about 3.5; one appearing 10 blocks ahead about 8. Every stop ends at the terrain margin, never past it. Adaptive cruise and Sprint caps are still limited by the constant-braking maximum below.
- **Soft landing.** With the block guard on, a deliberate descent (faster than 0.05 blocks/tick) scans up to 32 blocks straight down and brakes the same way as the floor approaches, touching down at **0.2 blocks/tick** (VolytraFly's anti-slam speed), including from a 60 b/s boosted dive.
- **adaptive-turn-rate** (default **15**, range **0–45** degrees per tick) is heading assistance that adapts to speed. Up to twice **acceleration** (0.3 blocks/tick, about 6 blocks/second, by default) steering stays pure momentum for precise tight-space control. Assistance blends in by six times **acceleration** (about 18 blocks/second), then rises with actual speed to double the setting at the adaptive engine's current maximum, so faster flight turns harder instead of drifting further. Corners keep their speed, speed changes still use **acceleration**, and reversals of more than 135° still brake first. With defaults, a 90° turn takes 5 ticks with about 5 blocks of sideways drift at 35% cruise, and 3 ticks with about 8 blocks at the 118 blocks/second maximum; plain momentum needed about 1 second and 19 blocks, and 2.8 seconds and 160 blocks. While unlimited boost drives, the rate keeps rising in proportion to speed beyond that maximum (up to 90° per tick), so a 90° turn at 300 b/s takes 2 ticks with about 4 blocks of drift. Zero restores momentum steering.
- **cruise-max-bps** (default **41.2**) caps horizontal cruise and **boost-max-bps** (default **300**) caps Sprint/boost, in blocks per second. Default settings and clear level terrain let the adaptive engine reach about **118 blocks/second**; cruise, and Sprint with **unlimited-boost** off, never pass that however high the cap is set.
- **vertical-cruise-max-bps** (default **21**) and **vertical-boost-max-bps** (default **60**) cap deliberate Jump/Sneak and Sprint + Jump/Sneak speed in blocks per second, up to 60. All four caps accelerate and brake smoothly and retain terrain limits; momentum above a cap, such as releasing boost, brakes down to it rather than snapping.
- **maneuver-mode** (default on) and **maneuver-max-bps** (default **200**): while you move horizontally, climb or descend **and** turn at the same time (a heading change of more than 2° per tick, from the mouse or a strafe key), total 3D speed is held to **maneuver-max-bps**. That combination rubber-bands far more than straight flight at 300 b/s. Speed drops to the cap at once, the mode lasts half a second after you stop turning, and boost then speeds up again from the cap along its normal curve instead of snapping back. The state shows **Maneuver capped**, and setback logs record `maneuverMode`. Climbing or turning on its own is never capped.
- **sprint-boost** defaults on. Hold your bound **Sprint** key while gliding to use both Sprint caps, including during purely vertical movement; with **unlimited-boost** on (the default), Sprint drives the boost ramps described above. **boost-key-mode** (default **Hold**) can be set to **Toggle**, where each Sprint press turns boost on or off (shown on the HUD boost line) until you stop gliding. With **toggle-off-when-idle** (default on), toggled boost also turns off a quarter of a second after you release every movement key (WASD, Jump, Sneak), unless Meteor's Auto Walk, Elytra Fly autopilot, Baritone or Baritone Elytra is moving you. Opening a screen releases the keys too. Releasing movement brakes to a stop; releasing Sprint returns each axis to its own cruise cap. Zero Meteor horizontal or vertical speed disables adaptive input on that axis. This uses the physical Sprint key, including a rebound mouse button, and only works while Minecraft is focused with no screen open. Automatic Sprint and toggle-sprint state do not activate it.
- **vertical-acceleration** (default **0.05 blocks/tick per tick**) smooths climbing, descending and releasing vertical input. Switching straight from Jump to Sneak (or back) slows the old direction evenly over **vertical-reversal-ticks** (default **2**, range **0–20**), then the new direction speeds up from a standstill, at the boost start speed with Sprint, which waits there until the old direction is gone. With the default a 21 b/s climb goes 14, 7, then a 10 b/s descent, and a 60 b/s Sprint climb goes 40, 20, then a 30 b/s descent. **0** turns around on the very next tick; higher is smoother, but never slower than releasing the key. **vertical-ramp-step** (default **0.5**) speeds climbing and descending up along the boost curve, tapering to zero at 60 b/s, so cruise reaches its target in 3 ticks instead of about a second. Climbing and descending no longer share horizontal acceleration. Vertical braking uses up to twice **vertical-acceleration**, bounded by **braking**, or the mirror of the vertical ramp if more (**vertical-ramp-step** × speed / 60 b/s: 0.5 per tick at 60 b/s, gentle near a stop). Terrain stops still take effect immediately.
- **lookahead** (default **64**, range **8–64** blocks) and **terrain-margin** (default **0.35**) set the known-clear stopping corridor. The speed ceiling reserves the next movement plus stopping distance: `v + v² / (2 × capacity(v)) <= clear distance - margin`, where `capacity(v) = braking + g × v²` and `g` makes a stop from the boost top speed fit the lookahead exactly (`g = 0` without unlimited boost). Above that envelope, the next speed is `v + d − √(v² + d²)`, the gentlest constant braking that stops within clear distance `d`. Even in open terrain, the finite horizon imposes the adaptive engine's ceiling: about 118 blocks/second with the defaults. Increase braking only after checking server acceptance and steering.
- **avoid-blocks** defaults on. Turning it off skips block collision reads unless Meteor's **no-crash** is on; chunk checks still run. Comet replaces Meteor's fixed-distance No Crash ray while it controls flight.
- **safe-maneuvering** defaults on. Near walls and other objects, movement up to **close-object-speed** (default **0.6 blocks/tick**, about **12 blocks/second**, range 0.1–4) bypasses the block guard and Meteor's No Crash, so Minecraft resolves the contact and you can turn or slide through tight spaces. Close-object control means touching or blocked by something; slow flight in open air no longer counts. With **unlimited-boost** off, pressing Sprint during close-object control lifts that limit until the contact clears. This also applies after the guard has slowed you down: if the next acceleration step would be blocked, a crawl-speed fallback permits movement along or away from the wall. Extra movements in the same tick use the same bypass. Unloaded-chunk protection stays active. Turn this off to keep block guarding at low speed. Your own speed into a wall is not what hurts: the server's hidden glide is, and **server-glide-guard** keeps walls out of its reach; see below.
- **server-glide-guard** defaults on and works with block avoidance (**avoid-blocks** or Meteor's **no-crash**). A vanilla server keeps simulating an ordinary elytra glide for you: every tick it moves that glide from your reported position, then puts you back. If that hidden move hits a wall faster than 0.3 blocks/tick, the server deals fly-into-wall damage however slowly you were really moving. 0.23.6 stopped 0.35 blocks from walls, inside that reach, which is the likely cause of the small hit when stopping at a wall. Comet now follows the server's glide (from takeoff, your sent rotation, attached rockets, corrections, motion and explosion packets) and keeps walls out of its next move: braking ends far enough away, a low-speed crawl stops at its reach, and if the hidden glide grows into a wall while you hover, you step back. Unloaded chunks in its path count as walls. A steady level glide reaches about 1.5 blocks/tick, so after a few seconds of flight a wall you face is held about 1.6 blocks away; glancing and side contact are unaffected. Full contact remains available while the hidden glide is harmless, such as shortly after takeoff, after a correction or after a harmless contact. If no reachable position is safe, the hit is counted instead of freezing you. Turn this off if a server does not simulate player glides.
- **steer-server-glide** defaults on. Near a wall you face, Comet reports a level heading away from it, so the server's own glide turns away from the wall within about a third of a second. You can then fly right up to the wall without wall damage, instead of being held about 1.6 blocks back by **server-glide-guard**. Your camera does not move; other players see your head turned away while it steers. The HUD's server glide shows **(steered away)** meanwhile, and Flight Diagnostics count steered ticks.
- **log-setbacks** (Diagnostics, default on) writes one JSON line per setback while flying to `meteor-client/comet/setback-logs` (a new file per session, at most 4 MB): time, position, correction distance, total/horizontal/vertical speed and both boost ramps in blocks/second, state, ping, whether the chunk two seconds ahead was loaded, whether you were pinned, and the **fly-into-unloaded-chunks**, **overdrive** and **split-moves** settings. When it can match the setback to a move you sent, it also records that rejected move: its speed, horizontal and vertical speed, how much your velocity changed from the move before (blocks/second in one tick), how sharply it turned, and whether it was the first move after an earlier setback. Compare flights by setbacks per minute instead of by feel.

The module list shows current activity (including **Unlimited boost**) and observed 3D progress over about one second. The settings panel shows the adaptive maximum, selected cruise/sprint or boost speeds, each boost ramp's speed and maximum, setbacks and the setback log file, ticks actually limited by chunks or blocks, the estimated server glide speed with its limited ticks and predicted server wall hits, query counts and frame times. Frame average is smoothed; peak and frames over 50 ms are counted since activation/world change or **Reset Flight Diagnostics**. These readings help distinguish server corrections, terrain pacing and rendering stalls; the controller does not change graphics settings or Minecraft's chunk renderer.

Terrain work is bounded: a current-body overlap check and one lookahead scan per movement tick, at most 40 swept segments including refinement, with non-loading chunk checks before block reads. A blocked move can add a short chunk-only check for the crawl fallback. Following the server's glide costs one short collision check per gliding tick. The guard adds two per moving tick while that glide could hurt, and at most 26 in total when it has to shorten a move or step back near a wall. Extra SELF movements in the same tick use a short guard without advancing acceleration again. No whole-world chunk scans or background chunk generation are performed.

Comet's scripted landings and Meteor's Shift auto-hover yield to Meteor's original handling; Shift auto-hover can take priority over boosted descent. Packet, Pitch40, Bounce, Freecam, non-gliding movement and stopped fluid flight keep their original handling. Disabling adaptive control leaves Meteor's Elytra Fly enabled and immediately returns its movement to Meteor. Baritone's normal firework flight is unaffected.

Start with Timer at **1×** and low horizontal and vertical cruise caps, then compare retained progress and the diagnostics as you increase speed. Keep Meteor's horizontal and vertical speeds above zero for the axes you want to control. These are starting settings, not a verified 6b6t speed cap. Live Minecraft/6b6t validation is still required for server acceptance, actual frame performance and interaction with other movement addons.

## Fast Storage ESP

Enable **fast-storage-esp** in Meteor's **Comet** category for shader outlines and glow around storage. This uses the actual container models, including double chests, with through-wall rendering. Enabling it switches off Meteor's original Storage ESP to avoid doing the same rendering twice. Its settings are independent; configure the colours and storage selection in this module.

Unchanged models stay in GPU buffers, grouped into 8-block cells. Offscreen groups are skipped; a separate storage depth buffer rejects hidden storage surfaces while keeping terrain transparent to ESP. Nearby fading happens in the shader, so moving the camera or player does not rebuild the stash. Storage changes and lid/shulker animation poses are checked five times a second, and changed geometry is prepared over several frames. New stashes appear progressively while their cache fills.

The usual **storage-blocks**, **shape-mode**, **fill-opacity**, **width**, **glow-multiplier**, colours, **fade-distance**, **tracers**, **hide-opened** and **opened-color** controls are available. Interaction history lasts for the current world and dimension, including after you fly away and its chunks unload, up to 65,536 containers (the least recently opened are forgotten first). Changing dimension or server, disabling the module or **Clear Opened Containers** resets it. Unloading chunks, leaving a world, disabling the module and reloading resources release the render caches; a resource reload keeps the interaction history. If the storage shader cannot be created, the module turns itself off with a chat error instead of failing every frame.

- **max-distance** defaults to **0**, which includes all loaded storage. Set a range such as **128** or **256** blocks to reduce the work further.
- **rebuild-budget-ms** defaults to **2** milliseconds per frame, with a maximum of 128 models prepared per frame. Lowering it spreads loading over more frames; a single model or GPU upload can exceed the time target.
- Tracers default off. Wider outlines and higher resolutions still increase shader cost.

Automated checks cover cache invalidation, camera transforms and retained GPU buffer ownership, including the shared-buffer crash regression. Live Minecraft testing is still needed to measure FPS on a large stash and check the shader appearance with your graphics setup.

## Dub Counter

Enable **dub-counter** to count the ordinary chest blocks in every chunk your client has loaded, shown as dubs (double chests): two chest blocks make one dub, so 25 chests read **12.5**. Trapped chests, copper chests, barrels and ender chests do not count. The count shows beside the module name in Meteor's active-modules list, and the **dub-counter** HUD element adds the chest and loaded-chunk totals.

The count refreshes five times a second from the chunks' block-entity lists, without reading blocks or allocating anything per chest. It resets when you leave a world or disable the module. Adapted from Majkey's DubCounter 1.0.0 addon with the author's permission; remove `DubCounter-1.0.0-mc1.21.11.jar` when you install Comet, because both register **dub-counter** and Meteor keeps whichever loads last.

## Call to Arms and Stash Report

Two one-press modules post to Discord through channel webhooks. Bind each to a key: a press posts once, then the module switches itself off again.

- **call-to-arms** pings the clan (@everyone by default) to join a live grief. It shows your name and face, coordinates, dimension, the dubs in your loaded chunks, a `/tpa <your name>` code block to copy into chat, the **call-to-action** text and a screenshot.
- **stash-report** logs the stash around you with the clan's stash bot and pings nobody. It shows your Discord account and the dubs, always, plus your name and face, dimension and a screenshot. Like `/submit-stash`, it never includes coordinates. The bot credits the stash to your Discord account, counts it in `/stats`, `/mystats` and `/leaderboard`, and reacts to the report once it is logged. A call to arms is never logged as a stash, even when both use the same webhook.

Dubs are counted the same way as **dub-counter**, at the moment you press, whether or not Dub Counter is on.

Settings both share:

- **webhook-url**: in Discord, open the channel's settings → **Integrations** → **Webhooks**, create one and use **Copy Webhook URL**. Each module has its own, so the call to arms can go to a ping channel and the stash report to the channel the stash bot reads. Only Discord webhook URLs are accepted, so a typo can't send your coordinates anywhere else. Anyone with the URL can post in that channel, and Meteor saves it in its config folder, so don't share your config.
- **Sending** group: **cooldown-seconds** (default 30) ignores presses for that long after one is sent, so a double press doesn't post twice; a failed post clears it. **confirm** (default on) asks first: **Send call to arms?** says who will be pinged, and **Report stash?** says how many dubs will be reported. While the call to arms' **show-coordinates** is on, its prompt always appears, even with **confirm** off, and asks "Are you sure?" with the exact coordinates about to be posted. Missing settings, the cooldown and a post still sending are reported before the prompt opens.
- **Contents** group: a **show-** switch for each optional part, all on by default except the call to arms' **show-coordinates**, which starts off. With **show-name** off, the call to arms' ping line reads "join now!" without your name; its `/tpa` still contains it unless **show-tpa** is off too.

Call to Arms only:

- **ping** (default `everyone`): `everyone` pings every member who can see the channel, `here` only those online right now, and a role's ID pings just that role: turn on Developer Mode (Discord **Settings** → **Advanced**), right-click the role and choose **Copy Role ID**, or paste the `<@&id>` mention. Leave it blank to post without a ping. If a role shows but nobody gets notified, turn on **Allow anyone to @mention this role** for it (**Server Settings** → **Roles**). With a role or no ping, an `@everyone` typed into the call to action is shown but does not ping.
- **call-to-action**: the line under the title.

Stash Report only:

- **discord-user-id** (required): your Discord user ID, shown as your clickable name without pinging you. Turn on Developer Mode, then click your name and choose **Copy User ID**; pasting your `<@id>` mention also works. Nothing is sent until it is set, or while no chests are loaded around you.
- Keep **show-screenshot** on: the stash bot only logs reports with a screenshot, as `/submit-stash` needs an image. Two reports of one stash are logged as two entries, as two `/submit-stash` runs would be; an admin removes the extra with `/delete-stash`.

**Screenshot:** with **show-screenshot** on, the module waits for you to close any open menu (the confirmation prompt closes by itself), hides the GUI for one frame exactly as F1 does, photographs that frame and puts the GUI back. That hides the hotbar, chat, hand, crosshair, Meteor's HUD and tracers. Outlines drawn into the world, such as ESP boxes, still show. Screens wider than 2560 pixels are scaled down to stay inside Discord's upload limit. If a menu stays open for 10 seconds, or the picture can't be taken, the message goes without it and chat says so.

Chat says whether each post went out, or why Discord refused it (a deleted webhook, rate limiting, no connection). Sending happens in the background, so the game doesn't pause while it waits. Meteor's toggled on/off messages are off for both modules by default.

## Safe Fast Use

Enable **Safe Fast Use** (**safe-fast-use**) in Meteor's **Comet** category to use it in place of Meteor's Fast Use. Hold Use to place blocks quickly. With **prevent-shulker-open** on (default), a shulker placed during that hold will not open from repeated use: release Use and click again to open it normally. This covers all shulker colours and either hand. Sneaking still lets you place against a shulker.

- **mode**: **All** (default) speeds up every item; **Some** speeds up selected **items**, plus all block items when **blocks** is on (default off).
- **cooldown**: delay between uses in ticks, default **0**. Items outside the Some filter keep the normal four-tick delay.
- **prevent-shulker-open**: turn off to allow the normal repeated interaction with newly placed shulkers.

Safe Fast Use's cooldown takes precedence while it is enabled, including when Meteor's original Fast Use is also on. In **Some** mode with no items selected and **blocks** off nothing is sped up; the module says so when enabled and shows `Nothing selected`. Live Minecraft/server testing is still required, particularly placement from each hand, release and reclick, and sneaking against a box.

## Allays

Two modules work on the nearest allay within your normal reach, one allay at a time. Each faces the allay first (**rotate**, on by default) and shows what it is doing next to its name in the module list. Neither works through walls. An allay is only bred, given a shulker or hit when part of it can be seen past the blocks in between, and the click aims at that part. Plants and other blocks you can walk through don't block the view; glass and other solid blocks do.

**allay-farm** breeds allays and gives them shulker boxes, using items from anywhere in your inventory. It takes from your offhand or hotbar first. An item in the main inventory is swapped into your held slot for the click, then swapped back. Each job can be turned off on its own.

- **breed** (on by default) duplicates allays dancing to a jukebox with amethyst shards. It only uses a shard on an allay that is dancing and off its 5-minute duplication cooldown. Any other allay with an empty hand would just take the shard as its held item. Allays holding a shulker are bred too, since holding an item stops neither dancing nor duplication.
- **give-shulkers** (on by default) gives a shulker box to each allay with an empty hand. Any shulker you carry can be given away, whatever its colour or contents, so keep the ones you want out of your inventory. While breeding, allays ready to duplicate are bred first and only given a shulker afterwards.
- **keep-population** (on by default) never gives out more shulkers than allays bred. The farm counts every allay duplication it sees while it is on, by you or anyone else nearby. It gives a shulker only while the allays it has given one to are fewer than that count. An allay holding a shulker is not assumed dead. It uses up one breed while it is alive, and that breed stays used if it dies. The breed is freed again once the allay is seen alive without its shulker. Even if every allay holding a shulker is killed, you never lose more allays than you bred. Until the first duplication it shows `Waiting for a breed`. Turning the farm off, or changing world or dimension, starts the count again from zero.

**allay-shulker-killer** attacks allays holding a shulker box so they drop it, along with anything they have collected. Meteor's **auto-weapon** picks the weapon if it is on.

- **no-sweep** (on by default) keeps the allays around the target unhurt. A fully charged sword hit on the ground sweeps every living thing within about a block of the target, with or without Sweeping Edge, which only makes the sweep stronger. So the killer jumps and hits while falling instead. That is a critical hit, which never sweeps and does half as much damage again. The jump is timed so you start falling just as the hit is fully charged. It needs room to jump: with a block above your head it doesn't attack and shows `No room to jump`. While it is on, Auto Weapon takes its best hotbar weapon, because a weapon pulled from your inventory hits later, possibly after you land. With **no-sweep** off, the killer hits at full charge wherever you stand.

The farm shares vanilla's right-click delay. Together with your own right-clicks, it makes no more than one right-click every four ticks. The farm leaves an allay alone for two seconds after using an item on it, so the server's reply arrives before it is tried again.

An allay can't be hurt by the player it likes, and it likes whoever gave it its held item. So allays that **allay-farm** handed shulkers to can't be killed from the same account. Kill them from another account, or with damage that no player causes. When three hits in a row cause no damage, **allay-shulker-killer** skips that allay and says so in chat, so it doesn't keep swinging at it.

## Block Coat

**Coating block** selects the block used by coating, scaffold, jump placement and tunnel backfill (default **TNT**). Keep it in your hotbar or offhand; Meteor's Auto Replenish can refill the hotbar. Changing it cancels deferred placements and rebuilds the search. Existing blocks of the selected type count as already coated. Blocks with special placement or gravity behaviour follow Minecraft's normal rules.

- **Normal coating:** one layer on eligible floor, wall and ceiling surfaces within the cube (radius 5 = 11×11×11). Feet/head-level targets must touch an eligible wall. Excludes blast-resistant/unbreakable terrain, waterlogged blocks, fluids and non-collidable vegetation; the optional Liquid Filler handles isolated source water separately.
- **Conservative TNT (default off):** open Block Coat's **TNT Saving** section, at the top of its settings, and enable **conservative-tnt** to save TNT during surface coating, including tunnel backfill. It estimates blast coverage using vanilla TNT rays at their weakest strength, accounting for block resistance, fluids and shielding. A placement is skipped only when every eligible terrain block beside it is covered by existing or queued TNT. Failed, cancelled or removed placements release their coverage. Smart Jump and scaffold still place their required blocks; other coating materials keep their normal behavior. This is a local coverage estimate at the placed positions, not a guaranteed minimum-TNT solution: primed TNT movement, explosion order, server changes and custom explosion rules can alter the result. The module still never ignites TNT. You can also read its value with `.settings block-coat conservative-tnt`, or enable it with `.settings block-coat conservative-tnt true` (use your Meteor command prefix if different).
- **Liquid Filler (default on):** **liquid-filler** replaces isolated plain water source blocks with TNT when **coating-block** is **TNT**, in both normal and conservative TNT modes. A source is isolated when none of its six face neighbours contains source water, including waterlogged blocks and aquatic plants. Diagonal sources and adjacent flowing water are allowed. Joined sources, flowing-water targets, lava, waterlogged blocks and plants are left alone. Filler uses the same scan radius, reach, tunnel direction, collision checks, redstone buffer and placement budget; it ignores the surface-type and terrain-height filters and can use visible solid supports such as bedrock or obsidian. Existing blast coverage does not suppress filling. Source isolation is checked again before sending; unknown neighbour chunks defer filling. Smart Sponge priority and the pause while you are in water still apply.
- **Pause items:** edit **pause-items** in General to choose items that pause all Block Coat placement and automatic switching while held in either hand. **Flint and steel** is included by default. **pause-items-timeout** defaults to **5 seconds** (adjustable from 0–60): coating resumes after this time even if a listed item is still held. Set it to **0** to pause until you switch away. Switching away resumes immediately; a fresh hold starts a new timeout. **pause-items-reset-on-use** defaults to **on**: using a listed item on a block, entity or in air restarts the countdown, even after it has expired. Continuous use (such as drawing a bow) keeps the timer refreshed; automatic Block Coat placements do not. Disable this toggle to count only from when you start holding an item. Changing directly between listed items keeps the current timer unless one is used. Selecting a listed item cancels queued, unsent placements. Clear the list to disable this pause. Applies to every auto-switch mode and to normal/conservative TNT, filler, scaffold and Smart Jump; the timeout leaves the selected auto-switch mode in control of switching.
- **Smart jump place (default on):** places a supported block directly below your current horizontal position once your body clears it, retrying while airborne (including descent) until one interaction is sent or you land. A server rejection allows another attempt in the same jump. Can use eligible terrain or an existing coating block as support. Surrounding placements yield until the jump is resolved. It never forces jumps or movement.
- **Scaffold (default off):** a strip below you that follows your horizontal movement, including diagonals. **Scaffold width** 1–9 (default 3); even widths have one extra cell on one side. The centre footpath, up to two blocks ahead, comes first and gets first use of every tick's budget; widening uses at most half of what remains, leaving the rest for coating. Coating keeps one pending slot free for the footpath. It cannot place without a reachable, visible support and cannot guarantee catching a fast-moving player over a gap.
- **Tight-corridor preservation (default on):** keeps your occupied lane and the next two blocks open when that lane is bounded as a one- or two-wide passage. It follows movement input or actual travel, and uses facing while stationary so activation cannot immediately seal the space in front of you. In a two-wide passage the other lane can still be coated. Protection is checked again before deferred interactions are sent, moves through diagonal block crossings, and releases cells behind you. Open areas are unchanged. A one-wide tunnel cannot be fully surface-coated and remain passable at the same time, so its body-height coating is deferred until you pass.
- **Manual TNT clearance (default on):** when you directly mine TNT at body height through the crosshair in a one- or two-wide passage, Block Coat treats that as an opening you intend to enter. Both body cells in that column remain clear until your centre enters and leaves it, or for at most five seconds after the TNT disappears if you walk away. An abandoned mining attempt expires after two seconds. Deliberately mined TNT is removed from placement retry tracking, so a recently placed block is not mistaken for a server rollback. This applies only while TNT is the selected coating block and can be disabled with **respect-manual-tnt-clearance**.
- **Tunnel backfill (default off):** only targets at least 1.5 blocks behind the selected **tunnel-direction**. **Movement** (default) follows movement input and actual horizontal travel, so looking sideways does not change the backfill side; before the first movement it falls back to facing. **Facing** retains the original look-direction behaviour. Facing is captured before Meteor aims at supports and rechecked before sending. Backfill coats surfaces, not unsupported tunnel interiors, and still needs eligible terrain adjacency. The behind-only rule also applies to scaffold; Smart Jump's underfoot placement is exempt. It applies outdoors too.
- **Riding:** while rotation is on, placement pauses as a passenger (`Paused while riding`). Minecraft sends no movement packets for passengers, so rotations could not be applied and would be sent in a burst on dismount.
- **Flight:** coating works while flying (creative, elytra, Meteor Flight); these disable scaffold and smart jump. **Coat while flying** (default on) turns flight coating off, and also pauses ordinary airborne coating outside a detected jump. Enable **External flight detection** for another addon's flight, especially ground-spoofing flight; it marks flight as active (disabling scaffold even on the ground) but does not itself enable placement.

All modes keep player-body and entity collision checks, world-bound checks, visible-support and normal-reach checks. When the coating block is TNT, powered targets are skipped and a **two-block buffer in every direction** is kept around redstone components, even when they are off. This includes wire, torches, repeaters, comparators, observers, levers, buttons, pressure plates, tripwire, powered rails, sculk sensors and trapped chests. The buffer includes diagonals, vertical offsets and circuits behind walls. Unloaded terrain inside that buffer defers TNT placement until it can be checked. The check applies to coating, tunnel backfill, scaffold and Smart Jump, and runs again before a queued placement is sent. Other coating materials keep their normal behavior. Nothing is ever placed in unsupported air, and flight never extends reach.

**Supports.** **Container supports** (default on, under Server Pacing) lets chests and other interactive blocks act as supports by sneak-using them: the server briefly receives a sneak input around the placement, while your local crouch and camera are untouched. These blocks only appear once the server confirms them. Because each costs two extra packets, an ordinary face is always preferred when one is usable. **Narrow gap targeting** (default off) is edge-face targeting: it adds near-edge face samples for gaps around chests and partial blocks, but does not itself detect corridors. Neither overrides tunnel mode, player clearance or terrain rules, and a fully opaque wall can never be placed through. Exhausting the bounded visibility-ray budget now defers the candidate to a later tick rather than temporarily classifying it as unreachable.

## Smart Sponge

A separate module. Put **dry sponges** in your hotbar or offhand.

- Searches a cube (**search-radius**, default 5) for valid, unobstructed target cells with a visible, reachable support.
- Scores each spot by the connected drainable water it would absorb: water sources and flowing water, waterlogged blocks, kelp and seagrass. It models vanilla 1.21.11's six-step absorption traversal and 64-cell cap, counting water displaced by the sponge itself. Waterlogged solids are drained by adjacent placement, never replaced or mined.
- Places the highest-scoring reachable spot (ties go to the closer one). This is a best-next-placement heuristic, not a minimum-sponge solution; flowing water and server changes can alter results.
- Places one sponge, waits for the server update (up to 40 ticks), lets it settle, then rescans (**scan-interval**, default 5 ticks).
- **break-wet-sponges** (default off) mines only server-confirmed wet sponges this module placed during the current activation, with the currently held tool. Drops must be picked up normally.

- Shares Block Coat's **max-packets-per-second** (150 without Block Coat): placement and wet-sponge mining wait while the last second's packets are at the limit (`Packet limit`).
- Pauses while riding, for the same rotation reason as Block Coat.

When both modules are on, Smart Sponge takes priority while a sponge is queued or pending, for two ticks after confirmation while water settles, and while collecting wet sponges. Block Coat cancels its unsent interactions and resumes automatically afterwards. Having no water target or no sponge does not pause coating.

## Speedmine

**speedmine** ports THM Addons' packet miner to Minecraft 1.21.11. It keeps queueing,
double break, rebreak, client prediction, silent tool switching, TPS sync, optional
air mining and automatic enemy targeting. Source credits and licensing are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

For the intended **6b6t bedrock** workflow, enable **auto-mine**, **anti-phase** and
**mine-bedrock**. Bedrock uses normal mining actions and hand swings, one target
at a time; this requires the server's bedrock-breaking mechanic and does not
make bedrock breakable on vanilla servers. `mine-bedrock` remains off by default.
Eligible blocks overlapping an enemy's actual hitbox take priority over surround
blocks. Existing Meteor friends, range limits and **never-mine-own** still apply.
The latter protects the area one block around your hitbox, so nearby phase targets
can be skipped deliberately. Enable **debug-auto-mine** to see the rejected phase
position and reason while a surround target is selected.

THM clan-service filtering and Paket Limiter integration are omitted. The bedrock
path and phase targeting have automated coverage; live 6b6t compatibility still
requires in-game verification.

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

## Chat Tools

Enable **chat-tools** and press **Y** during gameplay, or click **Open Chat Tools** in the module settings. Change the shortcut under **Messages → open-popup-key**. Chat Tools is one movable, resizable window with the same layout at every size: a header bar holding the page tabs and close button, a scrolling page body, and a composer bar at the bottom. Every tab, settings category, player action and private conversation changes the body in place. Drag the header to move the window and the bottom-right corner to resize it. Like normal chat, the window is exempt from Meteor's **GUI Move**: typing never walks you around, and movement keys held when it opens are released. Normal chat has no extra HUD strip; clicking an online player's name opens their conversation inside this same window and preserves your normal-chat draft.

Every page is built from the same parts, so the window reads as one system: an optional toolbar row, small uppercase section captions (with a count or a link such as **Manage** on the right), grouped lists whose rows join into one card, shortcut tiles, and one set of 20-pixel buttons — accent **primary** actions, outlined **secondary** actions and red **danger** actions. Text uses Minecraft's font at whole-pixel scales, so it stays crisp at every GUI scale, and Meteor's accent colour marks the selected tab and primary actions. Action results appear briefly as a notice above the composer instead of moving the page.

The tabs are **Overview**, **Players**, **Clan**, **Homes**, **Hotspot** and **Settings**; the Players tab carries an unread-message badge. **Overview** starts with a player search, **Go to hotspot** (sends `/hotspot`, shown when the server offers it) and **Reply**, then any active home/TP countdowns, favourite homes as shortcut tiles that wrap into extra rows, and recent players. Each tile teleports to its home with one click; **Manage** opens Homes and **View all** opens Players. Typing in the search narrows the recent list to matching players; Enter opens Players with the name filled in. **Reply** opens the last incoming DM's conversation when known, otherwise prepares `/r ` (or `/reply `) in the composer. Each active countdown shows its own **Cancel** button only while the server still allows that teleport or request to be cancelled.

**Players** has a search bar followed by **Recent** and **Meteor friends**. Both player search bars filter immediately by the start of a name, ignoring case: typing `s` shows `Sam` and `Steve`, but not `Oscar`. Matches include recent contacts, Meteor friends, online players and incoming TP requests; Players shows additional online matches below the friends. Clearing the search restores the regular lists. Recents follow interaction order from messages and TP requests; partial searches do not create recent contacts. Up to 100 recents are saved per server/account in the hidden **recent-players** setting, so they return after rejoining or restarting the game. Friends come directly from Meteor's Friends list. Overview and Players rows share one layout: an initial avatar with an online dot, the player name with an unread badge, online/friend status (or an outgoing request/warmup/cooldown countdown), and **Message**, **TP** and **⋯** actions. **TP** sends `/tpa <player>`. An incoming request adds a second line to that player's row with its expiry countdown, **Accept** and **Decline**. **⋯** fills Players search with that name and lists Meteor friendship, clan membership and configurable player commands below the lists.

**Clan** lists your clan: Meteor friends first (online ones on top), then other online members, then everyone else, each group A–Z. Rows match Players rows, plus an **×** that removes the member from the clan. The field at the top narrows the list by the start of a name, and **Add** (or Enter) adds the usernames typed in it, separated by commas or spaces. **Import CSV** opens a file picker, and dropping a `.csv` or `.txt` file anywhere on the window imports it too. A header row naming a username column (`Name`, `Username`, `IGN`, `Player`, `Member`, `Minecraft Username`…) selects that column. Without one, a single line is read as a list of names and several lines use the first column, so extra columns such as rank are ignored. Comma-, semicolon- and tab-separated files (spreadsheet exports, UTF-8 or UTF-16) all work. Imports only add names; the notice reports how many were added, already listed or not usernames. The roster is the module's **clan-members** setting (**Clan** group), shared by every server, and can be edited there by hand.

Meteor friends' and clan members' names are coloured in chat and in the tab list, each with its own switch and colour. Friends use **friend-chat-color** and **friend-tab-color** (Meteor's friend green by default), switched by **highlight-friends** and **highlight-friends-tab** in General. Clan members use **clan-chat-color** and **clan-tab-color** (orange by default), switched by **highlight-clan-chat** and **highlight-clan-tab** in the Clan group. All four switches are on by default. The tab list can also highlight their rows with a bar in a separate colour: **highlight-friend-rows** with **friend-row-color**, and **highlight-clan-rows** with **clan-row-color** (both off by default). The bar uses its colour's alpha, so lower the alpha for a fainter bar; the defaults are about 40% opaque. **Settings → Name colours** has every switch, each with a swatch that opens Meteor's colour picker. A clan member who is also a Meteor friend gets the friend colour, unless that friend switch is off; names and rows decide this separately. The Chat Tools window shows names in their chat colours. Name colouring keeps server prefixes and Meteor's **Better Tab** formatting; a tab name that never spells out the username, such as a nickname, is coloured whole.

**Message** changes the window to a conversation. The tabs stay available, and the back arrow returns to the previous view. The conversation header shows the player, their status and **TP** / **Add friend** (or **Unfriend**); an incoming TP request from them appears below it with **Accept** and **Decline**. Incoming and outgoing messages use left/right bubbles, with incoming messages kept unread while you scroll up. Public-chat and per-player drafts are retained independently while switching views. Enter or **Send** submits the composer: plain text sends public chat on ordinary pages or a DM in a conversation, and `/commands` run as server commands. **message-command** defaults to `/msg {player}` and must include `{player}`. Outgoing DMs appear in history after the server echoes them; until then they show as dimmed bubbles. A server's whisper delay, such as 6b6t's `Please wait 2 seconds before sending another whisper!`, no longer loses messages: DMs from a conversation, and `/msg`, `/w`, `/tell`, `/whisper`, `/r` or `/reply` typed in the composer, wait in a queue of up to 20. A refused whisper is sent again as soon as the reported wait is over (up to three tries) and the refusal is hidden; the reported delay then spaces out later whispers so they are not refused at all. Whispers typed in normal chat are sent straight away but count towards the delay, and their refusals still show.

**Homes** has **New home**, **Refresh** and **Server list**, then any active home countdowns and every known home with a favourite star, **Go**, **Rename** and **Delete**. In narrow windows, Rename and Delete move to a second line so the home name and buttons remain usable. A filled star marks a favourite; click the outlined star to add one. Favourites appear on Overview, persist through Meteor settings separately for each server/account, and follow confirmed home renames/deletions. **Go** sends `/home <name>` immediately. Rename fills `/homerename <name> ` for the new name; Delete sends `/delhome <name>`. **New home** fills `/sethome ` for a home name. Creating a home only adds it to the current server's list after a successful reply. Home actions keep the page open, and a home's countdown also replaces its status line.

Homes are discovered from `/home ` tab suggestions, clickable server home commands and common `Homes: ...` lists, plus manual fallback names in the module's **homes** setting. **Refresh** requests suggestions and **Server list** sends `/homes`. Favourite names also remain available as shortcuts after reconnecting. Home controls use standard server home commands and remain visible when the command tree does not advertise them; the server enforces availability and permissions.

**Hotspot** has **Teleport**, **Refresh** and **Kill hotspot**, a **Details** card and a **Manage** list for creating/cancelling a hotspot and its appearance and notification controls. Opening it requests `/hotspot info` when the server advertises the command. The details card shows recent server replies from that request and hotspot messages; it does not invent coordinates or a destination. Nested hotspot preferences update the same page.

**Settings** combines DM notifications and hiding DMs from normal chat (switches), friend and clan name colours in chat and the tab list and row highlights in the tab list (switches with colour swatches), Auto/Manual teleport cooldown lengths (steppers) and grouped server settings for chat, bots, notifications, homes/TPA, appearance, hotspot and gameplay. Categories show an arrow; command rows show the command they send. Server controls follow the [6b6t command reference](https://www.6b6t.org/en/commands). Unadvertised server settings are hidden after the command tree arrives. Categories, Back and commands needing input stay within the same window. Esc goes back through a settings category, then to Overview, then closes the window; a conversation first returns to its previous view. Tab switches keyboard focus between the visible search field and the composer.

Player actions use the current server's Brigadier command tree with **auto-detect**, supplemented by **command-options** entries such as `Label | /command {player}`. `%player%` is also accepted, and a missing placeholder appends the player name. End a template with a space when more text is needed. Choices fill the bottom input for confirmation by default; **run-complete-commands** sends complete player commands immediately. Plugin commands that expose targets as plain text need configured entries.

Incoming TPA answers use the server prompt's own clickable commands, or its advertised `/tpy`, `/tpaccept`, `/tpyes` (and `/tpn`, `/tpdeny`, `/tpno`), falling back to `/tpy` and `/tpn`. The default unreported request expiry is **tpa-request-timeout** (120 seconds). Home and TP timers use server-reported durations, including commands typed outside the window. Unknown warmup durations say **Waiting for server** (**awaiting server** in the HUD); reaching zero still waits for a completion/cancellation reply. After confirmed success, the countdown switches to a Home or TP **cooldown** and keeps the destination/player name. Success means a completion line (such as `Teleported to destination!`, or 6b6t's `Teleporting to: <home>`), or the network moving you to another of its servers as the warmup ends — on 6b6t, teleports to a player or home on another worker finish that way. Wait times such as `You have to wait 9m 35s to teleport again.` are read whole, and a generic wait like that belongs to the command you just retried, because 6b6t keeps separate home and TPA cooldowns. Times of a minute or more read as `9m 35s`. Explicit server times take priority, including cooldowns announced alongside a warmup or reported after a retry. The module can calibrate future cooldown estimates from a known success followed by a server-reported remaining time. Cancel TP uses `/tpacancel` or the advertised `/tpcancel` alias. Cooldowns, completed actions and elapsed warmups cannot be cancelled.

**Settings → Teleport cooldowns** sets how long Home and TP cooldowns last for the current account on the current server; ranks, rewards and other reductions differ per player, so each server/account keeps its own values. After a confirmed teleport the countdown uses, in order: a time the server states (exact, no `~`), your **Manual** length, the length **learned** from the server, then the fallback. **Auto** follows what the server reports: whenever you retry during a cooldown and get a reply such as `You have to wait 4m 10s to teleport again.`, the countdown switches to that exact time and the full length (remaining time plus time since the teleport) is learned for that command. **Manual** pins your own length, for example right after claiming a reward; learning continues underneath, and the row shows the learned value. The minus/plus buttons step by 5 seconds under a minute, 30 seconds under ten minutes, then a minute; pressing them while on Auto switches to Manual from the current value, and **0 / Off** means no cooldown. Changing a length re-times an estimated countdown that is already running from when its teleport finished. The global fallbacks (**30 seconds** each by default, **0** disables) are **home-cooldown-seconds** and **tp-cooldown-seconds** in the module's **Teleport Cooldowns** group, used until the server has reported a cooldown. Estimates show `~`, for example `Home base: cooldown ~30s`; server-reported cooldowns omit it. Failed/cancelled teleports and unconfirmed warmups never start estimated cooldowns. Retries/cancel commands and duplicate success messages do not erase or restart an active cooldown. Learned and manual lengths are stored in the hidden **saved-cooldowns** setting.

A separate Meteor HUD element, **Comet → tp-home-countdown**, shows the same live home/TP warmups, post-teleport cooldowns and incoming request expiry while playing. Add it from Meteor's HUD editor to move, scale and colour it like the other Comet HUD elements. It hides when idle or Chat Tools is disabled, and shows sample countdowns in the editor.

DM recognition supports common `From Name:`, `To Name:`, `Name whispers [to you]:`, `You whisper to Name:` and `[Name -> me]` / `[me -> Name]` formats, including the player-head sprite servers such as 6b6t put before the name (`You whisper to [unknown player head] Name:`). Add full-line regexes under **incoming-dm-patterns** and **outgoing-dm-patterns**, with username in capture group 1 and body in group 2; invalid entries are ignored. **hide-private-messages** defaults off, and **dm-toasts** defaults on. Opening a conversation marks it read; scrolling up keeps new arrivals unread until you return to the bottom. Name colours preserve normal chat formatting and click/hover actions while colouring whole friend and clan member names. Recognised DMs and player-authored chat cannot imitate server home/TP results.

Conversations (up to 100, with 250 messages each), queued whispers, drafts and frame position remain in memory for the current connection and clear on disconnect/server change or module disable. Moving between servers of one network, such as 6b6t's workers, keeps them along with homes, requests and timers. Recents and favourites persist per server/account. The redesigned layout and live server interactions still need an in-game check.

## Proxy tools

Comet adds two helpers to Meteor's **Proxies** screen and one to the disconnect
screen.

- **Fetch Free Proxies** downloads up to 24 SOCKS4/SOCKS5 proxies from
  ProxyScrape's public list, keeps entries with at least 50% uptime and a measured
  latency of 1–3000 ms, puts residential ISPs first, adds them to Meteor's proxy
  list and tests them off-thread. Review the list and test a proxy before
  connecting; free proxies are untrusted and can see or alter your traffic.
- **Proxy links** (on by default) opens web links you click in chat or menus in a
  separate Chrome/Chromium or Firefox profile bound to Meteor's enabled proxy.
  Profiles live in `meteor-client/proxy-browser/`; the toggle is saved in
  `meteor-client/proxy-links.txt`. If no supported browser is found, the click
  falls back to the system browser and its normal connection.
- **Verification links:** when a disconnect screen contains a URL, Comet adds
  **Open Verify Link** and **Open Via Proxy** buttons. The direct button uses a
  clean local browser profile. The proxy button uses the enabled proxy and, with
  Chrome/Chromium, fills the username, ticks the confirmation box and submits once
  you have solved the captcha; it does not solve or bypass the captcha.

Proxy use and verification links are subject to the server's rules and terms of
service. Comet does not create accounts, solve captchas or bypass bans. Check a
free proxy's location and trustworthiness before using it.

## Baritone Elytra

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

## Elytra Log

A separate module, **elytra-log**. While you're flying, once the elytra you're wearing has **durability** or less left (default 20) and there's no spare elytra in your inventory, it logs you out. What's left is what you glide down with after logging back in, about one point per second of gliding.

- A spare counts only if it has more than **durability** left, so you can swap it on before being logged out. It looks at the hotbar and main inventory, not the offhand. Any item with the glider component counts as an elytra, and one that cannot be damaged never wears out.
- Comet no longer swaps elytras for you. Meteor's Elytra Fly has its own **elytra-replace** and **replace-durability** options, which work while Elytra Fly is on.
- It only acts while you're gliding, or in the air with ElytraFly's Packet mode. Standing or walking with a worn-out elytra doesn't log you out.
- **warning-margin** (default 10, 0 disables) warns in chat once when the worn elytra is within that much durability of logging you out with no spare.
- After logging you out it turns itself off, so opening the elytra after you rejoin doesn't log you out again. It also turns off Meteor's AutoReconnect, which would put you straight back in the air.
- You rejoin where you logged out, in mid-air. Open the elytra straight away and glide down.
- For Baritone elytra flights, keep **durability** above Baritone's `elytraMinimumDurability` (default 5), or Baritone lands before you're logged out.

## HUD elements

Open Meteor's HUD editor and add elements from the **Comet** group. They look like Meteor's own text elements such as FPS and Speed: each label takes the first of the HUD's **text-colors** and its value the second, and they have the same **shadow**, **alignment**, **border**, **custom-scale** and **background** settings (background off by default).

- **adaptive-elytra-fly-engine**: Adaptive Elytra Fly's state, output, attainable maximum, boost ramps, setbacks and the simulated server glide. Shown while the module is on.
- **tp-home-countdown**: Chat Tools home/TP warmups, cooldowns, outgoing request expiry and incoming request expiry. Hidden when idle; uses the same countdown data as the Chat Tools window.
- **speed-3d**: your measured total 3D speed, climbing and diving included, in blocks/second or km/h with 1–40 ticks of smoothing, plus a **Maneuvering ≤ 200** line while Adaptive Elytra Fly's maneuver mode caps it.
- **player-speed**: your measured speed in blocks/second or km/h, optionally including vertical movement, with 1–40 ticks of smoothing. It uses real time, so Timer cannot skew it, and teleports such as `/home` never show as a spike. It replaces the former **player-speed-hud** module.
- **dub-counter**: dubs (ordinary chest blocks ÷ 2) and the chest and loaded-chunk totals from the **dub-counter** module. Shown while the module is on; the editor reminds you to enable it.

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

Block updates refresh only the changed cell and its neighbours. Moving one block reuses the overlapping region and reads only newly entered cells (121 instead of 1,331 at radius 5). A full rescan still runs on settings changes, resets and every 40 ticks. Support points are cached and revalidated before sending. With TNT, the two-block redstone buffer reads blocks only in chunk sections whose palette can hold a redstone component, so most checks cost a few palette lookups instead of 125 block reads. Ray casts are capped per tick, and positions that fail visibility wait three ticks. Smart Sponge scores all spots first and ray-casts supports only in score order. **placement-order** (default Nearest) sorts candidates, including retries, by distance.

## Upgrading from 0.6.1

- **placement-priority** and **prefer-nearby** are merged into **placement-order** (default Nearest). Old saved values are not carried over.
- **auto-switch** keeps your saved value (probably Silent); select **Sticky** to save two packets per placement.
- **rotate** now also applies in Adaptive and Conservative. If you had it saved off, those modes now place without rotating.
- New under Server Pacing: **max-packets-per-second**, **extra-rotation-packets**. With extra rotation packets off, Adaptive places at most one rotated block per tick (it previously could ramp to two).

## Build and tests

Run `./gradlew clean build` (`gradlew.bat clean build` on Windows). Output: `build/libs/comet-0.25.20-mc1.21.11.jar` and the matching `build/distributions/comet-0.25.20-mc1.21.11-source.zip`, which includes `vendor/`. `./gradlew test` runs the automated tests on their own. Gradle downloads the Java 21 toolchain if needed. Loom Remap (1.17.21, official Mojang mappings) and Meteor (snapshot build 82, `1.21.11-82`) are pinned in `gradle/libs.versions.toml`. Meteor build 82 is retained in `vendor/maven` because the upstream snapshot repository pruned it; see `vendor/README.md` for its source and checksum. Meteor is not bundled in Comet. Xaero's Minimap (`fabric-1.21.11-26.6.0`) comes from Modrinth's Maven and Baritone (`1.21.11-20260103.131549-1`, the same jar as `baritone-meteor-1.21.11.jar`) from Meteor's Maven, as compile-only dependencies; neither is bundled. CI runs the same build on every push and pull request.

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
- adaptive ElytraFly: recorded flight feel (sixteen scripted flights under defaults and the user's saved settings must reproduce their per-tick movement and boost ramps exactly), setbacks that only rewind boost, overdrive and the shared 3D server limit; adaptive cruise/Sprint speed caps, boost caps that stop the fixed curve, maneuver mode's 3D cap (only when climbing and turning together, and re-ramping from the cap afterwards), Sprint acceleration and vertical boost, smooth vertical release and adjustable vertical reversal; low-speed wall manoeuvring and recovery after guarding, preserved steering, reversals and unloaded-chunk protection; bounded swept-body terrain checks, negative coordinates, blended turns and floor touchdown
- Auto Wasp: the server's per-packet limits (including two packets in one tick), VolytraFly's horizontal and vertical boost curves with caps, the 3D limit while climbing or descending (its hold, the ramps' shares and recovery from it), collision-only speed loss, setback rewind and hold, pinned and teleport corrections, settling after elytra swaps, paced takeoff, ping-aware target prediction, worn-out elytra and chestplate choice with swap hysteresis, the server-glide guard and steering against walls and unloaded chunks, and navigation endpoints

The 1.21.11 port passes all 819 automated tests and 224 offscreen shader pixel checks against Meteor 1.21.11-82. All configured mixin targets are checked against the pinned dependencies. In-game verification is still pending for placement timing, rotations, Sticky switching, container sneak-use and server acceptance, as well as the Xaero markers, waypoint deletion, every part of Baritone Elytra (hand-off, take-off, restocking, chestplate swaps, landing and walking the rest), Elytra Log's logout, and Auto Wasp's pursuit, takeoff, chest swaps, setback recovery and server-glide guard.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for
the build, test and style expectations, and report the branch and Minecraft
version you used for gameplay problems.

## Licence and credits

Original Comet code is released under **CC0 1.0** (`LICENSE-CC0`). The repository
as a whole, and every JAR and source archive, is distributed under
**GPL-3.0-only** (`LICENSE`, version 3 only) because it combines that code with GPL-3.0-only
adaptations of THM Addons and DEVILS, an Apache-2.0 adaptation of
NoTextureRotations (`LICENSE-NoTextureRotations`), and Majkey's DubCounter
(included with permission). VolytraFly's CC0 boost curve is credited in the code
and in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), which lists every
component, its source revision and the files it covers, including the optional
Meteor, Baritone and Xaero integrations.

If you redistribute Comet, ship the matching
`build/distributions/comet-<version>-mc1.21.11-source.zip` with the JAR, as
GPL-3.0-only requires. The JAR bundles `LICENSE`, `LICENSE-CC0`,
`LICENSE-NoTextureRotations` and `THIRD_PARTY_NOTICES.md`.
