# CrystalCombat Phase 2b real-client checklist

`CombatRuntime`, `ObservationIntake` and `CombatInventoryAdapter` are unit tested with scripted fakes
and a failure-after-any-packet sink. The items below need a real 1.21.11 client because they depend on
vanilla hook timing, Meteor's rotation queue or a live inventory. Nothing here is required for the
build; run it before Phase 2c enables automatic combat.

**Minecraft 1.21.11 port:** this edition targets Minecraft 1.21.11, Java 21 and Meteor Client
**1.21.11-82** (build 82). The packet hooks, CrystalAura suppression surface, metadata
accessors and build stamp were verified from the pinned artifacts by
[the port audit](../superpowers/audit/2026-11-01-combat-api-1.21.11.md). `motion-prediction` and
`hurt-sync` are unavailable on this version and must refuse activation (audit §6.2); treat that refusal
as expected behaviour.

## Prerequisites

- JDK 25 through the repository toolchain, `./gradlew` artifacts already cached.
- A development client (`./gradlew runClient` with the existing setup). Do not attach to a
  production server; use a local test world or a private test server.
- Meteor's Combat Recorder already traces packets; keep it enabled so a failed check can be replayed.

## Observation pipeline

1. **Network thread receipts.** Join a world and watch the intake counter in the debug HUD or a
   temporary log. Every `ClientboundSetHealthPacket`, `ClientboundSetEntityDataPacket`,
   `ClientboundExplodePacket`, `ClientboundPlayerPositionPacket`, `ClientboundRespawnPacket`,
   `ClientboundBlockChangedAckPacket` and `ClientboundEntityEventPacket` produces a receipt on the
   netty thread and exactly one observation only after the vanilla handler returns. Expected: no
   observation before application; no exception when a bundle displaces the handle (the client tick
   applies observations in receipt order).
2. **Bundle order.** Stand in a world with a crystal explosion. Expected: the explosion observation,
   the entity-data health/absorption observation and the block-changed ack appear in the same order as
   the packets in the client trace, and the health/absorption pair shares one applied revision.
3. **Split health pair.** Force a health change that sends health and absorption in two packets
   (equip an absorption source such as a golden apple while damaged). Expected: the budget suspends
   with `INCOHERENT_HEALTH` until both fields arrive from one applied bundle; no retirement happens
   from the absorption-only observation.
4. **SetHealth carries no absorption.** Respawn or join. Expected: `ClientboundSetHealthPacket` alone
   never fabricates absorption and never retires a hazard; self effective health only becomes coherent
   from the metadata pair (audit C19).
5. **Explosion identity.** Blow up a crystal we placed. Expected: the explosion observation resolves to
   the crystal entity by position; a second crystal within a block makes resolution ambiguous and the
   explosion does not associate. A stale explosion at the same position cannot arm a later placement
   (carry-over 13).
6. **Pre-placement health.** Take damage after reserving a placement but before the placement packet
   leaves. Expected: the earlier health snapshot cannot retire the new risk; only a coherent snapshot
   applied after the send revision can (carry-over 14).

## Dispatch and inventory

7. **Manual slot race.** Bind the module to a plan. While a reservation is held, scroll to another
   hotbar slot. Expected: dispatch fails with `MANUAL_SLOT_CHANGE` before any place/attack packet.
8. **Open screen.** Open the inventory between reservation and dispatch. Expected: `SCREEN_OPEN`, no
   packets, and the unsent reservation is released.
9. **Loan and weapon-attack ownership.** Trigger an `InventorySlotLoan` borrow (AutoTool/AutoWeapon) or
   defer a hit with `InventoryWeaponAttack` while a combat reservation is held. Expected:
   `INVENTORY_OWNED_ELSEWHERE` with no call to `InventorySlotLoan.prepare()`; verify the combat send
   path never appears in the loan's stack trace.
10. **Offhand untouched.** Put a totem in the offhand and reserve a placement. Expected: the offhand
    slot contents and selection are unchanged for the whole cycle, including after the supporting
    swing and slot restore (rule 6).
11. **Slot restore race.** Change the selected slot from another module or by hand between the switch
    packet and the restore. Expected: the adapter does not send a restore packet, the user's selection
    wins, and the causative packet is not rolled back.
12. **Failure after a packet.** Use a packet drop/simulated failure (e.g. briefly disconnect the client
    during a placement). Expected: if the placement was accepted but the swing or restore failed, the
    risk stays `SENT` and the trace marks only the supporting stage as failed; no compensating packets.
