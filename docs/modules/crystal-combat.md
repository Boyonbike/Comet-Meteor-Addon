# CrystalCombat

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **crystal-combat**.

**CrystalCombat** is the audited crystal-aura / piston-crystal combat module for Meteor 26.1.2. It is
**DirectOnly by default**. The piston trap is an opt-in beta in the `-tester.3` build and is not
server-confirmed. This is beta software: test on worlds where losing gear is acceptable.

## Versions

This module ships on three Minecraft branches. The settings, defaults and send path are the same
unless the table says otherwise.

| Minecraft | Java | Meteor build | Status | Unavailable on this version |
| --- | --- | --- | --- | --- |
| 26.2 | 25 | `26.2-20261009.074120-35` | reference implementation | — |
| 26.1.2 | 25 | `26.1.2-20260810.135545-42` | ported; tester build 3 | `motion-prediction`, `hurt-sync` |
| 1.21.11 | 21 | `1.21.11-82` | not ported | — |

On **Minecraft 26.1.2** the `motion-prediction` and `hurt-sync` settings remain visible but are
**not available on 26.1.2**: the port audit records the stronger in-window hit's knockback and motion
behaviour as unconfirmed on this branch (audit §6.2), so enabling the module with either setting on is
refused with a visible reason. Every other setting and behaviour described here is available.

The module sends only through one runtime dispatch path. Planners never send, rotations are requested
only from dispatch, and the offhand item is never touched. Meteor's own CrystalAura is suppressed while
CrystalCombat is enabled; a missing safety mixin refuses activation with a visible reason rather than
silently running without the guard.

## Modes

- **DirectOnly** — the conservative direct break/place engine.
- **PistonOnly** — the beta single-push trap. It requires the `piston-trap` setting and at least one
  beta layout enabled. The direct engine does not run in this mode.
