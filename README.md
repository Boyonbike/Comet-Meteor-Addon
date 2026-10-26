# Comet by Boyonabike

[![Build](https://github.com/Boyonbike/Comet-Meteor-Addon/actions/workflows/build.yml/badge.svg?branch=26.2)](https://github.com/Boyonbike/Comet-Meteor-Addon/actions/workflows/build.yml)
[![Licence](https://img.shields.io/badge/licence-GPL--3.0--only-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-3c8527.svg)](https://www.minecraft.net/)
[![Meteor Client](https://img.shields.io/badge/Meteor%20Client-build%2035-8e44ad.svg)](https://meteorclient.com/)

Comet **0.26.26** is a Meteor Client addon for **Minecraft 26.2** with
elytra flight, building tools, storage rendering, chat automation and inventory upgrades.

## Built for 6b6t

Comet is designed primarily for **6b6t**. Its defaults, movement handling,
server-aware timing and automation are tuned around how 6b6t behaves.
It can also be used on other servers, but settings may need adjusting:
commands, cooldowns and movement checks can differ.

## From personal mods to a curated collection

Comet started as a few small custom modules built for personal use. It has grown
into a collection aimed at bringing together best-in-class modules, adapting and
optimising them for 6b6t alongside original Comet features. The focus is on making
strong modules work well together, with consistent settings and practical tuning.

## Install

1. Install Fabric Loader **0.19.3+**, Meteor Client for Minecraft **26.2**
   (tested with build **35**) and **Java 25**.
2. Get the matching JAR from [Releases](https://github.com/Boyonbike/Comet-Meteor-Addon/releases)
   or [build from source](docs/guides/building.md).
3. Remove the old Comet JAR, put the new one in `.minecraft/mods`, and open Meteor’s **Comet** category.

The Fabric mod ID is `comet` (previously `tnt-coating`). Xaero Waypoints needs Xaero’s Minimap;
Baritone Elytra needs Meteor’s Baritone build. See [installation](docs/guides/installation.md).

## Modules

- **Flight:** Adaptive Elytra Fly, Highway Fly, DEVILS Auto Wasp and Baritone Elytra.
- **Travel helpers:** independent Anti Portal, Auto Set Home and Auto Lobby integration.
- **Building:** Comet Scaffold, Block Coat, Smart Sponge, Speedmine and Meteor Scaffold upgrades.
- **Storage and rendering:** Fast Storage ESP, Shulker Icons, Dub Counter and Anti Coords.
- **Chat:** homes, teleport requests, clan management and [buffered Auto TPY](docs/modules/auto-tpy.md)
  with priority retries, CSV whitelist import, a blocklist and optional adaptive timing.
- **Inventory and utilities:** Auto Tool/Weapon, Inventory Tweaks, Auto Replenish, allay tools,
  waypoint tools, Discord reports and HUD elements.

**[Browse every module and its settings →](docs/README.md)**

For Highway Fly, start with its [guide](docs/modules/highway-fly.md) and configure each helper
in its own module. Auto TPY defaults off; configure its whitelist before enabling it.

## Version branches

| Minecraft branch | Java | Tested Meteor build | Release JAR |
| --- | --- | --- | --- |
| [26.1.2](https://github.com/Boyonbike/Comet-Meteor-Addon/tree/26.1.2) | 25 | 42 | `comet-0.26.26-mc26.1.2.jar` |
| [26.2](https://github.com/Boyonbike/Comet-Meteor-Addon/tree/26.2) | 25 | 35 | `comet-0.26.26-mc26.2.jar` |
| [1.21.11](https://github.com/Boyonbike/Comet-Meteor-Addon/tree/1.21.11) | 21 | 82 (vendored) | `comet-0.26.26-mc1.21.11.jar` |

Each branch builds independently with pinned dependencies and the same release number.
See [Meteor compatibility](docs/guides/module-settings.md) for how changed hooks are handled.

## Development and support

```sh
./gradlew build
python3 scripts/check_docs.py
```

The build runs tests and writes a JAR plus its corresponding `-source.zip`.
See [build and tests](docs/guides/building.md), [CONTRIBUTING.md](CONTRIBUTING.md),
[CHANGELOG.md](CHANGELOG.md), and [HUD documentation](docs/guides/hud.md).
Report problems with your Minecraft branch, Meteor build and reproduction steps.
Automated checks do not replace live server testing; individual guides record known limits.

## Licence and credits

Much of Comet is possible because of the work shared by open-source developers.
Credit for upstream modules, algorithms and ideas belongs to their original authors
and contributors. Comet adapts, integrates and tunes that work alongside its own code;
please recognise and support the upstream projects credited below.

The combined addon is **GPL-3.0-only**. Original Comet code is also available under CC0;
third-party adaptations retain their notices. Distribute the matching source ZIP with each JAR.
See [LICENSE](LICENSE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and the
[full credits](docs/guides/credits.md).

Comet is unofficial and is not affiliated with Meteor Development, Mojang or any server.
Use automated features only where the server’s rules allow them.
