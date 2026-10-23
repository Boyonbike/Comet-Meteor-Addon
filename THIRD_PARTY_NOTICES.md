# Third-party source

Speedmine and its mining, reach, rendering and option adaptations originate from
THM Addons: https://github.com/Magnus-Krill/THM-Addons
Commit: e952b86ff4e3836ba5c7db982fad22205126ef3a (26.2 branch).
Copyright THM Addons contributors; original headers and contributor credits are retained.
License: GNU GPL version 3, included as LICENSE-Speedmine.
Air-mining adaptation credits Quiettee Utils (GPL-3.0), as noted in its source.

The adapted files are Speedmine.java, SpeedmineRange.java, SpeedmineRender.java,
SpeedmineOption.java, SpeedmineAirMixin.java, SpeedmineBlockUpdateMixin.java,
SpeedminePredictionAccessor.java, and SpeedmineTest.java.
Comet adaptations target Minecraft 26.2 and remove THM services and limiter dependencies.
Original Comet source remains available under its CC0 license (LICENSE).
The combined distribution includes GPL-covered code and must be distributed under
GPL v3 terms, including corresponding source for the combined work.

## DEVILS Auto Wasp

AutoWasp.java and modules/autowasp/{AutoWaspFlightController.java,
AutoWaspPathfinder.java,AutoWaspPathSearch.java} are adapted from DEVILS:
https://github.com/ThianYong-hub/DEVILS
Revision: 2440a168c3ba6f051f2adf8249a8e6fb5f3a5b02 (main).
Upstream paths: devils-addon/src/main/java/com/devils/addon/modules/AutoWasp.java
and devils-addon/src/main/java/com/devils/addon/modules/autowasp/.
Credit: ThianYong-hub and DEVILS contributors.
The Auto Wasp implementation is also based on Meteor Client,
Copyright (c) Meteor Development: https://github.com/MeteorDevelopment/meteor-client
License: GNU GPL version 3 (GPL-3.0-only); full text in LICENSE-DEVILS.

Modified for Comet by Boyonabike on 22 October 2026: ported to Minecraft 26.1.2
and current Meteor mappings; registered separately as auto-wasp-devils; replaced
DEVILS-specific logging/crash wrappers and hard-coded AutoArmor chat commands
with Comet lifecycle guards and local chest-slot inventory swaps; added movement
controller conflict handling, loaded-terrain checks, precise per-tick navigation
caches, reachable path endpoints, and visible settings-menu attribution.
Further modified for Comet 0.25.15 on 22-23 October 2026: VolytraFly boost
ramps up to the server's per-packet movement limit, a lower 3D limit while
climbing or descending, collision-only speed loss, setback rewind, hold and
pinned-glide recovery, live-target steering in
plain sight, settling after elytra swaps, paced takeoff, a server-glide wall
guard, ping-aware target prediction, worn-out elytra handling, chest-swap
hysteresis, block-keyed navigation caches and a time-budgeted path search.
The new files modules/autowasp/{AutoWaspRamp.java, AutoWaspSwapDelay.java,
AutoWaspTakeoff.java, AutoWaspChestSwap.java, AutoWaspSetbacks.java,
AutoWaspSpeed.java, AutoWaspTargetTracker.java, AutoWaspServerGlide.java,
AutoWaspGlideTracker.java} and mixin/AutoWaspFireworkAccessor.java were written
for Comet as part of this port and are distributed under the same GPL v3 terms.
These modified files remain covered by GPL v3 and carry source/provenance notices.
No warranty is provided, including merchantability or fitness for a particular purpose.

The settings credit does not replace the licence or corresponding source.
`./gradlew build` packages LICENSE-DEVILS and this notice inside the JAR and
produces `build/distributions/comet-<version>-mc26.2-source.zip`, containing
the modified source for the combined addon, licence notices, and Gradle build
inputs. Distribute this matching source archive alongside the compiled JAR under
GPL v3 terms. Dependencies are declared in the build files and resolved by Gradle.

## NoTextureRotations

Anti Coords' texture rotation and offset hiding is adapted from NoTextureRotations by rfresh2:
https://github.com/rfresh2/NoTextureRotations
Revisions: 5f20453dce38f04a9b1671fe4638a68c882272d1 (26.x branch) and
8cb7deeede7205474be36b5863890b610d6095f3 (keyed-hash branch).
License: Apache License 2.0, full text in LICENSE-NoTextureRotations.

The adapted files are modules/AntiCoords.java, core/KeyedHash.java and
mixin/{AntiCoordsBlockStateMixin.java,AntiCoordsEntityTickMixin.java}.
Modified for Comet by Boyonabike on 23 October 2026: rewritten as a Meteor module
with Fixed and Keyed modes and no other config; the texture seed hook moved from
Block to the block state so doors, beds and tall plants are covered too; offsets are
only hooked for blocks that have one; SipHash-2-4 for one block is computed without
allocating; the key is kept in meteor-client/comet/anti-coords.key; the entity tick
is tracked without Fabric API. AntiCoordsScreenshotMixin.java, AntiCoordsFrameMixin.java
and KeyedHashTest.java were written for Comet.
