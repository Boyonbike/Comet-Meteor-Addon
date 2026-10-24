# Third-party notices

Comet by Boyonabike is an addon for Meteor Client. Original Comet code is published
under CC0 1.0 (`LICENSE-CC0`). The JAR and source archive combine that code with
GPL-3.0-only adaptations, so the combined work is distributed under **GNU GPL
version 3 only (GPL-3.0-only)**, whose full text is `LICENSE`. The "or any later
version" option in the GPL's example notice is not used. The Apache-2.0 text
for the NoTextureRotations adaptation is `LICENSE-NoTextureRotations`. Every
third-party component is listed below with its licence, source and the files it
covers.

Copyright (c) 2026 Boyonabike and Comet contributors.

## Summary

| Component | Licence | How Comet uses it |
| --- | --- | --- |
| Comet original code | CC0-1.0 | `LICENSE-CC0` |
| THM Addons (Speedmine) | GPL-3.0-only | Adapted source; see `LICENSE` |
| DEVILS (Auto Wasp) | GPL-3.0-only | Adapted source; see `LICENSE` |
| NoTextureRotations (Anti Coords) | Apache-2.0 | Adapted source; see `LICENSE-NoTextureRotations` |
| VolytraFly | CC0-1.0 | Boost-curve behaviour, credited |
| Majkey's DubCounter | No upstream licence; included with permission | Adapted source |
| Meteor Client | GPL-3.0-only | Runtime dependency and API; not bundled |
| Baritone | LGPL-3.0-only | Optional compile-only API; not bundled |
| Xaero's Minimap / World Map | Proprietary | Optional compile-only API; not bundled |
| Fabric Loader / Fabric API | Apache-2.0 | Runtime dependencies; not bundled |
| Minecraft | Mojang EULA | Runtime dependency; not bundled |

## Comet original code

Code written for Comet, including modules that do not adapt third-party source, is
released under CC0 1.0 (`LICENSE-CC0`). Because the distributed JAR also contains
GPL-3.0-only adaptations, the repository and every binary release as a whole are
distributed under GPL-3.0-only (`LICENSE`). CC0 permits this; the original code
remains available under CC0 as well. Contributions are accepted under GPL-3.0-only.

## Comet Scaffold — original implementation and design references

The independent `CometScaffold`, placement adapter, `core/scaffold` policies and
their integration mixins are new Comet implementations. They reuse Comet's
existing StickySlot, face-sampling, packet-cost, sequenced-interaction and logging
helpers and depend on Meteor's GPLv3 API. No additional client JARs or assets are
bundled. Their original code remains available under `LICENSE-CC0`; the combined
distribution stays GPL-3.0-only with the matching source archive.

The following revisions were studied for functional requirements, without copying
their scaffold source into this implementation:

- LiquidBounce/CCBlueX: `3889854bc55e45f208c8ae16a46bb9edf578d0f7`,
  https://github.com/CCBlueX/LiquidBounce/tree/3889854bc55e45f208c8ae16a46bb9edf578d0f7
  — movement prediction, stable planning and plan/verify separation; GPLv3.
- BlackOut/KassuK and OLEPOSSU: `b17e1bfec963bcac04b61c2394f0ad1fb9b9bcf6`,
  https://github.com/KassuK1/BlackOut/tree/b17e1bfec963bcac04b61c2394f0ad1fb9b9bcf6
  — short collision-stopped prediction; GPLv3.
- FDPClient/SkidderMC and LiquidBounce contributors: `07d03078b3fe12ac7278c678f56d0e7f607fbec2`,
  https://github.com/SkidderMC/FDPClient/tree/07d03078b3fe12ac7278c678f56d0e7f607fbec2
  — support-face search/scoring; GPLv3.
- ThunderHack-Recode/Pan4ur: `49c76bcc5a5e4fbd12b06305c09f0c8fb76c510b`,
  https://github.com/Pan4ur/ThunderHack-Recode/tree/49c76bcc5a5e4fbd12b06305c09f0c8fb76c510b
  — inventory/height policy requirements; GPLv3.
- HIGTools/RedCarlos26: `cd83cfbf1ed65bb647de16749b4197f78f341642`,
  https://github.com/RedCarlos26/HIGTools/tree/cd83cfbf1ed65bb647de16749b4197f78f341642
  — configured-height requirements; GPLv3.

Trouser-Streak's `c6fb1dda9d7beb9f06066e5676848fac0d8faac0` was an ideas-only
reference for height and expansion. No grant for its modifications was verified,
and none of its source is copied. RusherHack and Future documentation supplied
feature/geometry checklists only; no proprietary implementation or assets are used.

## Speedmine — THM Addons

Speedmine and its mining, reach, rendering and option adaptations originate from
THM Addons: https://github.com/Magnus-Krill/THM-Addons
Commit: e952b86ff4e3836ba5c7db982fad22205126ef3a (26.2 branch).
Copyright THM Addons contributors; original headers and contributor credits are retained.
Licence: GNU GPL version 3 only (GPL-3.0-only); full text in `LICENSE`.