- **Hybrid** — serialised hybrid. Direct plans and the piston trap are ranked against each other, but
  at most one of them owns the field: a committed trap blocks every direct plan until the trap finishes
  or aborts. Defence evaluation does not stop during a trap. Serialised Hybrid is the shipped stage; an
  experimental [footprint-scoped concurrency](#footprint-scoped-concurrency-phase-64-stage-2-experimental)
  path exists behind `footprint-concurrency`, which is off by default and not promoted.

A live mode or safety change aborts unsent work and keeps hazards and already-sent risk. The old trap
sequence becomes `MANUAL_CLEANUP` and cannot resume.

## Safety

Every opportunity is priced before reservation. The summed outstanding risk bound must leave the
configured margin; self and per-friend upper bounds use the weakest mitigation envelope, never the
current, stronger one. A timeout only changes retry/backoff: it never retires risk and never clears a
hazard. Sent risk retires only on the safety model's evidence. The anti-suicide setting is locked on.

The default safety envelope is minimum target damage 4, maximum self damage 6, remaining margin 2 and
maximum friend damage 2. Stricter values are accepted; looser values are refused.

### Risk models

`risk-model` (Safety group) selects how sent risk is retired. `Strict` is the default in every profile
and is the only model that adds no assumption: a reservation retires only by rule 1 (an observed
explosion plus a causally later coherent effective-health snapshot), self respawn or connection close. A
zero-damage explosion therefore stays as debt under `Strict` even though the crystal is gone.

`Barrier` is `Strict` plus one probe. After an applied explosion whose reservation is still `SENT` and
has seen no coherent health snapshot for a bound entity at or after the explosion, the runtime sends
exactly one `ABORT_DESTROY_BLOCK` at the first air block above the player's eye within four blocks
through the single dispatch path. The packet carries a real vanilla prediction sequence from
`BlockStatePredictionHandler`, so the server treats it as a normal block action and acknowledges that
sequence; the abort branch only clears the server's destroy state and sends a `-1` progress overlay, so
the probe changes no block, consumes no inventory item, never touches the offhand and sends no other
packet. If those four blocks above the eye contain no air, for example in a confined, fully underwater
or low-ceiling position, the probe is refused and the debt stays. While the runtime is breaking a block
it started, the probe is delayed, because the abort would cancel that in-progress destroy and the
server would then warn about a destroy-block-pos mismatch; the probe window keeps running during the
delay. The server may also log that harmless "Mismatch in destroy block pos" warning for a normal
probe, because the abort races the server's own destroy tracking even when no cleanup break is running.
If a cleanup destroy ever leaks past our tracking, the delay guard suppresses probes until the risk is
abandoned with its debt kept. The ack alone is not enough: after the ack, one further server tick retires
only reservations whose explosion predates the probe and that saw no coherent health change. A lost
probe, or a lost, late, repeated or mismatched ack, leaves the debt in place. At most one probe is in
flight at a time, and a sent probe is pruned after 40 ticks. A reservation left probe-eligible for 40
ticks is abandoned with its debt and is not probed again. This is not one probe per reservation: a
reservation that becomes eligible after an earlier probe has gone out can be covered by a later one.
The probe consumes one packet from the shared budget, is never planned, and is sent only while the
module is enabled, the session is open and
the model is `Barrier`; `Strict` and `Meteor-style` never arm it. Switching away from `Barrier` drops
only the in-flight probe evidence, never sent risk. Confirmation is local vanilla only: on a laggy,
proxied or modified server the ack may not order the health sync, so use `Strict` there.

`Meteor-style` follows Meteor and Future: an observed explosion retires that reservation immediately,
and an observed removal or tracking removal also retires it. Crystals that were sent but never observed
exploding still count against the next placement, so three crystals in flight before a health update are
summed and a placement that would cross the margin is blocked. It is the fastest model and the weakest
when several hits land before health updates.

The module info string shows the active model (`[risk: strict]`), the debug log header carries
`risk-model=<key>`, and each accepted dispatch line records the outstanding total. Model changes are
logged once. `Barrier` and `Meteor-style` retirements are logged once each; a `Strict` rule-1
retirement is not separately logged, because it is inferred from the same health snapshot that the
rule already applies.

## Settings

The Phase 8 settings are grouped for the Meteor screen. `show-risk-debt` (Render group, default on)
draws outstanding risk debt and every active suspension cause in the top-left corner. It is fed by an
immutable snapshot published on the client tick; the render callback never queries the budget, ledger or
world and never advances a sequence. It shows nothing while the module is disabled, before the stack
exists, when there is neither debt nor a suspension, or after leaving the world:
`CrystalCombatController.onLeft()` clears the snapshot (including the module's `onGameLeft` path), so a
closed connection cannot leave stale debt or suspension on screen until the next enabled tick.

- **General** — mode, profile and target range.
- **Safety** — risk model, min target damage, max self damage, remaining-health margin, max friend damage
  and locked anti-suicide.
- **Direct** — place, attack and walls ranges, own-only, support and surround-gap.
- **Timing** — motion prediction and hurt-sync.
- **Piston** — piston-trap, beta auto-cleanup and the sixteen beta layout toggles.
- **Defence** — dangerous-crystal break and anti-piston.
- **Pauses** — eating/drinking, mining, screens, Baritone, health floor and lag.
- **Experimental** — footprint-scoped concurrency.
- **Render** — the read-only `show-risk-debt` overlay.
- **Diagnostics** — opt-in debug log, identity export and incident marker (documented deviation from the
  Phase 8 table).
- **Inventory** — deliberately absent. Main-inventory lending is deferred behind its gate, so this build
  adds no lending, switch-delay or restore-slot setting; hotbar-only switching is unchanged.

`Profile` offers **Conservative** and **Custom**. Conservative re-applies the four audited defaults when
selected; Custom keeps the player's values. Both pass through `CombatProfiles.productionSafety`, so
neither can move a floor. There is no player-facing "what changed" view: the switch only updates the
settings and runs the shared reconfigure path. Selecting Conservative sets four settings, each with its
own `onChanged -> configChanged()` handler, so one user action can run that path up to five times; it is
chatty but safe (no sends, no hazard or sent-risk change, and every intermediate value is legal). The
plan's third profile is not implemented and is not offered. Old settings files are migrated from the
pre-Stage-5 groups (General/Ranges) by `CrystalCombat.fromTag` before Meteor loads them, so saved keys
keep their values; unknown groups and settings survive only in the migrated tag, because Meteor drops
data it does not load on the next save.

Settings that appear in the plan's table but have no backend are not exposed rather than invented:
per-tick caps, faceplace and armour thresholds, prediction ticks, power kinds, max structural blocks,
cleanup policy, Speedmine cooperation, switch delay and restore slot, double push, render hiding,
collision filtering, early reuse, plan outline, pushed-position marker and damage label.

Motion prediction and hurt-aware timing are off by default. Hurt-aware timing has no production
raw-hit feed in this build, so it stays inert; the parity document records the gap. Dangerous-crystal
break and anti-piston are independent, default-off gates. The piston layouts are beta and individually
toggleable; all sixteen off leaves PistonOnly visibly inert with no fallback to direct mode.

## Piston cleanup

By default the trap does not mine or break its own blocks. After a trap ends, aborts, times out or is
disabled, remove the crystal, piston, power and supports yourself only when safe, then run:

```
.crystalcombat cleanup confirm
```

The command releases only the single-trap slot and the plan's scheduler locks. It sends no packet and
keeps the ledger hazards and sent risk as debt. Until it is run, the info string says `manual cleanup
required`; `piston trap disabled` and `all piston layouts disabled` are reported separately.

The info string also lists the blocks this trap actually placed, with their coordinates, so the player
does not have to guess what the trap left behind. Only cells whose placement packet was sent are named:
power first, then the piston, then any supports, then the crystal while it is still present (at the source
cell before the push, at the computed pushed cell after it; it is omitted once the attack has been sent or
its removal was observed). The abort reason is included when one exists, for example:

```
manual cleanup required (target-pushed-vertically): remove power (-3, -60, 0), piston (-2, -60, 0), crystal (-1, -60, 0); .crystalcombat cleanup confirm
```

The debug log records the same list and reason on the phase change as
`manual-cleanup-blocks reason=... blocks=...`. With auto-cleanup off, manual cleanup remains the only
path: no cleanup packet, mining request or block-break action is added by the list.

### Beta auto-cleanup

The `auto-cleanup` setting (Piston group, off by default in every profile) lets the trap remove what it
placed after the crystal is resolved. Its description is exactly:

> Beta: removes only the power, piston and supports this module placed and saw confirmed. Contested blocks are always left for you.

The automatic path is deliberately narrow:

- Only cells whose placement packet was sent and then answered by a matching block update, i.e.
  `CrystalLedger.ownership(ticket.conn(), cell) == INFERRED_OURS` for this trap's connection, and still
  present in the current trap's `sentStructural`. `CONTESTED` and `NOT_OURS` cells are never touched,
  and a pre-existing obsidian, bedrock or other block the trap did not place cannot enter the set.
- Ownership and the exact block id are re-checked immediately before every action; a cell whose block
  or ownership changed is skipped.
- Order: power, then the piston, then the supports this trap added.
- Removal is a vanilla hotbar block break through `CombatRuntime.dispatch` only: no Speedmine, no other
  module and no offhand tool. The inventory adapter picks the best hotbar pickaxe for the piston and
  obsidian, and the selected hotbar item for the torch. If the tool a cell needs is missing the trap
  sends nothing for that cell and lets the deadline/manual path handle it; a torch cell needs no tool
  and is still removed with the selected hotbar item.
- Cleanup never starts while the trap crystal is unresolved and never detonates anything.
- It consumes packet budget only (no risk entry, lease debt or hazard) and shares the cleanup
  starvation rule: `CleanupPriority` gives cleanup the next free packet slot after 20 server ticks
  waiting, and the engine yields that slot only when the trap's feasibility probe confirms it can
  reserve (packet budget and hotbar slot), still evaluating non-imminent defence. The cleanup deadline
  is 40 ticks from entering `CLEANUP`; if the debris is not gone (obsidian supports usually take longer
  than that window) the sequence enters `MANUAL_CLEANUP` and lists what remains.
- Disable, death, respawn or a connection change stops cleanup immediately and cancels the vanilla
  destruction it started; no cleanup packet is sent afterwards. Hazard and risk retention are unchanged.

## Vertical-push abort

During POWER and WAIT_PUSH the trap watches the verified target. It aborts with
`target-pushed-vertically` when the target rises faster than a vanilla jump (more than 0.45 blocks per
tick; a jump peaks at about 0.42) and the client has recent evidence from either of two sources: an
explosion observation whose centre is within eight blocks of the target, or a non-plan block change
within four blocks, both within three server ticks. The explosion evidence comes from the runtime's
passive explosion observer on the client-thread observation path; that callback only records the tick.
The trap never sends anything on this path: no attack, no placement and no cleanup packet. The abort
keeps the risk reservation and the crystal hazard and marks the trap as manual cleanup.

**Residual limitation:** an outside explosion that lifts the target is not detected when the server
sends neither an explosion observation nor any nearby non-plan block change — for example when the
client sees only block updates for cells the trap itself placed, which are excluded as plan cells.

## Unsupported or unconfirmed layouts

All sixteen piston layouts are beta. The set is the four horizontal directions (east, west, north,
south) × two heights (feet, eye) × two power kinds (redstone torch, redstone block). Every layout has a
recorded Phase 0 fixture under `src/test/resources/combat/traces/piston_*` and is replayed through the
real `PistonTrapController` and `PistonSequence` by
`src/test/java/dev/tntcoating/core/combat/mc/PistonTraceReplayTest.java`, which uses the fixture's
recorded piston/power blocks, crystal spawn, arena floor, the `piston_tick_start`/`piston_tick_end`
progress values at their recorded tick offsets, and the crystal positions in `server_tick_end`; the
computed push is checked against the recorded final crystal position and displacement, not against a
second copy of the geometry constants.

**The list of unsupported or unconfirmed layouts is empty.** The Phase 0.4 trace report
(`docs/superpowers/audit/2026-10-29-trace-report.md`, Q7) checked all sixteen layouts against the server
log and none disagreed with the expected outcome under the measured 1.02-block push rule; the offline
geometry, planner, sequence, controller and replay tests agree. No layout is listed as unconfirmed or
unsupported beyond the beta status above.

Any other arrangement has no trace evidence and is not offered at all: the geometry check returns
`UNSUPPORTED_LAYOUT` for a scenario that is not one of the sixteen, the planner never produces a plan
for it, and no setting can turn it on. That includes vertical pistons, other power sources, a second
push and layouts whose measured displacement differs from the audited trace. If a future recorded trace
disagrees with the expected outcome, the affected layout must be listed here with the observed
difference before it ships; until then the list stays empty.

## Serialised Hybrid (Phase 6.4 stage 1)

Hybrid ships in its serialised stage: it changes which plan is chosen, never how a packet is sent. Both
the direct engine and the trap keep their own planner and their own reservations; the choice layer only
decides whose turn it is. A committed trap is modelled as a committed plan that blocks direct plans, and
the trap stays the owner of its sends, leases, hazards and risk. The engine never sends a trap packet
and never re-implements the trap's bookkeeping.

The choice order is the plan's Phase 6.1 rule:

1. Hard filters, in order: planner policy, risk budget, ledger protection, committed-plan conflict. The
   last filter is what serialises a trap: a direct plan that would interact with the trap's crystal is
   rejected, a defensive break of the trap crystal is rejected, and the trap's own ranking view exempts
   only the crystal that same plan has already bound. Footprint-scoped compatibility (Phase 6.3) is not
   evaluated, because it is not implemented in this stage.
2. Lethal first, but only when confidence is at least 0.7 and the plan's guaranteed lower bound reaches
   the target's effective-health upper bound. A lower bound cannot claim lethality on a known health,
   and an unknown health never claims lethality at all.
3. Otherwise `confidence × usefulDamage20 / max(1, etaTicks)`, where `usefulDamage20` is the estimated
   damage capped by the target's remaining health. The tie-breaks are lower self upper bound, then
   fewer actions, then stable opportunity ID.
4. Every rejection is counted with its reason (`MISSING_DATA`, `UNKNOWN_CONFIDENCE`, `POLICY`, `RISK`,
   `LEDGER`, `COMMITTED`, `NO_USEFUL_DAMAGE` or `LOWER_RANK`).

Hysteresis (Phase 6.2) is per-plan and keyed by target, strategy and intent signature. Before the
current plan's first irreversible action, a better plan must beat it by 20% for three consecutive server
ticks before the engine switches; the last tick's decision is reused if the engine is asked twice for
the same tick. Once the plan has a live crystal or a power reservation, it is never abandoned for a
score: it either finishes or is aborted by its owner. Policy or ledger invalidation overrides the hold
immediately, at any point in the plan's life. A plan that is no longer offered is still given the three
switch ticks before it is dropped, and a live plan that is no longer offered is held. Nothing here
retires risk; a hold is a scheduling decision, not evidence.

Defence is evaluated on every tick, including while the trap owns the field. Defence plans are ranked
separately, are not serialised by the trap, and still pass the conservative risk bound. A failed defence
send falls back to the normal choice only when the fallback plan has passed the same hard filters.

### Parity and ablation notes

- DirectOnly is unchanged: the engine skips hybrid ranking entirely and dispatches as before.
- PistonOnly is unchanged: the trap runs alone, and the direct engine is disabled.
- Hybrid with no committed trap equals DirectOnly for any direct plan that outranks the trap preview. A
  weak trap preview loses to a normal direct plan; the trap is only started when its ranking view wins
  outright.
- Hybrid with a committed trap equals PistonOnly for the trap's own sends, plus defence. The direct
  engine still ticks and still records its rejected plans, so the ablation shows what serialisation
  costs.

Before a trap is planned, the controller checks the hotbar (slots 0–8 only; the offhand is never used)
for the piston, end crystals, obsidian supports and at least one enabled layout's power item (redstone
torch or redstone block). If any of those is missing, no piston opportunity is offered or reserved, the
info string shows `piston trap: missing materials: ...`, and the debug log records the list once per
change. Starting a specific plan additionally re-checks that plan's own power item alongside the piston,
crystals and obsidian, so a preview can never begin after the exact power item it needs leaves the
hotbar: no plan is offered or started without its own materials.

A completed trap pass that found no plan is not terminal: the next tick starts a fresh pass over the
live target list with the same bounded per-tick work budget, so a target that becomes stationary or
steps into a hole after a fruitless pass can still be trapped. A completed pass that did find a plan
stays sticky until the engine selects it or reconciliation releases it, so a Hybrid offer cannot vanish
between the offer tick and the selection tick. Hybrid previews perform one observational world sample
per server tick (refresh the read view and advance the stationary trackers) before ranking, because the
trap itself is ticked only after selection; the sample is deduplicated per server tick, so a tick that
previews and then starts or ticks the trap advances the trackers exactly once. PistonOnly is unchanged:
its trap tick is the only sample point. A preview still never reserves a slot or lease, starts a trap or
sends a packet.

| Stage | Setting | Status | Evidence |
| --- | --- | --- | --- |
| Serialised Hybrid | shipped | Default in every preset | 1,000-seed interleaving and the Phase 6.1/6.2 choice tests pass; this is the baseline. |
| Footprint-scoped concurrency | `footprint-concurrency` (off) | Experimental, not promoted | Per-row `FootprintConflictTest`, `CombatRiskGateTest`, engine `HybridConcurrencyTest` and the on-path 1,000-seed `ConcurrentInterleavingTest` pass offline. The paired serialised-vs-footprint-scoped comparison in the tester checklist is still outstanding, so the stage stays off. |

### Cleanup scheduling and starvation

Cleanup has a real intent origin now: the trap emits `MINE_POWER`, `MINE_PISTON` and `MINE_SUPPORT`
intents for `INFERRED_OURS` cells and reserves each one through the shared `CombatScheduler` and packet
budget. The wait is counted in server ticks, is bound to the action generation and connection, and after
`CleanupPriority.STARVATION_TICKS` (20) server ticks the priority reports cleanup due for the next free
slot; `CombatEngine.hybridTick` yields its direct send to that slot after recording the choice while
still evaluating non-imminent defence. If no cell can be acted on (contested, changed or missing tool)
the trap clears the priority instead of blocking the engine, and the 40-tick cleanup deadline falls back
to `MANUAL_CLEANUP` with the remaining blocks. The info string shows
`cleanup due: waiting for the trap's cleanup slot` while cleanup is waiting. The metrics count the
waited ticks, the maximum wait and the starved ticks.

### Metrics and debug log

The Phase 4 metric table is unchanged. Hybrid adds its own counters: `choice_modes` (a tick per mode),
`choice_winners` (`lethal`, `utility`, `none`), `choice_rejections` (one per rejected candidate, keyed
by reason), `hysteresis_holds`, `hysteresis_switches` (`score` or `invalidated`), `trap_blocked_direct`,
`defence_during_trap`, `cleanup_waits`, `cleanup_max_wait_ticks` and `cleanup_starved`. They are kept
separate from the Phase 4 counters so the table and its tests do not move. The experimental concurrency
path adds its own table: `concurrency_admitted` (keyed by `compatible`), `concurrency_serialised` (keyed
by the conflict row or by `setting`/`uncached`), `concurrency_setting` (`on`/`off`) and
`concurrency_modes` (the active mode). The debug line prints setting, mode and phase on change only; it
never changes a decision.

The debug log's header records the active mode, whether hysteresis is engaged and `risk-model=<key>`.
The per-tick choice line is only written through the existing debug hook; each accepted dispatch line
also records the outstanding total. Model changes are logged once; `Barrier` and `Meteor-style`
retirements are logged once each, while a `Strict` rule-1 retirement is not separately logged. The log
never changes a decision.

## Footprint-scoped concurrency (Phase 6.4 stage 2, experimental)

`footprint-concurrency` (Experimental, default **off**) is the only way to admit a direct plan while a trap is
committed. With it off, Hybrid is exactly the serialised stage above. With it on, the engine still treats
every committed trap as blocking by default: a direct plan is admitted only through a positive
`FootprintConflict` verdict against the live trap footprint. A trap that is active but whose ranking view
or footprint cannot be built yields an *empty* view, and an empty view is a conflict, never a free pass.
The trap's own ranking opportunity is exempt only when it is the already-committed plan; the sentinel
does not depend on a ranking opportunity existing.

The check order is the plan's 6.3 table (`core/combat/FootprintConflict`; pure, no game imports):

| Row | Conflict when | Verdict |
| --- | --- | --- |
| Structural | the direct explosion could break the trap's piston or power cell under the audited ray rules (`7.8 - 0.75·distance - (resistance+0.3)·0.3 > 0`, power-6 worst case, no per-ray random roll); planned obsidian supports (resistance 1200) are immune | CONFLICT |
| Entity | the plan attacks the trap's bound crystal, or an explosion is within the audited 12-block power-6 entity search of it; the audited vanilla hit removes the crystal without a second explosion, so the trap loses its damage rather than detonating early | CONFLICT |
| Swept | an explosion is within 12 blocks of the trap's swept push volume | CONFLICT |
| Target motion | the audited 3-D knockback `normalize(eye − centre)·(1 − d/12)` (seen-percent 1, knockback multiplier 1, no resistance) moves the target's 4-tick predicted hull outside the trap's priced target window (`PistonGeometry.POSITION_TOLERANCE`), including vertical escape; missing motion/collision evidence or a LOST prediction is a conflict | CONFLICT |
| Resources | a live scheduler slot/rotation owner, or this tick's trap dispatch claims the slot/rotation the direct plan needs | SERIALISE (direct waits this tick; not a rejection) |
| Risk | the combined direct + trap reservation fails the same self/friend ceilings and margins | CONFLICT |

Unknown evidence in any row (null view, unknown block id, missing motion sample) is a conflict. Distance
alone is never accepted as compatibility.

Engine wiring keeps the single send path: the choice gate returns the verdict; dispatch, leases, hazards
and risk stay with their existing owners. Defence is still evaluated every tick and is not serialised by
the trap. The blast-resistance rule uses the pinned 26.1.2 bytecode (`f -= 0.22500001` per 0.3-block ray
step), which is stricter than the audit's summary that air "costs nothing"; the audited 12-block entity
search and the no-second-explosion crystal rule are unchanged.

The path is **not promoted**. Its per-row unit tests, engine tests and 1,000-seed interleaving test are
code-level evidence only; the paired serialised-vs-footprint-scoped comparison in the tester checklist
must be run on a real client before this setting can be considered for a default or a preset. P6.2 stays
off until that evidence exists. The cleanup-starvation item now has a real intent origin: the trap's
beta `auto-cleanup` sends vanilla hotbar breaks through the dispatch path and shares the same packet
budget and `CleanupPriority` starvation rule (see above); the concurrency path itself stays unpromoted.

## Diagnostics

The debug log is off by default and keeps the last five minutes in memory. Incidents include self death
while enabled, self damage above the outstanding upper bound, long-lived risk debt, every suspension,
trap abort after POWER, manual cleanup, computed/client position disagreement and a send-path invariant.
The incident-marker key writes a manual marker.

`.crystalcombat export` prints the zip contents without writing; `.crystalcombat export confirm` writes
the sanitized export. Other players' names and UUIDs are replaced by per-export salted hashes by default;
chat and server addresses are never included. `.crystalcombat cleanup` without `confirm` prints the
manual-removal instructions.

## Limitations

- DirectOnly, beta PistonOnly and serialised Hybrid with Conservative or Custom and locked anti-suicide
  are the accepted configurations; the plan's third profile is not offered, not silently approximated.
  Hybrid changes which plan is chosen, not how a packet is sent.
- Hybrid runs direct plans during a committed trap only when `footprint-concurrency` is explicitly on and
  `FootprintConflict` proves every 6.3 row; otherwise the committed trap serialises. The concurrent path
  is experimental, off by default and not promoted: no live paired comparison exists yet.
- Hybrid's trap ranking uses a deliberate discount: eta is the serialised lower bound (one action per
  tick) and confidence is `WEAK`, because the pushed-position damage is an estimate, not an observed
  value. Neither is a measured probability. This is recorded as an open question for the Phase 9
  comparison, not as a parity claim.
