# Module settings and compatibility

[Documentation index](../README.md) · [Project README](../../README.md)

Open Meteor's **Comet** category. Modules appear in this order, grouped by purpose:

- **Flight and movement:** **adaptive-elytra-fly**, **highway-fly**, **anti-portal**, **auto-set-home**, **auto-lobby**, **auto-wasp-devils**, **baritone-elytra**, **elytra-log**, **angle-calculator**.
- **Rendering, storage, waypoints and chat:** **fast-storage-esp**, **dub-counter**, **shulker-icons**, **no-hurt-cam**, **anti-coords**, **xaero-waypoints**, **waypoint-delete**, **chat-tools**, **call-to-arms**, **stash-report**.
- **Building:** **scaffold**, **comet-scaffold**, **block-coat**, **smart-sponge**, **speedmine**, **safe-fast-use**, **anti-spawn-set**.
- **Combat and inventory:** **auto-tool**, **auto-weapon**, **inventory-tweaks**, **auto-replenish**.
- **Allays:** **allay-farm**, **allay-shulker-killer**. See [Allays](allay-behaviour.md#allay-interaction-and-coordination).
- **HUD elements** (Meteor's HUD editor, **Comet** group): **adaptive-elytra-fly-engine**, **player-speed**, **speed-3d**, **tp-home-countdown**, **auto-set-home-timer**, **highway-fly-status**, **highway-fly-runtime** and **dub-counter**. See [HUD elements](hud.md#hud-elements).

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

**Newer Meteor builds.** Before applying an upgrade to a Meteor module, Comet checks that the installed Meteor still has every method, field and code location the upgrade attaches to. If a Meteor build has changed one, that upgrade alone is skipped: the stock module keeps working, it is not listed under Comet, and chat names the skipped upgrades when you join a world (details are in the log). Adaptive Elytra Fly refuses to start if its Elytra Fly hook was skipped. Builds older than 35 are rejected by Fabric with a clear message.

Version history is in [CHANGELOG.md](../../CHANGELOG.md). Phase, Elytra Replace and the
Trajectories upgrade were removed in 0.25.0; see the
[0.25.0 changelog](../../CHANGELOG.md#0250). Upgrading from 0.6.1? See
[Upgrading](upgrading.md#upgrading-from-061).