The adapted files are `Speedmine.java`, `SpeedmineRange.java`,
`SpeedmineRender.java`, `SpeedmineOption.java`, `SpeedmineAirMixin.java`,
`SpeedmineBlockUpdateMixin.java`, `SpeedminePredictionAccessor.java` and
`SpeedmineTest.java`. Comet adaptations target Minecraft 26.1.2 and remove THM
services and limiter dependencies. New Comet helper classes such as
`SpeedminePolicy`, `SpeedmineTiming`, `SpeedmineInventory` and
`SpeedmineTargeting` are original code.

Air mining is adapted from Quiettee Utils by FragmentZero6b6t, GPL-3.0-only:
https://github.com/FragmentZero6b6t/quiettee-utils
The credit is repeated in `SpeedmineAirMixin.java`.

## DEVILS Auto Wasp

`AutoWasp.java` and `modules/autowasp/{AutoWaspFlightController.java,
AutoWaspPathfinder.java, AutoWaspPathSearch.java}` are adapted from DEVILS:
https://github.com/ThianYong-hub/DEVILS
Revision: 2440a168c3ba6f051f2adf8249a8e6fb5f3a5b02 (main).
Upstream paths: `devils-addon/src/main/java/com/devils/addon/modules/AutoWasp.java`
and `devils-addon/src/main/java/com/devils/addon/modules/autowasp/`.
Credit: ThianYong-hub and DEVILS contributors.
The Auto Wasp implementation is also based on Meteor Client,
Copyright (c) Meteor Development: https://github.com/MeteorDevelopment/meteor-client
Licence: GNU GPL version 3 only (GPL-3.0-only); full text in `LICENSE`.

Modified for Comet by Boyonabike on 22 October 2026: ported to Minecraft 26.1.2
and current Meteor mappings; registered separately as `auto-wasp-devils`; replaced
DEVILS-specific logging/crash wrappers and hard-coded AutoArmor chat commands
with Comet lifecycle guards and local chest-slot inventory swaps; added movement
controller conflict handling, loaded-terrain checks, precise per-tick navigation
caches, reachable path endpoints, and visible settings-menu attribution.
Further modified for Comet 0.25.15 on 22-23 October 2026: VolytraFly boost
ramps up to the server's per-packet movement limit, a lower 3D limit while
climbing or descending, collision-only speed loss, setback rewind, hold and
pinned-glide recovery, live-target steering in plain sight, settling after elytra
swaps, paced takeoff, a server-glide wall guard, ping-aware target prediction,
worn-out elytra handling, chest-swap hysteresis, block-keyed navigation caches
and a time-budgeted path search.

The new files `modules/autowasp/{AutoWaspRamp.java, AutoWaspSwapDelay.java,
AutoWaspTakeoff.java, AutoWaspChestSwap.java, AutoWaspSetbacks.java,
AutoWaspSpeed.java, AutoWaspTargetTracker.java, AutoWaspServerGlide.java,
AutoWaspGlideTracker.java}` and `mixin/AutoWaspFireworkAccessor.java` were
written for Comet as part of this port and are distributed under the same
GPL-3.0-only terms. These modified files remain covered by GPL v3 and carry
source/provenance notices.

The settings credit does not replace the licence or corresponding source.

## NoTextureRotations — Anti Coords

Anti Coords' texture rotation and offset hiding is adapted from NoTextureRotations
by rfresh2: https://github.com/rfresh2/NoTextureRotations
Revisions: 5f20453dce38f04a9b1671fe4638a68c882272d1 (26.x branch) and
8cb7deeede7205474be36b5863890b610d6095f3 (keyed-hash branch).
Copyright rfresh2.
Licence: Apache License 2.0, full text in `LICENSE-NoTextureRotations`.
The upstream project has no separate NOTICE file; this section and the source
headers provide the required attribution.

The adapted files are `modules/AntiCoords.java`, `core/KeyedHash.java` and
`mixin/{AntiCoordsBlockStateMixin.java, AntiCoordsEntityTickMixin.java}`.
Modified for Comet by Boyonabike on 23 October 2026: rewritten as a Meteor module
with Fixed and Keyed modes and no other config; the texture seed hook moved from
Block to the block state so doors, beds and tall plants are covered too; offsets are
only hooked for blocks that have one; SipHash-2-4 for one block is computed without
allocating; the key is kept in `meteor-client/comet/anti-coords.key`; the entity
tick is tracked without Fabric API. `AntiCoordsScreenshotMixin.java`,
`AntiCoordsFrameMixin.java` and `KeyedHashTest.java` were written for Comet.

## VolytraFly

