<!--
  This file is part of THM Addons — https://github.com/Leonn170709/THM-Addons
  Copyright (c) THM Addons contributors. Credit the devs, keep the link.
  By using this code you agree to the license terms and to keep your repo public.
-->

# Speedmine source extraction

Extracted without changes from https://github.com/Magnus-Krill/THM-Addons,
branch `26.2`, commit `e952b86ff4e3836ba5c7db982fad22205126ef3a`.

The module is `src/main/java/xyz/thm/addon/modules/Speedmine.java`.
Included: the two mining mixins, five accessor mixins, inventory/range/render
helpers, the option interface, upstream Speedmine tests, and upstream LICENSE.
Original credit headers are preserved. SOURCE.json records SHA-256 hashes.

## Compatibility

This is a source extraction, not an independently buildable mod or installable JAR.
Upstream targets Minecraft 26.2, Java 25, Fabric Loader 0.19.5,
Fabric API 0.161.0+26.2, and Meteor Client 26.2-SNAPSHOT, with Mojang names.
The surrounding local project targets Minecraft 26.1.2 and needs a port before integration.
No source has been added to the surrounding project's active source tree.

## Host integration points still required

- Replace `THMAddon.PVP` with the host's module category, and `THMAddon.THMColor`
  with its default color (upstream RGBA: 145, 60, 255, 255).
- Register `new Speedmine()` using `Modules.get().add(...)` in the host addon.
- Adapt the THM member exclusion in Speedmine's auto-target selection:
  `THMSystem.get().ignoreThmMembers.get()` and `ThmMembers.isThmMember(player)`.
  Those THM-wide services and their network infrastructure are not included.
- Adapt `Speedmine.miningPacketBlocked(PacketType<?>)`, which consults
  `PaketLimiter`. That module is not included; it also depends on HighwayBuilderTHM.
  Use the host's packet limiter, or explicitly remove this optional integration.
- Remove or adapt the `Surround` import and callback in
  `ClientLevelBlockUpdateMixin`; keep the Speedmine callback. This is a shared
  upstream mixin, copied verbatim, and Surround is not included.
- Register PlayerAirMiningMixin, ClientLevelBlockUpdateMixin and all five
  included accessors in the host's client mixin configuration. Preserve package
  names or update both imports and mixin configuration consistently.
- InventoryManager subscribes itself to Meteor's event bus on first getInstance().
  Its slot tracking and accessors must remain available for silent tool swaps.
- DescribedOption is included; upstream custom dropdown tooltip rendering is not.
- SpeedmineTest uses JUnit Jupiter (upstream 5.13.4) and requires a configured
  Minecraft/Meteor development classpath and the completed host integrations.

## Verification

All copied files were checked byte-for-byte against the pinned source revision.
The extracted code was not compiled or tested in-game. No independent Gradle
build is supplied because the host integrations above remain unresolved.