13. **Late dispatch.** Hold a reservation for more than one server tick before dispatch (pause the
    module's planner or freeze its tick). Expected: the packet reservation moves to the dispatch tick;
    if the rolling window has no room, the intent is blocked and released instead of under-counting.
14. **Acknowledgement.** With a real server, trigger an interaction with a block-changed prediction.
    Expected: the send trace records the ack sequence when a sequence is known. The ordinary send path
    keeps `ackSequence = -1` because it does not record a prediction sequence. The **Barrier**
    risk model is the exception: it sends its side-effect-free probe with the real vanilla prediction
    sequence, so the server's ack retires harmless reservations (see the tester build 3 items). A
    missing ack on an ordinary dispatch is expected here, not a bug to patch.
15. **Rotation queue.** Enable rotations and dispatch with a yaw/pitch outside the tolerance. Expected:
    one rotation request to Meteor Rotations from inside dispatch, no packet send that tick, then a
    successful dispatch on the next tick after the rotation lands. Expected: the request is never
    issued from a render callback (F3 frame profiler must not show a rotation enqueue).

## Lifecycle

16. **Death.** Die while a placement risk is sent. Expected: no send after death, unsent leases vanish,
    the sent risk stays counted as debt, and no retirement happens from death (only rule 5 clears self
    debt on respawn).
17. **Respawn.** Respawn. Expected: self debt retires through the respawn observation; friend debt and
    unresolved crystal hazards remain and are rescanned.
18. **Dimension change.** Travel through a portal. Expected: a new connection epoch starts, sent self
    debt is still counted on the new epoch, old-dimension hazards no longer arm, and no old-dimension
    lease survives into the new world.
19. **Connection close.** Disconnect and rejoin. Expected: risks, pending health pairs and leases from
    the old connection are gone; the new connection starts without inherited risk.
20. **Disable/config change.** Disable the module or change a setting mid-reservation. Expected: no
    packets, unsent work is dropped, sent debt keeps counting, and the ledger keeps observing passively
    (no cleanup packets, rule 8).
21. **Server stall.** Freeze the client for more than a second of server ticks. Expected: the budget
    suspends on server-tick stall and refuses sends until reconciliation; the scheduler and budget each
    tick once per server tick (not once per frame).
22. **Queue saturation.** Run the trace recorder and a busy crystal fight to saturate the receipt queue.
    Expected: intake overflow suspends spending before the next tick's sends, and reconciliation clears
    the suspension only after the queue is drained.

## CrystalAura suppression (Phase 2c)

These checks need the CrystalCombat module from Phase 2d to enable and disable it. Until then, a
development build can activate the gate from a test harness; the client behaviour is identical.

23. **Both fight over the same crystals.** Enable Meteor CrystalAura and CrystalCombat on a server with
    obsidian and crystals available. Let both run for 200 ticks (about ten seconds) while crystals are
    being placed and broken. Expected: CrystalCombat sends its own placement/break/attack packets, and
    Meteor CrystalAura contributes zero packets, zero rotations and zero hotbar swaps. Meteor's enabled
    flag, settings and bookkeeping (switch timer, break timer, placed bookkeeping) stay untouched, and
    re-opening Meteor's GUI shows CrystalAura still enabled with the same settings.
24. **Disable with callbacks queued.** With both modules enabled, break a crystal so Meteor has a
    rotation callback queued, then disable CrystalCombat in the same tick. Expected: the queued callback
    never sends an attack or placement after CrystalCombat is disabled; no delayed packet arrives a tick
    or two later. Re-enabling CrystalCombat must also not release any old callback.
25. **Ledger-protected crystal after disable.** Add a crystal to CrystalCombat's ledger protection (a
    friend's crystal or a protected hazard), then disable CrystalCombat and let Meteor CrystalAura keep
    attacking. Expected: Meteor never attacks or breaks the protected crystal while CrystalCombat is
    disabled, and CrystalCombat sends nothing itself. Removing the protection lets Meteor resume.
26. **Capability refusal.** Force a capability failure in a development build (skip one critical mixin or
    remove one guarded injection target). Expected: enabling CrystalCombat refuses with a visible reason
    naming the missing capability, the module does not activate, and Meteor CrystalAura keeps placing,
    breaking and rotating normally (its enabled flag may be set, the mixin must not suppress or cancel
    when CrystalCombat is refused).

## CrystalCombat module (Phase 2d)

These checks need a real client because they exercise Meteor's module lifecycle, the mixer, and real
connections; the unit tests cover the same transitions with fakes only.

27. **Registration and refusal.** Open Meteor's module list. Expected: `CrystalCombat` appears under
    `Comet` next to the combat modules, starts disabled, and enabling it in the main menu either refuses
    with `capability missing: ...` or enables and reports a wait reason. Meteor `CrystalAura` keeps
    working in every refusal case (its enabled flag, settings and bookkeeping are untouched).
28. **Enable in world.** Join a world with obsidian and crystals. Enable `CrystalCombat`. Expected: the
    gate suppresses Meteor `CrystalAura` immediately; the info string reads `no crystals`, `idle` or a
    suspension reason; no packet is sent from the render thread (F3 profiler shows no send in frames).
29. **Disable in world.** Disable `CrystalCombat` while a placement risk is sent. Expected: the gate is
    deactivated in the same tick, no cleanup packet is sent, the connection stays open, Meteor
    `CrystalAura` resumes its own behaviour, and the ledger still observes packets passively
    (no float/HUD cost on the render thread; `getInfoString` never queries the world).
30. **Ledger protection after disable.** Repeat checklist item 25 now that the module exists: a
    ledger-bound crystal stays protected from Meteor after disabling `CrystalCombat`, and protection ends
    only when the hazard retires or the ledger forgets it.
31. **Death and respawn.** Die with a sent placement and an unresolved crystal hazard. Expected: the
    action generation ends, the info string reports `suspended: death`, hazards and friend debt stay, and
    after respawn the wait reason clears only after the ledger reconcile; no send occurs before that
    reconcile. Repeat with the module disabled: hazards and debt remain, no packets are sent.
32. **Connection close and quick reconnect.** Disconnect while `CrystalCombat` is enabled, then rejoin.
    Expected: no packet from the old connection reaches the server, the session is closed, and the first
    tick after rejoin opens a fresh session; a stale old-connection ticket is refused. Repeat by leaving
    the world while the module is disabled and enabling it in the next world: the stale session is
    detected from the connection identity before any action.
33. **Settings guards.** Try to weaken safety below the conservative floor (min target damage < 4, max
    self damage > 6, remaining margin < 2, friend damage > 2). Expected: enabling refuses with
    `unsafe safety settings: ...`; changing mode or profile away from `DirectOnly`/`Conservative` refuses
    the same way; `anti-suicide` cannot be turned off. Changing a setting mid-combat ends in-flight
    actions (`onConfigChanged`) and sends nothing.
34. **Suspensions and lag pause.** Stall the client for more than a second of server ticks, then saturate
    the observation queue. Expected: the info string reports `suspended: queue overflow` (or the stall
    cause), the module sends nothing until reconciliation, and the suspension counter in a debug build
    increments once per episode, not once per tick.
35. **Metrics wiring.** In a development build with metrics exposed, fight one crystal target. Expected:
    `combat_tick_ms`, suspension and risk-debt samples update once per server tick; all Phase 4.7 table
    counters exist and stay zero until their phase instrumentation lands (no merged counters);
    `defence_actions{break_crystal|place_blocker}` is the Phase 4.6 counter and must increment only when a
    defensive action is actually dispatched.
36. **Passive spawn/removal observation while disabled.** Enable `CrystalCombat` once so its ledger exists,
    disable it, then place an end crystal near a previously sent placement and break it. Expected: the
    ledger binds the spawned crystal to the sent placement and keeps it protected from Meteor even though
    the module is disabled; `Meteor`'s own CrystalAura must not attack the protected crystal. Breaking the
    crystal records an authoritative removal and protection ends for it. No packet is sent by
    `CrystalCombat` while it is disabled, and re-enabling resumes the same ledger evidence.
37. **Passive receipt application while disabled.** Enable `CrystalCombat` once, send a placement, then
    disable the module. Let the sent crystal explode and observe the resulting health change without
    re-enabling. Expected: the sent risk still retires from the passively applied observations, the
    visible info string stays `disabled`, and no packet is sent. This also covers the always-subscribed
    observation host: spawn/removal events must keep feeding the ledger with the module off.
38. **Respawn while disabled.** Enable once so the ledger exists, disable, then die and respawn.
    Expected: the runtime hook runs the single shared reconcile path (ledger rescan against a snapshot
    taken after the generation change, budget un-suspended), the info string stays `disabled`, and no
    packet is sent. A respawn observed through the normal packet path must reconcile exactly once, not
    twice.

## P3.2 tester build 1

Run these on the tester jar built from this tree with Meteor Client **1.21.11-82**. Turn the
**Debug log** on for every item unless the item says otherwise, and keep the matching source zip with the
jar. The info string must show the build version and short commit (for example
`v0.27.0-tester.1 fb59152-dirty`).

39. **Open target.** Fight a player standing in the open with obsidian and crystals available. Expected:
    CrystalCombat places, breaks and attacks through the audited path; no packet comes from the render
    thread (F3 profiler shows no send in frames); the info string moves between `idle`, `no crystals`,
    `risk debt` and a suspension reason. No speculative crystal or attack IDs appear in the debug log.
40. **Holed target.** Fight a target standing in a one-block hole (feet covered by the hole, head exposed
    or covered). Expected: CrystalCombat either places a crystal that can actually damage the target or
    stays on `idle`/refuses; it must not spam placements into a covered face, and any explosion risk is
    counted before the placement. Record whether placements land at the target's feet.
41. **Moving target.** Fight a target strafing and jumping at 3–6 blocks. Expected: the planner refreshes
    position and rotation evidence per tick; with **Motion prediction** off (the default) stale positions
    are rejected, not extrapolated. The info string may show `idle` between opportunities but no crystal
    is placed at a position the target has already left by more than a block. With the tactic on this
    becomes the experimental path compared in item 53.
42. **Friend nearby.** Fight a target with a friend (or a second account) within explosion range.
    Expected: the friend-damage bound blocks placements that could hit the friend; the info string shows
    a refusal rather than a send. Verify the ledger protects the friend's crystals and that disabling
    CrystalCombat lets Meteor CrystalAura resume normal behaviour.
43. **Weakness / damage estimate.** Apply Weakness (or a similar damage-reduction effect) to yourself and
    fight a target. Expected: the estimated target damage drops; if it falls below the conservative
    minimum (4) CrystalCombat does not spend risk and the info string shows the refusal. Remove the
    effect and confirm fights resume.
44. **Eating.** Eat a golden apple or chorus fruit while a fight is possible. Expected: the engine pauses
    (`paused: eating`), no placement/break/attack packet is sent during the pause, and combat resumes
    afterwards. Also confirm the pause does not freeze passive observation: an explosion during the meal
    still retires risk.
45. **Disable mid-fight.** With CrystalCombat enabled and a sent placement, disable the module from the
    Meteor GUI while a crystal is live. Expected: the gate deactivates in the same tick, no cleanup
    packet is sent, unsent work is dropped, the sent risk keeps counting as debt, the connection stays
    open, and Meteor CrystalAura resumes its own behaviour. Re-enable and confirm the same ledger resumes.
46. **Death and respawn.** Die while CrystalCombat is enabled and a placement risk is sent. Expected: the
    debug log records a `self-death` incident (module enabled), the action generation ends, no packet is
    sent after death, and sent self debt stays counted. After respawn the ledger reconcile clears the
    suspension once; the info string must not clear `suspended: death` before that reconcile. Repeat with
    the module disabled: the death is not recorded as an incident and no packet is sent.
47. **Low TPS / high ping.** Fight through a server tick stall or 300+ ms ping. Expected: the runtime
    suspends on a server stall, refuses sends until reconciliation, and does not use timeouts to retire
    risk. The debug log records the suspension change with `SERVER_STALL` if that cause is raised.
48. **Debug log ring and incidents.** With the debug log on, let a fight run for more than five minutes
    (or start it, wait, then check). Expected: the buffer holds at most the most recent five minutes;
    incident markers appear for self death, above-bound self damage, debt older than 30 seconds,
    suspension changes and failed send-path invariants; each automatic marker starts with `incident:` and
    carries its values. Nothing is written to `comet/` while the debug log runs.
49. **Export preview.** Run `.crystalcombat export`. Expected: chat lists every archive file with its size
    (`manifest.json`, `build.txt`, `settings.json`, `debug-log.jsonl`, `THIRD_PARTY_NOTICES.md`), the
    debug line count/dropped count and the privacy mode, then states that nothing has been written. Check
    the game directory: no `comet/exports/` zip is created by the preview. The preview must not reveal raw
    other-player names, UUIDs, chat or server addresses.
50. **Export confirm and privacy.** Run `.crystalcombat export confirm`. Expected: exactly one timestamped
    zip appears under `comet/exports/` and chat reports its path and file count. Unzip it: `build.txt`
    matches the jar's version and commit (including `-dirty` if the tree was dirty), `manifest.json`
    says `identities: hashed`, and `debug-log.jsonl` contains `player-` pseudonyms instead of raw names,
    UUIDs, chat or server IPs. Run the export twice and confirm the pseudonyms differ between zips
    (per-export salt) but are stable within one zip. Repeat with **Export include identities** on: real
    names/UUIDs may appear but chat and addresses must still be absent.
51. **Manual incident marker.** With the debug log on, press the **Incident marker key** during a fight.
    Expected: an `incident: manual something went wrong here` line appears in the next export's
    `debug-log.jsonl`; no packet is sent and gameplay does not change. Press it with the debug log off:
    chat warns that the debug log is not running and nothing is written.
52. **Offhand untouched.** Place a totem in the offhand, then place, break and attack with CrystalCombat.
    Expected: the offhand item and selection are unchanged for every cycle; no packet touches the offhand
    slot. This is the P3.2 regression check for the audited inventory rules.
53. **Motion prediction on/off (Phase 4.2, experimental).** Fight the same moving target twice, once with
    **Motion prediction** off (default) and once on, in the same gear, position and server conditions.
    Keep the debug log on; the header should start with `debug-log-start motion-prediction=off|on`
    (plus its ticks) reported from the active module settings. Compare: damage dealt to the target and
    damage taken; placements and attacks attempted (counts, not intentions); wait reasons and their
    frequencies; FPS and combat tick timing; and any outcome that looks surprisingly safe or unsafe —
    for example a cell that should have been refused, damage to a friend, or a full-health hit that was
    not attempted. Expected on: the target's guaranteed lower bound is `0.0` whenever a strong prediction
    replaces the estimate (the model's hull cannot prove the least exposed interior point), while expected
    damage is the mean over the predicted positions, so an action that off refused may become available
    when the evidence shows the target moving into exposure. Self and friend upper bounds may only rise
    (whole-hull worst point at full exposure, including vertical escape) and must never be lower than off
    for the same observation. Unknown or low-confidence motion must behave exactly like off, and low
    confidence may only lower ranking. Toggling the setting mid-session ends in-flight actions and sends
    nothing; the new value applies immediately to the next action generation, so compare the two runs
    as separate launches. Record pass/fail per comparison, the world/gear setup, the build stamp and both
    debug-log exports.

54. **Hurt-aware timing on/off (Phase 4.3, experimental).** This build has no production raw-hit feed, so
    **Hurt sync** must behave exactly like off in a normal fight: same placements, attacks, wait reasons and
    debug header `debug-log-start ... hurt-sync=off|on`. Turn it on and confirm the header changes while the
    action counts do not; if any placement or attack differs with the same world state, capture the debug
    log and stop using the setting. The pure tactic is covered by unit tests with a synthetic window; do not
    treat a synthetic or debug-fed window as evidence that the production feed exists. Expected on: never a
    lower self/friend bound, never a delayed lethal opportunity, unknown windows keep the exact Phase 3
    estimate with a zero guaranteed lower bound, and any future feed must not be inferred from health or
    absorption drops. Record the build stamp, both exports and whether the header matched the setting.

55. **Surround-gap on/off (Phase 4.5, experimental).** Find a target standing in a one-column hole with an
    obsidian or bedrock block under the single open surround column. With **Surround gap** off, expect the
    normal placement choice; with it on, expect a crystal placement in the open column (or the exact same
    actions when no legal base sits under the gap). The debug header must include
    `debug-log-start ... surround-gap=off|on`. Neither setting may mine a surround block or take the mining
    slot. With **Surround gap** on, neither it nor **Support** may place an obsidian support block in the
    gap column itself (a support block *under* the gap column that enables a crystal in the gap is the
    intended chain); the damage, reach, visibility and friend/self limits must be unchanged. If the on/off
    action differs without a legal base under the gap cell, a support block appears in the gap column, or
    any mining packet appears, capture the export and stop using the setting.

56. **Support placement on/off (Phase 4.4, experimental).** Find a target where no obsidian/bedrock base is
    already in place but a support block can be placed within reach. With **Support** off, expect the normal
    direct-base choice (often no placement); with it on, expect at most one obsidian support block followed
    by a crystal on that support, counted as two actions. The debug header must include
    `debug-log-start ... support=off|on`. On must never relax the self/friend, minimum-damage, reach,
    visibility, hazard or entity-volume checks, and must never place more than one support for the same
    opportunity. If a second support block appears, or any bound is relaxed, capture the export and stop.

57. **Dangerous-crystal break on/off (Phase 4.6, experimental).** Have a second player place an end
    crystal within your attack range while your effective health is low enough that its whole-hull worst
    detonation would breach your remaining margin (the planner prices the worst mitigation envelope, not
    your current armour). With **Dangerous crystal break** off (default), expect normal offence only. With
    it on, expect a defensive break to be chosen before offence when the estimated arrival is within 4
    ticks, and the debug header to include `debug-log-start ... dangerous-crystal-break=on`. Expected: the break
    goes through the single dispatch path (rotation, slot and reach checks; offhand untouched), reserves
    its own risk, and increments `defence_actions{break_crystal}`. A crystal this module placed and still
    owns (or a ledger-protected cell) must never be broken: there is no trap-invalidation API, so it is
    skipped. Self and per-friend ceilings still apply; if the break would exceed either, expect no send.
    Turn the setting off mid-fight: in-flight actions end and no defensive break is sent afterwards. If a
    defensive break appears with the setting off, an own crystal is attacked, or a self/friend ceiling is
    exceeded, capture the debug export and stop using the setting.

58. **Anti-piston blocker on/off (Phase 4.6, experimental).** Build a piston aimed at your feet from each
    of the four horizontal directions, then repeat at head height, once with an end crystal in the piston's
    front cell and once with an empty crystal-ready front cell (the front cell is the cell the piston head
    extends into). With **Anti-piston** off, expect normal offence. With it on, the debug header must
    include `debug-log-start ... anti-piston=on`, and the expected response is: break the front crystal via
    the defensive break/risk path when safe; otherwise place exactly one obsidian `PLACE_BLOCKER` in the
    front cell (sturdy face below, entity-free, visible, in reach, real obsidian item) and increment
    `defence_actions{place_blocker}`. Expected: one placement per opportunity, no attack of an own/protected
    crystal, no block placed into an occupied crystal cell, no offhand change, and **no mining packet ever**
    (power-source mining is out of scope because Speedmine has no owner-scoped contract). If the front
    crystal's break is unsafe (self/friend ceiling), expect the opportunity to be dropped rather than a
    blocker placed into the crystal. Disable mid-defence: no further defensive send, and the ordinary
    offence path resumes. If any mining packet appears, the offhand changes, or a blocker lands outside the
    front cell, capture the export and stop using the setting.

## P5.2 tester build 2 (beta PistonOnly)

These scenarios were written for build 2 and still apply to build 3. Run them on the current
`-tester.3` jar built with Meteor Client **1.21.11-82**, with the **Debug log** on unless
an item says otherwise. Keep the matching source zip with the jar. The info string must show
`v0.27.0-tester.3 <commit>` (the commit may end in `-dirty`). **Manual cleanup is the default**: the
trap never mines or breaks its own supports, piston, power block or crystals. Remove debris yourself
only when safe, and expect the trap slot to stay occupied until reconciled.

### Per-layout matrix (direction × height × power)

Enable `Mode = PistonOnly`, **piston-trap** on. Run one row at a time with the other fifteen layout
toggles off (or note which were on), and record the chosen layout plus the computed final position
from the debug log. Every row is **beta and not yet server-confirmed**; expect the measured 1.02-block
push, two extension ticks, block-by-block confirmation, one attack, and no attack before the piston
extension events.

| # | Direction | Height | Power | Expected scenario id | Result |
| --- | --- | --- | --- | --- | --- |
| 59.1 | east | feet | torch | `piston_east_feet_torch` | |
| 59.2 | east | feet | redstone | `piston_east_feet_redstone` | |
| 59.3 | north | feet | torch | `piston_north_feet_torch` | |
| 59.4 | north | feet | redstone | `piston_north_feet_redstone` | |
| 59.5 | south | feet | torch | `piston_south_feet_torch` | |
| 59.6 | south | feet | redstone | `piston_south_feet_redstone` | |
| 59.7 | west | feet | torch | `piston_west_feet_torch` | |
| 59.8 | west | feet | redstone | `piston_west_feet_redstone` | |
| 59.9 | east | eye | torch | `piston_east_eye_torch` | |
| 59.10 | east | eye | redstone | `piston_east_eye_redstone` | |
| 59.11 | north | eye | torch | `piston_north_eye_torch` | |
| 59.12 | north | eye | redstone | `piston_north_eye_redstone` | |
| 59.13 | south | eye | torch | `piston_south_eye_torch` | |
| 59.14 | south | eye | redstone | `piston_south_eye_redstone` | |
| 59.15 | west | eye | torch | `piston_west_eye_torch` | |
| 59.16 | west | eye | redstone | `piston_west_eye_redstone` | |

### Scenarios

59. **Piston trap base run (PistonOnly).** Enable `Mode = PistonOnly` with **piston-trap** on and all
    layouts on. Against a stationary player in a sealed hole or full surround, verify: one trap at a
    time; support → piston → crystal → power in dependency order; the piston faces the target; power
    only lands after a unique crystal spawn binds; no attack until the authoritative piston extension
    events complete; exactly one swing; no packet after disable, death, disconnect or a mode switch;
    the ledger and risk stay occupied. Wrong facing, early power, ambiguous/foreign spawn and deadline
    expiry must produce no further packets and keep manual cleanup visible. If a trap packet appears
    with the mode or switch off, or an attack targets an unbound entity, capture the export and stop.

60. **Target leaves the hole.** Start a trap on a stationary target in a hole, then make the target
    leave or move out of the attack envelope before POWER. Expected: the trap aborts with no further
    send, the reason for the abort is in the debug log, the risk and trap slot stay occupied, and no
    attack is sent. Repeat with the target leaving after POWER: expected is either a safe finish only
    if the attack remains safe, otherwise manual cleanup with no speculative sends.

61. **Disable mid-trap.** Start a trap, wait for at least one sent block, then disable CrystalCombat (or
    switch Mode back to DirectOnly) mid-trap. Expected: an incident marker records manual cleanup, no
    cleanup/mining packet is sent, the cycle stops immediately, the sent risk keeps counting as debt,
    and re-enabling only acts after reconciliation. Switching back to DirectOnly must send no stale
    trap packet in the same tick or later. After manually removing the debris, `.crystalcombat cleanup
    confirm` must release the trap slot, send no packet, keep the sent risk debt, and let a new trap start.

62. **Death mid-trap.** Die while a trap is mid-sequence. Expected: no packet after death, the trap
    generation ends, the debug log records manual cleanup (not a false success), sent risk stays
    counted, and the next life does not resume the old trap. Recover with `.crystalcombat cleanup
    confirm` after clearing the debris; the info string must show `manual cleanup required` until then.

63. **Layout filter.** Turn off one layout and confirm the planner never chooses it (the chosen layout
    in the debug log differs). Turn off all sixteen and confirm PistonOnly stays inert: no support,
    piston, crystal, power or attack packet, and no silent fallback to direct mode. The info string
    must show `all piston layouts disabled`, not `idle` pretending the mode is healthy. Turn one layout
    back on after an aborted trap only after `.crystalcombat cleanup confirm`.

64. **C21 client-position handling.** With the debug log on, run a trap to WAIT_PUSH and check that the
    client's crystal position is recorded as stale/unobserved (C21 suppresses sync) **without** a
    `position-disagreement` incident. Only a genuine client position update beyond 0.05 blocks should
    produce that incident.

65. **Block confirmation.** Force a wrong block, wrong facing or a missing confirmation during SUPPORTS,
    PISTON or POWER. Expected: the stage rejects, the debug log records the block-confirmation result
    and the reason, an incident records the wrong block/timeout, no further stage packet is sent, and
    manual cleanup is required.

66. **Incident set.** With the debug log on, confirm the P5.2 markers appear when their condition
    occurs: trap aborted after POWER, manual cleanup entered, computed/client disagreement with a real
    update, attack sent while no crystal is observed, block-confirmation timeout/wrong block. None of
    these markers may send or change a decision.

67. **Debug-log behaviour.** Run a complete trap with the debug log on. Expected lines include: the
    chosen layout and plan id, computed final position, every stage transition, the client-position
    cross-check, every block confirmation, and the reason for every abort. Run the same trap with the
    log off: the gameplay outcome must be identical, with no incidents and no extra sends.

68. **DirectOnly regression.** Switch back to DirectOnly and repeat checklist items 39–51. The trap must
    never run in DirectOnly, direct placement still goes through the audited dispatch path, and the
    mode switch itself sends nothing.

69. **Real-client wiring smoke test.** The full client stack (module settings → controller → trap with
    the production `PistonTrapWorld` and `DirectWorldReader`) has no automated client-level test. On a
    private test world, confirm the info string, the beta mode switch, the sixteen layout settings and
    the debug log lines appear as described; if the stack fails to start, export the debug log and stop
    (do not work around it with a hidden setting).

## P6.4 stage 1 (serialised Hybrid)

Hybrid is offline-tested only in this build. These checks compare it with the two modes it replaces;
run them on a private test world with obsidian and crystals available.

70. **Mode switch.** With the module enabled in DirectOnly, switch Mode to Hybrid. Expected: the info
    string names Hybrid (or a Hybrid wait reason), no packet is sent by the switch, and the debug log
    header records `mode=HYBRID hysteresis=on`. Switch back to DirectOnly and confirm the direct engine
    resumes without a stale packet in the same tick.

71. **No trap, direct plan wins.** Stand in a normal crystal spot where a direct placement is available
    and no trap can start (no stationary holed target). Enable Hybrid. Expected: the direct placement
    and break packets match DirectOnly tick for tick; no piston, support or power packet appears, and
    `choice_winners` grows in the `utility` bucket.

72. **Trap owns the field.** Start a trap in Hybrid (stationary holed target, piston materials). Expected:
    while the trap is active, no direct place/break/blocker packet is sent, `trap_blocked_direct` grows,
    the trap's own sends match PistonOnly exactly, and the info string shows the trap phase. When the
    trap aborts or finishes, direct plans resume on a later tick only after the trap slot is released.

73. **Defence keeps evaluating during the trap.** While a trap is active, place a crystal the module
    would break defensively (dangerous-crystal-break or anti-piston on). Expected: a defensive break or
    blocker is sent, `defence_during_trap` grows, and the trap's sends are not duplicated or re-ordered.
    A defensive failure must not unblock a direct plan that failed a hard filter.

74. **No thrash.** With two opportunities scoring close together, watch the debug log for at least 80
    ticks in Hybrid. Expected: the plan does not alternate; the log shows a hold until one plan leads by
    20% for three consecutive server ticks (or safety invalidation switches immediately). Counters:
    `hysteresis_holds` grows, `hysteresis_switches` stays low.

75. **Cleanup visibility.** With `auto-cleanup` on, get the trap to its cleanup stage while a direct
    plan is waiting, then watch Hybrid for 20 server ticks with a free slot. Expected: once cleanup has
    waited its starvation window the info string shows `cleanup due: waiting for the trap's cleanup
    slot`, no direct send takes that slot instead, `cleanup_waits` and `cleanup_starved` grow, and the
    trap's break goes through the packet budget. With `auto-cleanup` off, manual cleanup remains the
    visible state (`manual cleanup required: remove ...`) and nothing mines.

76. **Hybrid regression.** Repeat checklist items 39–51 and 55–69 in Hybrid. The safety thresholds,
    pauses, ledger protection, manual cleanup, offhand rule and dispatch invariants must behave exactly
    as in DirectOnly and PistonOnly.

## P6.2 footprint-scoped concurrency (experimental, off by default)

Run these in **Hybrid** with the trap committed on a stationary target. `footprint-concurrency` is in
**Experimental** and defaults off; it is not promoted and must not be presented as a validated preset. Keep
**Debug log** on. The settings are otherwise identical between the two runs, so the only difference is
the new toggle.

**Comparison record.** Fill one row per real encounter, and keep the export with it:

| Field | Serialised (`footprint-concurrency` off) | Footprint-scoped on |
| --- | --- | --- |
| Direct sends during a committed trap (place / attack) | 0 expected | Only with a positive `FootprintConflict` verdict |
| Trap sends and stage order | Unchanged | Unchanged |
| Defence evaluated during the trap | Yes | Yes |
| `concurrency_setting{on|off}` | `off` grows | `on` grows |
| `concurrency_admitted{compatible}` | 0 | Grows only on admitted direct work |
| `concurrency_serialised{row}` | Grows under `setting` | Grows under the conflict row (or `setting` when off) |
| Double slot/rotation owner or incident | None | None |
| Sent risk and manual cleanup | Unchanged | Unchanged |

77. **Serialised baseline.** With the setting **off**, commit a trap and present an unrelated direct
    opportunity (a direct plan whose explosion search and target-motion bound both miss the trap's
    footprint and which does not need the trap's slot or rotation in the same tick). Expected: the
    direct plan is rejected with `COMMITTED`, no direct place/attack packet is sent while the trap owns
    the field, `concurrency_serialised{setting}` grows and `concurrency_admitted` stays zero. This is
    the shipped behaviour.

78. **Admitted unrelated direct action.** Turn the setting **on** and repeat item 77 on a fresh trap.
    Expected: the same direct opportunity is admitted and goes through the one runtime dispatch path
    (rotation, slot, reach, risk); allow at most one direct action per tick; `concurrency_admitted{compatible}`
    grows; the trap's own sends and stage order are unchanged; `defence_during_trap` still grows when a
    defensive opportunity exists. If the direct plan needs the slot/rotation the trap may dispatch that
    tick, expect it to wait (serialise), not to be force-sent.

79. **Conflict cases (setting on).** Repeat with each of: the direct explosion reaching the trap's
    piston or power cell; the direct explosion inside the audited 12-block search of the trap's bound
    crystal; a direct explosion 8–12 blocks below the target that can push it vertically out of the
    hole; and a direct plan that needs the same slot/rotation as the trap's next stage. Expected: no
    direct send for that tick, `concurrency_serialised` grows under `STRUCTURAL`, `ENTITY`,
    `TARGET_MOTION` or `RESOURCES` respectively. For the crystal case, the audited vanilla hit removes
    the trap crystal **without** a second explosion: expect the trap to abort or retain manual cleanup,
    `attack_before_power`-style sends to stay absent, the trap risk to remain as debt until evidence,
    and no fabricated damage or cleanup packet. For the vertical case, the trap must not be treated as
    safe just because the explosion is more than 6 blocks away.

80. **Sentinel with no ranking view.** With the setting on, arrange an active trap whose ranking view is
    empty/unavailable (the trap is between stages, or its plan view cannot be priced that tick). Expected:
    the direct plan is still blocked (`COMMITTED`, serialised bucket `STRUCTURAL` or `setting`), never
    admitted through a null footprint, and the debug log shows the trap-view-unavailable phase. Disable
    the trap: the sentinel must clear on the next Hybrid tick and ordinary DirectOnly/Hybrid behaviour
    resumes.

If any direct send appears during a committed trap with the setting off, or a conflicting direct send
appears with it on, capture the debug export and stop using the setting.

## Tester build 3 additions

Run these on the current `-tester.3` jar after the baseline sections. Keep **Debug log** on. These are
numbered in the same style as the rest of the checklist; work through them in order.

81. **Risk model selection.** Switch `risk-model` between Strict, Barrier and Meteor-style. Expected:
    the info string changes to `[risk: strict]`, `[risk: barrier]` or `[risk: meteor-style]`, the
    debug-log header records `risk-model=<key>`, outstanding debt is kept when the model changes, and
    only Barrier sends a probe packet.
82. **Strict retirement.** Place and break a crystal, take no damage, and watch the debt. Expected: the
    explosion or removal alone does not retire it; a causally later coherent health update does. If no
    update arrives, the debt stays visible and the aura may wait on `risk debt`.
83. **Barrier on local vanilla.** On a local vanilla server, use Barrier for a trap or direct crystal.
    Expected: one side-effect-free `ABORT_DESTROY_BLOCK` probe is sent after our explosion, no block
    breaks and no inventory or offhand change occurs, and harmless reservations retire after the ack
    plus one tick. Record what the debug log shows, and a packet log if you have one. Do not run this
    item on a proxy or anti-cheat server.
84. **Meteor-style retirement.** Use Meteor-style for a crystal. Expected: the reservation retires when
    the explosion or removal is observed, while a crystal sent but not yet exploded still counts as
    debt. This is the weakest model; record any damage taken before the next health update.
85. **Debt and suspension display.** Toggle `show-risk-debt`. Expected: on, the top-left overlay shows
    `risk debt: N outstanding (bound X)` and any `suspended: <cause>`, and updates after a
    health/explosion event; off, it draws nothing. It is read-only and clears on leaving the world.
86. **Auto-cleanup.** With `auto-cleanup` off, confirm the trap leaves debris and the manual cleanup
    text lists the blocks. Turn it on for a trap with power and piston debris. Expected: it breaks only
    this trap's power, piston and supports, power before piston before supports, using a hotbar tool,
    and stops on disable, death, respawn, a mode switch or a connection change. Obsidian supports will
    usually exceed the
    40-tick deadline; manual cleanup of the remainder is the expected outcome, not a failure.
87. **Settings migration.** Load the build once with a saved tester.2 config. Expected: values from the
    old General and Ranges groups appear in their new General, Safety, Direct, Timing, Piston, Defence
    and Experimental groups, `piston-layout-*` values are kept, and Pauses keeps its existing values.
    Unknown settings are preserved in the migrated tag but Meteor drops unloaded data on the next save,
    so do not rely on unknown keys surviving later saves. The profile stays Conservative unless you
    selected Custom.