Adaptive Elytra Fly's unlimited-boost curve and Auto Wasp's pursuit ramps follow
the speed curve and behaviour published in VolytraFly by Volizray:
https://github.com/Volizray/VolytraFly-Addon
Licence: CC0 1.0 Universal (CC0-1.0), public domain.
VolytraFly's licence imposes no attribution condition; the credit is recorded
here, in the modules' comments and in Adaptive Elytra Fly's settings menu because
the curve was its author's work. Comet's implementation of the curve is its own;
no VolytraFly source file is copied into this repository.
The relevant Comet files are `core/BoostRamp.java`, `core/AdaptiveFlightEngine.java`,
`modules/AdaptiveElytraFly.java`, `modules/autowasp/AutoWaspRamp.java`,
`modules/autowasp/AutoWaspSpeed.java` and
`modules/autowasp/AutoWaspFlightController.java`.

## Majkey's DubCounter

The `dub-counter` module, its HUD element and its maths are adapted from
Majkey's DubCounter 1.0.0 addon by Majkey. The original addon shipped without a
licence file. It is included in Comet with the author's permission, and the
adapted code is distributed as part of this GPL-3.0-only combined work.

Adapted files: `modules/DubCounter.java`, `core/DubCount.java` and
`hud/DubCounterHud.java`; `test/core/DubCountTest.java` was written for Comet.
Modified for Comet by Boyonabike: counts block entities only, refreshes five times
a second instead of every tick, adds cached totals and HUD lines, and registers
as part of Comet. Remove the standalone `DubCounter-1.0.0-mc26.1.2.jar` when
installing Comet, because both register `dub-counter`.

## Meteor Client

Comet is built for and loaded by Meteor Client, Copyright (c) Meteor Development:
https://github.com/MeteorDevelopment/meteor-client
Licence: GNU GPL version 3 only (GPL-3.0-only).
Meteor is a runtime dependency and is not bundled in Comet's JAR. Comet uses its
public addon API, settings, events and mixin targets. The Auto Wasp code that
originated in Meteor is covered by the DEVILS section above. Any Meteor code
copied or adapted elsewhere remains under GPL-3.0-only and the combined work is
distributed under the same licence.

## Baritone

Comet optionally integrates with Baritone, the Meteor build:
https://github.com/cabaletta/baritone
Licence: GNU Lesser General Public License version 3 only (LGPL-3.0-only).
Baritone is an optional, compile-only dependency; its classes are not bundled in
Comet and players install it separately. Comet calls its public API and does not
copy Baritone source. The LGPL's relinking and notice requirements are met by
keeping Baritone as a separate jar and publishing Comet's complete corresponding
source in the source archive.

## Xaero's Minimap and World Map

Comet's `xaero-waypoints` and `waypoint-delete` modules integrate with Xaero's
Minimap and World Map by Xaero:
https://modrinth.com/mod/xaeros-minimap
https://modrinth.com/mod/xaeros-world-map
Those mods are proprietary and are not covered by an open-source licence. They
are optional, compile-only dependencies and are not bundled in Comet. Comet only
calls their public classes through its compatibility bridge; no Xaero code or
assets are copied into this repository. Players must obtain Xaero's mods from
their author and follow Xaero's own terms.

## Fabric and Minecraft

Comet is built with Fabric Loader and Fabric API (Apache-2.0) and for Minecraft
(Mojang EULA). These are runtime dependencies, are not bundled in Comet's JAR,
and remain subject to their own licences. Comet's Gradle build resolves them
through Fabric's and Mojang's repositories; see `gradle/libs.versions.toml`.

## BepHax code removed

Earlier revisions of Comet included Angle Calculator, Anti Spawn Set, No Hurt Cam
and Phase code ported from BepHax Community. BepHax Community is proprietary,
closed-source software whose licence prohibits derivative works; it has no
licence that is compatible with distribution here. Those ports were therefore
removed:

- **Angle Calculator**, **Anti Spawn Set** and **No Hurt Cam** were rewritten as
  original Comet implementations. Their current source files carry the `CC0-1.0`
  identifier and were written for Comet by Boyonabike. No Hurt Cam's module and
  its `NoHurtCamMixin` are included.
- Phase was removed from Comet before this revision.
- No BepHax source code or assets are included in this revision. The names above
  describe the features only; Comet is not affiliated with or endorsed by BepHax.

## Source distribution and GPL compliance

`./gradlew build` packages `LICENSE`, `LICENSE-CC0`, `LICENSE-NoTextureRotations`
and this notice inside the JAR. It also produces
`build/distributions/comet-<version>-mc26.1.2-source.zip`, containing the complete
modified source, Gradle build inputs, licence texts and this notice. Distribute
that matching source archive alongside the compiled JAR under GPL-3.0-only terms.
Dependencies are declared in the Gradle files and resolved by Gradle; they are
not part of the source archive.

## No warranty

The third-party code and Comet's adaptations are provided without any warranty,
including merchantability or fitness for a particular purpose. See the full
licence texts in `LICENSE`, `LICENSE-CC0` and
`LICENSE-NoTextureRotations` for the applicable terms.
