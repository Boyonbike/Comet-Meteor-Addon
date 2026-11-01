# CrystalCombat

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **crystal-combat**.

**CrystalCombat** is the audited crystal-aura / piston-crystal combat module for Meteor 26.2. It is
**DirectOnly by default**. The piston trap is an opt-in beta in the `-tester.2` build and is not
server-confirmed. This is beta software: test on worlds where losing gear is acceptable.

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

## Settings

- **General** — mode, DirectOnly experimental tactics (motion prediction, hurt-aware timing, support,
  surround-gap, dangerous-crystal break, anti-piston), the experimental `footprint-concurrency`, debug
  log, incident marker, piston-trap and the sixteen beta layouts.
- **Safety** — the thresholds above; locked anti-suicide.
- **Ranges** — target, placement, attack and walls ranges.
- **Pauses** — eating/drinking, mining, screens, Baritone, self-health floor and lag.
- **Inventory** — hotbar-only switching; never offhand.

Motion prediction and hurt-aware timing are off by default. Hurt-aware timing has no production
raw-hit feed in this build, so it stays inert; the parity document records the gap. Dangerous-crystal
break and anti-piston are independent, default-off gates. The piston layouts are beta and individually
toggleable; all sixteen off leaves PistonOnly visibly inert with no fallback to direct mode.

## Piston cleanup

The trap does not mine or break its own blocks. After a trap ends, aborts, times out or is disabled,
remove the crystal, piston, power and supports yourself only when safe, then run:

```
.crystalcombat cleanup confirm
```

The command releases only the single-trap slot and the plan's scheduler locks. It sends no packet and
keeps the ledger hazards and sent risk as debt. Until it is run, the info string says `manual cleanup
required`; `piston trap disabled` and `all piston layouts disabled` are reported separately.

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

| Stage | Setting | Status | Evidence |
| --- | --- | --- | --- |
| Serialised Hybrid | shipped | Default in every preset | 1,000-seed interleaving and the Phase 6.1/6.2 choice tests pass; this is the baseline. |
| Footprint-scoped concurrency | `footprint-concurrency` (off) | Experimental, not promoted | Per-row `FootprintConflictTest`, `CombatRiskGateTest`, engine `HybridConcurrencyTest` and the on-path 1,000-seed `ConcurrentInterleavingTest` pass offline. The paired serialised-vs-footprint-scoped comparison in the tester checklist is still outstanding, so the stage stays off. |

### Cleanup gap

The trap exposes a manual-cleanup obligation, and Hybrid keeps a scheduling hook for it: the wait is
counted in server ticks, is bound to the action generation and connection, and after
`CleanupPriority.STARVATION_TICKS` (20) server ticks the hook reports that cleanup is due for the next
free slot. There is no cleanup intent origin in this build — the same reason Phase 5 mines nothing:
no owner-scoped mining/packet API exists, and Phase 5.5 keeps cleanup manual. The hook therefore sends
nothing. The info string shows `cleanup due: no cleanup intent in this build`, so the starvation is
visible instead of silently ignored; the metrics count the waited ticks, the maximum wait and the
starved ticks.

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

The debug log's header records the active mode and whether hysteresis is engaged. The per-tick choice
line is only written through the existing debug hook; the log never changes a decision.

## Footprint-scoped concurrency (Phase 6.4 stage 2, experimental)

`footprint-concurrency` (General, default **off**) is the only way to admit a direct plan while a trap is
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
the trap. The blast-resistance rule uses the pinned 26.2 bytecode (`f -= 0.22500001` per 0.3-block ray
step), which is stricter than the audit's summary that air "costs nothing"; the audited 12-block entity
search and the no-second-explosion crystal rule are unchanged.

The path is **not promoted**. Its per-row unit tests, engine tests and 1,000-seed interleaving test are
code-level evidence only; the paired serialised-vs-footprint-scoped comparison in the tester checklist
must be run on a real client before this setting can be considered for a default or a preset. P6.2 stays
off until that evidence exists. The cleanup-starvation item remains the documented manual-cleanup gap
above; no cleanup packet path exists.

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

- DirectOnly, beta PistonOnly and serialised Hybrid with Conservative are the only accepted
  configurations. Hybrid runs direct plans during a committed trap only when
  `footprint-concurrency` is explicitly on and `FootprintConflict` proves every 6.3 row; otherwise the
  committed trap serialises. The concurrent path is experimental, off by default and not promoted: no
  live paired comparison exists yet.
- Hybrid's trap ranking uses a deliberate discount: eta is the serialised lower bound (one action per
  tick) and confidence is `WEAK`, because the pushed-position damage is an estimate, not an observed
  value. Neither is a measured probability. This is recorded as an open question for the Phase 9
  comparison, not as a parity claim.
- The computed pushed crystal position is not confirmed by the server; C21 suppresses crystal position
  sync. The trap uses the measured two-tick 1.02 push and the client simulation only as a cross-check.
- The piston trap is code-level and offline-tested only; a real server may behave differently. The
  sixteen layouts are beta, not validated across servers.
- Automatic piston cleanup and power-source mining are absent because no owner-scoped Speedmine
  request/cancel API exists. Cleanup is the explicit command above.
- The production raw-hit feed for hurt-aware timing is absent; the setting cannot act on a real client.
- Move prediction, support, surround-gap and defence are experimental and off by default. They never
  relax a self or friend bound.
- Metrics exist without a UI surface in this build; most stay zero.
- No client or server was launched to validate this build. See the tester guide and checklist for the
  required live checks.
