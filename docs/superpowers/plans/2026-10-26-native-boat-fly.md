# Native Boat Fly implementation plan

**Goal:** Native boat flight/phasing with managed travel helpers, Angle Calculator direction, boat dashboard and eGap reports; restore standalone AutoLobby join recovery.

**Architecture:** A ported BoatFlyEngine provides movement and correction hooks; BoatFly owns helper leases, timing and reports. AutoLobby retains trip controller identity and uses independent join recovery only when appropriate. Dashboard HTTP transport is shared with separate assets and port.

- [x] Port Quiettee flight engine/helpers and mixin boundaries with GPL attribution; remove BoatShot coupling.
- [x] Validate pure motion, travel/fallback, remount/hash and inherited Orbit handler behaviour.
- [x] Add native controller registration and managed AutoWalk/AutoEat preferences, independent helper ownership and profile restoration.
- [x] Persist native/external recovery ownership and preserve Angle Calculator direction, suspended during recovery.
- [x] Add standalone AutoLobby recovery on join without a saved flight; protect native current-dimension trips and complete standalone recovery independently of resume-flight.
- [x] Add independent boat dashboard on 8735 using existing auth/SSE transport, exact eGap count and safe DOM updates.
- [x] Add boat webhook branding with exact eGap count and existing validated Discord delivery/mentions.
- [x] Review handoffs/profile boundaries; add regression for guard ordering before releasing shared helper ownership.
- [x] Build and run the full regression suite plus frontend runtime tests. Live Minecraft checks remain outside automated validation.