- The computed pushed crystal position is not confirmed by the server; C21 suppresses crystal position
  sync. The trap uses the measured two-tick 1.02 push and the client simulation only as a cross-check.
- The piston trap is code-level and offline-tested only; a real server may behave differently. The
  sixteen layouts are beta, not validated across servers.
- Automatic piston cleanup is a beta, off-by-default setting. It removes only inferred-ours cells
  through vanilla hotbar breaking on the dispatch path, leaves contested and pre-existing blocks in
  place, and never uses Speedmine, the offhand or a detonation. Missing the needed hotbar tool sends
  nothing; obsidian supports usually exceed the 40-tick cleanup deadline and end in manual cleanup.
- The production raw-hit feed for hurt-aware timing is absent; the setting cannot act on a real client.
  On 26.1.2 both `hurt-sync` and `motion-prediction` are unavailable and refuse activation (port
  audit §6.2).
- Move prediction, support, surround-gap and defence are experimental and off by default. They never
  relax a self or friend bound. Move prediction is unavailable on 26.1.2.
- Metrics exist without a UI surface in this build; most stay zero.
- No client or server was launched to validate this build. See the [tester guide](../testers/README.md),
  [tester build 3 handout](../testers/tester-3-handout.md) and
  [checklist](../testers/checklist.md) for the required live checks.
