# Boat automation compatibility implementation plan

**Goal:** Make mounted Quiettee BoatPhase travel work with Meteor AutoWalk/AutoEat and Comet AutoLobby/AutoSetHome on Minecraft 26.1.2.

**Architecture:** BoatPhase consumes logical movement keys. AutoLobby records boat-trip identity and heading alongside its existing recovery markers and uses an optional public Quiettee bridge to hold or slow boat travel during recovery. AutoSetHome shares the recovery gate.

**Scope:** User supplies the mounted boat. No boat search or automatic boarding. Existing unrelated working changes remain intact.

- [x] Add regression coverage for durable boat heading, Highway Fly marker compatibility, replacement and invalid identity.
- [x] Read logical steering and vertical controls in BoatPhase; preserve item use for AutoEat.
- [x] Track mounted trips, suspend AutoWalk for recovery, steer through portals, then resume the correct controller and heading.
- [x] Pause AutoSetHome during boat recovery and at known lobby spawns.
- [x] Document the module configuration and build both addon jars.
- [x] Run Comet's full tests and Quiettee's checks. Review recovery handoffs and correct held input after dismounting during portal exit.

**Validation:** Both Gradle builds pass; all 1,439 Comet tests pass; Quiettee pure-Java regression checks pass. Live server transfer and eating while flying need an in-game check.
