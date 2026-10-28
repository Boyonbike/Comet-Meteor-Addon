# Native Comet Boat Fly

User request: copy Quiettee's boat flight/phasing into Comet 26.1.2, give it Highway Fly style helper integration and a separate similar dashboard. Direction remains owned by Comet Angle Calculator. Dashboard and webhooks report enchanted golden apple count instead of elytra.

## Behaviour
- Native `boat-fly` module; no Quiettee addon required. Preserve Quiettee flight/phasing, corrections, speed fallback and passenger handling, excluding BoatShot combat coupling.
- User already sits in the driver seat. No placement/search/automatic boarding.
- Boat Fly manages AutoWalk (Simple/Forwards, disable-on-y-change off), AutoEat, AutoLobby and AutoSetHome through configurable integration settings. Restore borrowed module preferences on stop; independently enabled helpers remain enabled.
- Angle Calculator determines direction. Boat Fly does not introduce its own destination steering. Suspend Angle Calculator updates during boat lobby recovery so recovery owns portal approach; resume afterward.
- Reuse AutoLobby's mounted recovery and existing reconnect safeguards. Track native module ownership separately from external Quiettee travel; resume the correct controller. Pause sethome during recovery and known lobby spawns.
- Existing Highway Fly behaviour and dashboard stay intact. Prevent conflicting flight controllers from running together.

## Dashboard and webhooks
- Separate Boat Fly dashboard, default port 8735; reuse the existing read-only HTTP/SSE server and access controls, with independent assets and publishing lifecycle.
- Similar visual structure: status, boat speed, trip/active time, position, health/food, eGaps, Angle Calculator target, helper states, recovery, home schedule/history, settings and events. No elytra metrics.
- Count `ENCHANTED_GOLDEN_APPLE` item units across the main inventory, hotbar and offhand, excluding nested container contents. AutoEat's blacklist must allow eGaps while managed and restore on release.
- Boat Fly webhook settings and event feed are independent, with start/stop/recovery/home/status events using Boat Fly branding and eGap count. Reuse Discord URL validation and delivery. No actual webhook sends in tests.

## Implementation choices
- Port flight engine/helpers into a dedicated Comet boat package; add boat movement/network mixins with inactive fallthrough.
- Separate the trip/helper coordinator from the engine; retain attribution and GPL licence notices and source packaging.
- Use existing Meteor/Gradle/Java stack. Reuse dashboard transport rather than copying network security logic.
- Verify with meaningful inventory/marker/webhook/asset regression tests, full existing tests, and successful addon build. Live Minecraft/server validation remains a user-side check.
