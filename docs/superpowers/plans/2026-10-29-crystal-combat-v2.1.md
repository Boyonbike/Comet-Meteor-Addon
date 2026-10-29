# CrystalCombat Implementation Plan v2.1

Oct 28, 2026 · @Innes

## Purpose and scope

This plan builds one `CrystalCombat` module for Comet, with DirectOnly, PistonOnly and Hybrid modes, on a shared engine. It supersedes the 2026-10-28 v1 plan and keeps its architecture, but changes the safety model, the task order and three feature decisions.

Target first: Minecraft 26.2, Java 25, Meteor `26.2-20261009.074120-35`. Later ports: 26.1.2 (Java 25, Meteor build 42) and 1.21.11 (Java 21, vendored build 82).

This document authorises planning only. It does not authorise writing product code, launching the client, joining any server or publishing a build. Each of those needs a separate go-ahead.

### What changed from v1

| Area | v1 | v2 | Why |
| --- | --- | --- | --- |
| Risk reconciliation | Released only on explosion + fresh health packet | Released on explosion/removal + latency window with no health change (superseded in v2.1) | Health packets only arrive on change; v1 could deadlock forever |
| Health freshness | Age-based staleness | "No unresolved event older than X" | Health is legitimately quiet for long periods |
| Cumulative self damage | Sum of per-crystal upper bounds | Sum of each close-arrival group's two largest hits | Sound even with outside hits; much less throttling on bursts of 3+ |
| Task order | Four infrastructure tasks before anything runs | Phase 0 records real 26.2 event order first | Ledger and models are built against observed traces |
| Task 4 | One task | Split into 2a scheduler/risk, 2b runtime/inventory, 2c Meteor suppression | Separately reviewable; 2c is the most version-fragile |
| Hybrid concurrency | Serialised, footprint narrowing later | Footprint-scoped concurrency is core Phase 6 work | Without it Hybrid loses direct pressure for up to 5 s per trap |
| ID prediction | Bound over every reachable position | Only when no unknown crystal actor is in reach (superseded in v2.1: removed) | The v1 bound was almost always unsatisfiable |
| Defence | Break dangerous crystals | Adds anti-piston detection and blocking | Open-source precedent (GameSense Blocker); you will be pistoned too |
| Release gate | 19 of 20 per layout | 19/20 smoke gate, 48/50 for default layouts | 19/20 only bounds success at about 75% |

### What changed in v2.1 (technical review)

An external review read the cached 26.2 bytecode and Meteor build 35 source and found that v2 treated several probabilistic observations as guarantees. v2.1 adopts its findings. Its evidence files are on your machine and were not re-checked here; Phase 0 must confirm each against the pinned artifacts.

| Area | v2 | v2.1 |
| --- | --- | --- |
| Risk retirement | Quiet window and 40-tick rejection retired risk | Timeouts change backoff only; risk retires on terminal evidence covering the reservation, or world close |
| Safety profiles | One rule for all | Conservative keeps hard-guarantee debt; Anarchy may opt into a labelled statistical quiet window |
| Damage quantities | One "raw post-mitigation" value | `RawDamage`, `MitigatedBeforeAbsorption`, `EffectiveHealth` kept separate |
| Risk reservation point | At attack | At `PLACE_CRYSTAL`, since others can detonate our crystal |
| Cumulative bound | Grouped top-two from the start | Summed conservative bound first; grouping only after its proof obligations pass |
| Target lower bound | Full value at confidence 0.5 | Unknown prior hurt → guaranteed lower bound 0; expected and optimistic kept separately |
| Friend health | Implied hidden | Read from tracked metadata when available; known / unknown / censored states |
| Crystal hit by explosion | Detonates early | Destroyed without a second explosion (inspected vanilla) |
| Knockback | Zero in a full hole; 6-block cutoff | Three-dimensional push, 12-block entity search, swept exposure |
| Damage raycast | Meteor default | Separate audited raycast policies for estimates, target lower bounds and self/friend upper bounds |
| Observations | Enqueued on receive | Receipt sequence plus applied-state revision; snapshots after the application barrier |
| Dispatch | Binary send | Packet-level progress; risk kept if any causative packet may have left |
| Ownership | Owned if we placed and observed it | Confirmed state, inferred attribution and contested ownership kept separate; ambiguous cleanup stays manual |
| Death | Closes session | Action generation ends; world hazards persist and are rescanned |
| ID prediction | Narrowed precondition | Removed from the hard-safety milestone entirely |
| Optimistic removal | Render and collision | Render hiding only; collision filtering and reuse are separate experiments |
| Delivery order | Lifecycle late | First usable milestone is DirectOnly with suppression, lifecycle, risk and metrics |
| Ablation | Interval overlap | Paired treatment-minus-baseline effect with a prespecified threshold |
| Performance budget | Per planner pass | All combat work in a client tick, shared deadline |

### How to use this plan

Work phases in order. Each phase lists its files, contracts, tests to write first, implementation steps and an exit gate. A phase is done only when its exit gate passes and a reviewer signs the checkpoint. Tick the `- [ ]` boxes as you go.

### Delivery order

1. Phase 0: desk audit, then (separately authorised) trace recording.
2. Phases 1–2: pure core, conservative risk accounting, runtime, Meteor suppression, minimal module lifecycle and metrics, built together.
3. Phase 3: DirectOnly vertical slice, measured against Meteor. **First usable milestone.**
4. Phase 4: direct tactics and defence, each behind its own gate.
5. Phase 5: single-push piston layouts.
6. Phase 6: serialised Hybrid, then footprint-scoped concurrency only if it demonstrates benefit.
7. Phase 7: double push and presentation experiments, each independently gated.
8. Phase 8: remaining UI; inventory lending behind its own gate.
9. Phase 9: full validation; Phase 10: ports.

At no stage is speculative ID prediction in scope.

## Global constraints and provenance

Safety invariants are hard rules: no profile, tactic, override or emergency may weaken them. Everything else in this plan is a tunable default.

### Hard invariants

1. One packet-sending path for combat-owned work. Only `CombatRuntime.dispatch` sends interaction, attack, swing, slot or rotation packets, including delegated mining and rotation requests. Planners never send.
2. Verified attacks need a real current-world crystal identified by entity ID and UUID within the current connection epoch.
3. Self and friend safety cannot be bypassed by faceplace, lethal override, hurt-sync, cleanup, defence or any profile. No self-lethal totem gambling.
4. Every crystal placement and every attack acquires a risk reservation atomically with its action, slot and rotation reservations. Placement reserves because another actor can detonate our crystal before we attack it.
5. Timeouts change retry and backoff state only. They never retire risk or clear a hazard.
6. Sent risk retires only when authoritative terminal evidence and a causally complete effective-health snapshot cover that reservation, or when its connection closes. Uncertain evidence suspends new risk spending.
7. Unresolved sent-placement hazards survive timeout, disable, death and plan invalidation.
8. Manual disable stops automatic sends immediately. No cleanup packets after disable.
9. No sends from render callbacks or network threads.
10. No silent double execution with Meteor CrystalAura. Missing critical mixin capability refuses activation.
11. Never toggle the user's other modules. Never mutate Meteor's settings or enabled flag.
12. Manual slot selection, cursor/container state and offhand totem priority win over automation.
13. No speculative (unverified-ID) attacks in any profile that claims these invariants.

The Anarchy profile may opt into a statistical quiet-window rule that relaxes invariant 6. When it does, the UI and docs must say the guarantee is statistical, and Phase 9 must report its observed miss rate.

### Defaults that are not invariants

Ranges, damage thresholds, packet budgets, hysteresis values, timeouts and caps are engineering starting points, listed with rationale in the defaults section. They change from measurement, not from guesses.

### Provenance policy

Comet ships under a GPL-compatible licence with full corresponding source. Every borrowed algorithm, helper or constant needs a pinned upstream revision, its licence, the author, and a note of what was changed. Record this in file headers, `THIRD_PARTY_NOTICES` and the source ZIP.

Borrowing means one of three things, and each file states which:

- **Ported code:** copied and adapted from a licence-compatible source. Needs header and notice.
- **Reimplemented idea:** written fresh after reading a compatible source. Needs a notice line.
- **Independent:** written from vanilla behaviour and tests alone. No notice needed.

### Approved references

| Source | Use for | Mode | Pin |
| --- | --- | --- | --- |
| Meteor 26.2 build | Damage, faceplace, Weakness, pauses, inventory semantics; test baseline | Ported where needed | `26.2-20261009.074120-35` |
| Lambda (new rewrite) | Shared InteractionSettings/RotationSettings/Targeting split; opportunity concepts | Reimplemented | `e8eb261c…` plus the CrystalAura PR revision used |
| THM | 26.2 integration points only | Reimplemented | `21140a37…`; recheck lineage before use |
| Coyduck | Hurt-aware arrival timing, gap fill, defensive pass, budgets | Ported selectively | `20d01a51…` |
| LiquidBounce | Event-driven invalidation, rotation queue, movement simulation | Reimplemented | Pin a 26.2 revision (v0.40.0 tag) before reading code |
| BlackOut | Piston geometry and ordering; real displaced-crystal attacks | Ported selectively | `4c18af3f…` plus the fork it comes from |
| GameSense PR #95 (TechAle) | Piston failure cases; Blocker anti-piston idea | Reimplemented | PR #95 merge commit |
| Marlow's Crystal Optimizer (MIT) | Presentation-overlay reference for optimistic removal | Reimplemented | Pin a release tag |
| WaifuHax | Geometry ideas only | Reimplemented, no code | n/a |

### Excluded sources

These never enter the codebase, notes or tests, in code or as ideas:

- Cracked clients and loaders (for example rushercrack).
- Decompiled or deobfuscated paid clients, whatever licence badge they carry (for example OpenMio).
- Leaked proprietary source (for example Shoreline leaks).
- Renamed ThunderHack builds flagged as ratted.

Closed clients you hold a licence for (Future, RusherHack, Mio, CatLean) may be used only as black-box opponents or baselines in Phase 9, and via public docs or plugin APIs. Never decompile them.

### Deferred HongShi/Alien and Saturn behaviour

They remain regression fixtures only. Their power-before-crystal ordering is a known-bad case the piston tests must reject.

## Architecture and contracts

Two planners propose, one scheduler reserves, one runtime sends. Observations flow the other way: network events become immutable records, the client thread applies them to the ledger, and planners see them in the next snapshot.

&#91;embedded content: engine data flow · 8 components, one sender\]

Observations run along the top row on their way in; actions run along the bottom row on their way out. Only the highlighted runtime talks to the server.

### Data flow per client tick or event

1. Meteor posts its Receive event before vanilla handles the packet. Network intake therefore records only an immutable receipt: connection ID, receipt sequence number, bundle position, nanoTime and the raw packet fields. It resolves nothing mutable and never touches planners.
2. On the client thread, after vanilla has applied the packet, an application hook stamps the matching receipt with an applied-state revision. Only applied receipts become `Observation`s. UUIDs and world data are resolved here, not in intake.
3. `CombatEngine.drain()` applies observations to `CrystalLedger`, `CombatHurtModel`, `CombatRiskBudget` and `CombatMetrics` in receipt order, preserving bundle order.
4. `CombatRuntime.capture()` builds a bounded immutable `Snapshot` stamped with the applied-state revision it reflects.
5. `DirectPlanner`, `PistonPlanner` and `DefensePlanner` evaluate the same snapshot and return `Opportunity` records. None sends.
6. `CombatEngine.choose()` filters unsafe opportunities, ranks the rest and hands the winner's next `Intent` to `CombatScheduler`.
7. `CombatScheduler.reserve()` atomically acquires block, entity, slot, rotation, packet-budget and risk reservations, or nothing.
8. `CombatRuntime.dispatch()` revalidates against the newest applied revision, then sends packet by packet, recording progress (see Phase 2b).

Event-triggered reactions (a crystal spawn, a block break) run steps 3–8 in the client-thread task that applies the event, without waiting for the next tick. The reaction delay is a measured service objective in Phase 9 (observation-to-application and application-to-send latency), not an assumed one-frame bound.

### Package layout

All paths are under `26.2/src/main/java/dev/tntcoating/`. Tests mirror them under `src/test/java/`.

| Package / file | Responsibility | Touches Minecraft? |
| --- | --- | --- |
| `core/combat/CombatTypes` | Shared immutable records and enums | No |
| `core/combat/CombatSession` | World epochs, config generations, tickets | No |
| `core/combat/CrystalLedger` | Crystal lifecycle, spawn hazards, quarantine | No |
| `core/combat/CombatDamagePolicy` | Threshold checks | No |
| `core/combat/CombatMotionModel` | Bounded collision-aware prediction | No (uses `CollisionView`) |
| `core/combat/CombatHurtModel` | Immunity-window and arrival modelling | No |
| `core/combat/CombatRiskBudget` | Cumulative self/friend exposure | No |
| `core/combat/CombatScheduler` | Leases, dependencies, packet budget | No |
| `core/combat/DirectPlanner`, `DirectSequence` | Direct aura opportunities and sequence | No |
| `core/combat/PistonPlanner`, `PistonSequence` | Piston opportunities and sequence | No |
| `core/combat/DefensePlanner` | Dangerous crystal breaking, anti-piston blocking | No |
| `core/combat/CombatEngine` | Orchestration and choice | No |
| `core/combat/CombatMetrics` | Attempt/observation/outcome counters | No |
| `core/combat/CombatRecorder` | Phase 0 trace recorder, kept for validation | Reads only |
| `core/combat/mc/CombatRuntime` | Snapshot capture, observation intake, dispatch | Yes |
| `core/combat/mc/CombatDamageAdapter` | Explosion exposure, armour, effects | Yes |
| `core/combat/mc/CombatInventoryAdapter` | Hotbar selection, loans, restoration | Yes |
| `modules/CrystalCombat` | Meteor module, settings, info string | Yes |
| `render/CrystalCombatRender` | Read-only plan rendering | Yes |
| `mixin/CrystalAuraMixin` | Meteor CrystalAura suppression | Yes |

The `core/combat` package must compile and test without Minecraft on the classpath. Enforce it with a Gradle source-set or ArchUnit check in Phase 1.

### Core contracts

These are project types, not claims about Minecraft APIs. All collections are defensively copied.

```java
record ConnectionKey(long epoch) {}                 // one per server connection / world
record ActionGeneration(long value) {}              // ends on death, respawn, config change, disable
record EntityKey(ConnectionKey conn, int id, UUID uuid) {}
record Cell(int x, int y, int z) {}
record Vec(double x, double y, double z) {}
record Box(Vec min, Vec max) {}
record Ticket(ConnectionKey conn, ActionGeneration gen, long config, long plan) {}
record ServerTickInterval(long earliest, long latest) {}   // server ticks, not wall clock

enum Mode { DIRECT_ONLY, PISTON_ONLY, HYBRID }
enum Strategy { DIRECT, PISTON, DEFEND, CLEANUP }
enum Action { PLACE_BASE, PLACE_SUPPORT, PLACE_PISTON, PLACE_CRYSTAL, PLACE_POWER,
              ATTACK_CRYSTAL, MINE_POWER, MINE_PISTON, PLACE_BLOCKER }
enum ObservationKind { SPAWN, MOVE, REMOVED, TRACKING_REMOVED, BLOCK_UPDATE, EXPLOSION,
                       HEALTH, ENTITY_DATA, EFFECT, EQUIPMENT, DAMAGE_EVENT, ENTITY_STATUS,
                       TOTEM_POP, SETBACK, DEATH, RESPAWN, CONNECTION_CHANGE }
enum Capability { KNOWN, UNKNOWN, CENSORED }
enum Ownership { CONFIRMED_OURS, INFERRED_OURS, CONTESTED, NOT_OURS }

record Observation(ConnectionKey conn, long receiptSeq, long appliedRevision,
                   long receiptNanos, int clientTick, ObservationKind kind,
                   EntityKey entity, Cell cell, Vec position, Box bounds,
                   ObservationPayload payload) {}

// Damage quantities are never mixed
record RawDamage(double value) {}
record MitigatedDamage(double beforeAbsorption) {}   // after armour, toughness, enchants, effects
record EffectiveHealth(double health, double absorption, Capability cap, long revision) {}

record TargetEstimate(double guaranteedLower, double expected, double optimistic) {}
record ExposureBound(MitigatedDamage selfUpper, Map<EntityKey, MitigatedDamage> friendUpper,
                     MitigationEnvelope envelope) {}
record Safety(double minTarget, double maxSelf, double minRemaining, double maxFriend) {}

record Intent(long id, long plan, Strategy strategy, Action action,
              Cell cell, EntityKey entity, Set<Cell> blockLocks,
              Set<EntityKey> entityLocks, Set<Intent> dependsOn, int expiresTick) {}

record Opportunity(long id, Strategy strategy, EntityKey target, Vec explosion,
                   TargetEstimate targetDamage, ExposureBound exposure, int etaTicks,
                   double expectedDamage20, int actionCost, List<Intent> plan,
                   double confidence, Footprint footprint) {}

record Footprint(Set<Cell> structural, Box swept, Box blastSearch, Set<EntityKey> bound) {}
```

`EntityKey` carries its `ConnectionKey`, so identical numeric IDs across connections are never equal. `ActionGeneration` is separate: death ends every in-flight action but crystals and structures can survive in the same world, so hazards are kept and rescanned before reactivation. `Footprint` is what Hybrid uses to decide whether two plans can run concurrently. `SPECULATIVE_DIRECT` is gone from `Strategy`.

### Threading

- Network thread: record immutable receipts into a bounded MPSC queue, preserving bundle order. Nothing else.
- Client thread: stamp applied revisions, drain, snapshot, plan, reserve, dispatch.
- One shared per-tick work budget covers all of that, including event-triggered passes (see Phase 9.3). Invalidations are coalesced; optional planning is deferred when reconciliation uses the budget.
- Worker threads: none until whole-client JFR profiles show the budget is exceeded.
- Queue overflow: suspend new risk spending, log, take a fresh snapshot and reconcile. Never drop an observation silently.

## Safety model

The aura may spend risk only when the conservative worst case of every unresolved reservation, plus the new one, leaves self effective health above the margin and every friend within limits. In Conservative, risk retires only on evidence; silence and elapsed time never retire it.

### Three damage quantities, never mixed

- **`RawDamage`**: explosion damage as the hurt path receives it, before armour. The immunity rule subtracts the previous raw hit at this stage, before mitigation.
- **`MitigatedDamage`**: after armour, toughness, protection enchantments and Resistance, before absorption.
- **`EffectiveHealth`**: health plus absorption from one coherent snapshot. Absorption is subtracted here only, never inside a damage estimate, so it is never credited twice.

Each reservation `r` carries `u_r`, a `MitigatedDamage` upper bound, and an arrival interval in **server ticks**. Server ticks are tracked separately from client wall-clock time, because a server stall or low TPS separates them. The margin `m` stays strictly greater than 2.

### Mitigation envelope

Upper bounds assume the weakest protection that could apply by the latest possible arrival:

- An armour piece that could break before arrival is assumed broken.
- An effect (Resistance, absorption source) that could expire before arrival is assumed gone.
- When protection deteriorates, every outstanding reservation's `u_r` widens. It never narrows until the reservation retires.

### Cumulative bound: summed first

All profiles start with the plain sum:

```latex
D_{\max} = \sum_{r \in R} u_r
```

Allow a send only if the effective-health lower bound minus `D_max` exceeds `m`. Repeat per friend with their own `u` values.

### Grouped bound (gated, later)

The inspected 26.2 hurt path subtracts the previous raw hit before mitigation, and under fixed armour, toughness and protection the mitigation formula has a nondecreasing damage-to-input ratio. That suggests a group of hits landing within one immunity window costs at most the mitigated largest raw hit, and a group whose arrivals span less than `W` touches at most two windows:

```latex
D_{\max} = \sum_{g \in G} \left( u^{(1)}_g + u^{(2)}_g \right)
```

This is a mathematical inference, not yet a proof for gameplay. Grouping stays off until every obligation below passes, and falls back to the plain sum whenever one fails at run time:

- [ ] Phase 0 confirms the raw-subtraction order and the ratio property against 26.2 bytecode, for every armour, toughness and protection combination in the test matrix.
- [ ] Every member's arrival fits inside a stated server-tick interval shorter than `W`. Under a detected stall or TPS below 18, fall back.
- [ ] One common worst-case mitigation envelope applies across the whole group.
- [ ] Damage types that bypass the cooldown are excluded from grouping.
- [ ] Armour breakage, effect expiry or a cooldown reset that could happen inside the group's interval → fall back.
- [ ] A property test over random arrivals and prior outside hits never finds actual damage above the bound.
- [ ] Server overrides: grouping is enabled only on a server where Phase 9 confirms vanilla behaviour.

The v2 "fresh window after silence" tightening is removed: an outside hit can already have landed before its observation arrives.

### Target estimates

`TargetEstimate` keeps three values apart:

- **`guaranteedLower`**: 0 whenever the target's prior hurt is unknown or a larger hit may still be inside its window.
- **`expected`**: the best estimate, used for ordinary ranking and minimum-damage checks.
- **`optimistic`**: used only for diagnostics.

Lethal eligibility requires `guaranteedLower` at least equal to the target's effective-health upper bound. Ranking with low-confidence expected values is fine; claiming lethality with them is not.

### Friend health

In inspected vanilla, health is tracked entity metadata sent to tracking players. Read friend health and absorption through an adapter that reports `KNOWN`, `UNKNOWN` (not tracked) or `CENSORED` (the server sends placeholder values; detect constant or implausible values).

- The per-friend cumulative damage ceiling (default 2) applies in every state.
- A friend-survival check applies only with `KNOWN` health and uses a conservative lower bound.

### Reservation lifecycle

| State | Entered when | Leaves when |
| --- | --- | --- |
| Proposed | A planner returns an opportunity | Reserved, or dropped |
| Reserved | Atomically acquired with action, slot and rotation locks | `DISPATCHING`, or cancelled (released) |
| Dispatching | Before the first causative packet | Sent once any causative packet may have left; released only if non-transmission is established |
| Sent | A placement or attack may have reached the server | Retired by the rules below, or connection close |
| Debt | Sent, with no terminal evidence yet | Retired by rule 1 or 3, or connection close |

Placement crystals reserve at `PLACE_CRYSTAL`. A later swing or slot-restore failure never rolls back an already queued attack.

### Retirement rules (Conservative: hard guarantee)

1. **Terminal evidence with a coherent snapshot.** An explosion at the bound crystal's position is observed, and then an effective-health snapshot arrives whose applied revision is causally after it. One transaction applies the snapshot and retires exactly the reservations whose explosions precede it. The snapshot need not attribute the change; it only has to postdate the explosion, so unrelated healing is harmless.
2. **Coherence.** Health and absorption updates that may belong to one damage event are held until both are applied for the same bundle or revision. An absorption-only update never retires anything on its own.
3. **Processing barrier.** Where no health change follows (a zero-damage or weaker in-window hit), retire only with evidence that the server has processed everything up to the explosion and sent any resulting health sync. The candidate barrier is the vanilla block-change acknowledgement sequence: send a sequenced interaction after the explosion is applied, wait for its acknowledgement, then one further server tick. Phase 0 must verify that ordering against health sync; a keepalive or a send callback does not qualify.
4. **Debt.** Anything without terminal evidence stays as debt and counts in `D_max`. Debt blocks only sends that would cross the margin. The info string and render show outstanding debt.
5. **Respawn and connection close.** Respawn clears self debt (health is reset by the server). Friend debt and crystal hazards persist and are rescanned. Connection close clears everything for that connection.

Timeouts never retire risk. A crystal that disappears through tracking removal (out of range) is not evidence of detonation. Removal of an unrelated crystal retires nothing.

### Rejected attacks

A 40-tick wait only sets backoff. The reservation stays `Sent` until the crystal's explosion is observed (rules 1–3), or a processing barrier sent after the attack is acknowledged while the crystal is still present in a later applied snapshot. That barrier proves the server processed the attack and did not detonate the crystal.

### Anarchy statistical option

The Anarchy profile may enable a labelled quiet-window rule: retire with zero damage after an explosion plus RTT p99 + jitter p99 + 2 ticks with no health change. The setting text says the guarantee is statistical and can fail under delays beyond the measured tail. Phase 9 reports its observed miss rate. Conservative never uses it.

### Suspension

Uncertain evidence suspends new risk spending (offence and defence detonations) until reconciled:

- Observation queue overflow.
- An unreconciled setback, death, respawn or connection change.
- Missing equipment or effect data the damage adapter needs.
- A pending incoherent health/absorption pair.
- A detected server-tick stall (server time not advancing while the client does).

### What this does not promise

The budget bounds module-issued risk. Damage from other players, mobs or the environment can still arrive between observations.

### Tests that must pass before Phase 2 closes

- [ ] Summed bound blocks the third self-6 reservation at effective health 20, margin 2.
- [ ] Absorption is subtracted only once (estimate excludes it; effective health includes it).
- [ ] Armour that could break before arrival widens every outstanding bound.
- [ ] Server stall beyond 40 client ticks: no reservation retires, and a late-landing attack is still covered.
- [ ] Delays beyond the p99 tail: Conservative retires nothing early; Anarchy statistical mode records a miss.
- [ ] Split absorption then health updates: no retirement until coherent.
- [ ] Unrelated healing after the explosion retires correctly without double counting.
- [ ] Zero-damage detonation retires only after the processing barrier; without a barrier it remains debt and blocks only margin-crossing sends.
- [ ] Tracking removal does not retire.
- [ ] Rejected attack retires only after a barrier with the crystal still present.
- [ ] Failure after each packet step in dispatch: risk is retained once any causative packet may have left.
- [ ] Death and respawn in the same world: self debt cleared, friend debt and hazards kept.
- [ ] Hidden prior hurt on the target: `guaranteedLower` is 0 and lethal override does not fire.
- [ ] Friend health `CENSORED`: ceiling still applies, survival check skipped.
- [ ] Queue overflow and stall detection suspend detonations until reconciled.
- [ ] Grouping disabled path is exercised in every test above; grouping tests run only behind its flag.

## Phase 0: API audit and observation recorder

Before any planner exists, record what 26.2 actually sends during crystal combat. Every later model is tested against these traces instead of against assumptions.

**Goal:** an audit file of pinned API signatures, and a set of recorded traces covering every event the engine will consume.

**Needs authorisation:** recording requires launching the client against a local test server you control. Get that go-ahead before step 0.4.

### 0.1 API audit (desk work, no launch)

- [ ] Record exact 26.2 Mojmap signatures and source paths for: entity add/remove packets, block update packets (single and section), block-change acknowledgement, explosion packet, health/food packet, entity data (health, absorption), effect add/remove, equipment, damage event, entity status (totem pop), player position/setback, respawn, dimension change and bundle delimiters.
- [ ] Confirm which of these Meteor exposes as events, on which thread, and whether before or after vanilla applies the packet. Build 35's Receive fires before handling; find or add an after-application hook.
- [ ] Read the 26.2 `LivingEntity` hurt path. Record the invulnerability timer, the immunity window `W`, that the previous raw hit is subtracted before mitigation, which damage types bypass the cooldown, and whether a stronger in-window hit broadcasts a damage event.
- [ ] Read `CombatRules` mitigation and confirm the nondecreasing damage-to-input ratio under fixed armour, toughness and protection (a grouping proof obligation).
- [ ] Read `EndCrystal.hurtServer`: confirm a crystal damaged by an explosion-tagged source is removed without a second explosion.
- [ ] Read the 26.2 explosion: entity search radius (expected 12 for power 6), three-dimensional knockback including vertical, and block destruction rules by blast resistance.
- [ ] Read crystal placement validity: confirm only the first block above the base must be air while a two-block entity volume is checked.
- [ ] Read the attack handler: confirm which invalid targets cause a disconnect (items, experience orbs, qualifying arrows).
- [ ] Read `ServerEntity` and entity data: confirm player health and absorption are sent to tracking players, and how often.
- [ ] Read piston push behaviour for end crystals: distance per tick, collisions, retraction.
- [ ] Read Meteor build 35 `DamageUtils`: record that its default raycast ignores blocks below 600 blast resistance, and that a custom raycast factory is accepted.
- [ ] Record Meteor 26.2 APIs the adapters will call: rotation manager callbacks, `InvUtils`, `PlayerUtils`, and every CrystalAura send path (tick, spawn callback, pre-attack swap, delayed rotation callback).
- [ ] Record existing Comet helpers and their real contracts: `InventorySlotLoan` (recognises AutoTool/AutoWeapon; `prepare()` can restore a loan and send a swap, so it is not a pure query), `InventoryWeaponAttack`, `ScaffoldCoordinator`, `Speedmine.requestBreak` (no owner-scoped cancel), `PlacementTracker`, `ServerPacer`, `PacketBudget`. Note Offhand and rotation owners.
- [ ] Record SHA-256 hashes of every inspected artifact. Where a publisher checksum exists, compare it; otherwise state that the artifact is unauthenticated.
- [ ] Write `docs/superpowers/audit/2026-xx-combat-api-26.2.md` with every signature, path, hash and finding.

### 0.2 Provenance pins

- [ ] Resolve each approved reference to a specific repository and commit. For BlackOut, record the fork as well as the commit.
- [ ] Re-check THM's lineage. If it derives from a flagged ThunderHack rename, drop it as a reference.
- [ ] Pin the LiquidBounce 26.2 revision and the Lambda CrystalAura PR revision.
- [ ] For each, decide ported, reimplemented or independent, and record it.

### 0.3 CombatRecorder

- [ ] Write `CombatRecorder`: a read-only listener that logs every audited observation kind with receipt nanoTime, client tick, entity ID, UUID, position and payload to JSON Lines. No sends.
- [ ] Add a manual hotkey that marks a trace with a free-text label (for example "placed crystal", "attacked").
- [ ] Unit test: serialisation round-trips and the recorder never calls any send method (verify with a fake runtime).

### 0.4 Record traces on a local test server

Use a local server with a scripted fake target (a second account or a server-side dummy). Record each scenario at least five times:

- [ ] Manual crystal place then hit, target in the open.
- [ ] Same, target in a hole; record the target's vertical and horizontal motion after the explosion.
- [ ] Self-damaging crystal at three distances, including one that does zero self damage.
- [ ] Two crystals inside one immunity window, weaker second; then stronger second.
- [ ] Two crystals across a window boundary.
- [ ] Self with absorption: record whether absorption and health arrive in one bundle or separately.
- [ ] A crystal destroyed by another crystal's explosion: confirm no second explosion.
- [ ] A crystal placed where the server rejects it; a crystal attacked out of reach.
- [ ] A sequenced interaction sent after an applied explosion: record acknowledgement timing relative to any health packet.
- [ ] A server stall (pause the server process for 3 s) mid-sequence.
- [ ] Piston push of a crystal in each of four directions, feet and eye height, torch and redstone block power.
- [ ] Totem pop on the target; friend health metadata while tracking a friend.
- [ ] Death and respawn in the same world with crystals present; disconnect and reconnect; dimension change.

Repeat at added one-way delay of 0, 40 and 100 ms each way (80 and 200 ms RTT) with `tc netem` on CachyOS, and at server TPS 20, 15 and 10. Always state whether a figure is RTT or one-way. Log server ticks and authoritative events server-side as well, so client traces can be checked against ground truth.

### 0.5 Write the trace report

- [ ] For each scenario, record the observed event order, inter-event delays and which observations never arrived.
- [ ] Answer explicitly from traces:
  1. Does the explosion packet arrive before or after the crystal removal?
  2. Is a health packet sent on a zero-damage hit, or on a weaker in-window hit?
  3. Do absorption and health from one damage event share a bundle?
  4. Does a block-change acknowledgement sent after an explosion reliably follow any health sync it caused? This decides whether the processing barrier (safety rule 3) exists.
  5. How many ticks does a piston push of a crystal take, and where does it end?
  6. Is a holed target pushed vertically by a crystal explosion?
- [ ] Store traces under `src/test/resources/combat/traces/` as fixtures, with the server-side log beside each.

### Exit gate

- [ ] Audit file reviewed, with artifact hashes.
- [ ] All six questions in 0.5 answered from traces, not from memory.
- [ ] `W`, raw-before-mitigation subtraction and crystal-explosion semantics confirmed for 26.2.
- [ ] Processing-barrier question answered. If no barrier exists, Conservative keeps zero-damage risk as debt and the plan records that limitation.
- [ ] Provenance pins recorded.
- [ ] At least 50 traces stored as fixtures.

## Phase 1: Session, ledger and models

Build the pure, Minecraft-free foundations, and test them against the Phase 0 traces as well as synthetic cases.

**Files:** `CombatTypes`, `CombatSession`, `CrystalLedger`, `CombatDamagePolicy`, `CombatMotionModel`, `CombatHurtModel`, `mc/CombatDamageAdapter`, and a `TraceReplayer` test utility. Matching `*Test` classes.

### 1.1 Purity guard

- [ ] Add a Gradle check (ArchUnit or a separate source set) that fails the build if any class under `core/combat` except `mc/*` imports `net.minecraft` or `meteordevelopment`.

### 1.2 CombatSession

Interface: `open(): WorldKey`, `reconfigure(): void`, `ticket(long plan): Ticket`, `valid(Ticket): boolean`, `close(): void`.

Tests first:

- [ ] Identical numeric entity IDs across world epochs are distinct `EntityKey`s.
- [ ] `reconfigure()` invalidates outstanding tickets.
- [ ] `close()` then `open()` never validates an old ticket.
- [ ] `close()` never calls the runtime.

Implement with monotonic `long` counters. Run `./gradlew test --tests '*CombatSessionTest'`.

### 1.3 CrystalLedger

Interface:

```java
HazardId reserveSpawn(ConnectionKey c, Box source, Box swept, Set<EntityKey> baseline);
void cancelUnsent(HazardId h);
void markSent(HazardId h, int tick);
void observe(Observation o);
Optional<EntityKey> bound(HazardId h);
boolean unresolved(HazardId h);
boolean blocksCell(Cell c);
boolean blocked(EntityKey e, Box b);
CrystalState state(EntityKey e);   // PROPOSED, SENT, OBSERVED, TRACKING_LOST, EXPLODED, REMOVED, QUARANTINED
Ownership ownership(Cell c);       // CONFIRMED_OURS, INFERRED_OURS, CONTESTED, NOT_OURS
void actionGenerationEnded(ActionGeneration g);   // death: keep hazards, mark for rescan
void rescan(Snapshot s);
void connectionClosed(ConnectionKey c);
```

Binding rule: a sent spawn hazard binds a crystal only when exactly one new end crystal spawns inside its source box, not in the baseline set, after the send tick. Two or more candidates → quarantine the region.

Ownership rule: block updates carry state and position, not who placed the block. A cell becomes `INFERRED_OURS` when its update matches our pending placement and nothing else could plausibly explain it; `CONFIRMED_OURS` is reserved for a cooperating server that supplies causal evidence. Any unexplained update makes the cell `CONTESTED`. Automatic cleanup may mine only `CONFIRMED_OURS` cells under the hard requirement, or `INFERRED_OURS` cells if the user enables that explicitly; everything else is manual cleanup.

Tracking removal (an entity leaving tracking range) sets `TRACKING_LOST`, never `EXPLODED` or `REMOVED`.

Tests first:

- [ ] Reserve before send; cancel before send releases.
- [ ] Late spawn after abort or disable still binds to the persistent hazard.
- [ ] Two simultaneous plausible spawns → quarantine; no repeat placement in that region.
- [ ] Unrelated spawn outside the swept envelope is ignored.
- [ ] Out-of-order block update and spawn (from traces) reconcile correctly.
- [ ] Duplicate observations are idempotent.
- [ ] Removal followed by another plausible delayed spawn does not double-bind.
- [ ] Timeout and ticket invalidation never clear a sent hazard.
- [ ] Tracking removal yields `TRACKING_LOST`, not removal.
- [ ] An identical block update that could belong to an earlier send or another player makes the cell `CONTESTED`.
- [ ] Death in the same world keeps hazards and marks them for rescan; respawn rescan rebinds surviving crystals.
- [ ] Replay every Phase 0 trace; assert final ledger state matches the labelled outcome.

Keep observation queues bounded (default 4096). Overflow sets a stale flag that the risk budget reads.

### 1.4 CombatDamagePolicy

`allows(DamageEstimate e, Safety s, boolean targetOverride): boolean`.

- [ ] Accept at expected target ≥ 4, self upper ≤ 6, every friend upper ≤ 2, effective-health lower bound − self upper > 2.
- [ ] Reject at a remainder of exactly 2 (strict margin).
- [ ] Reject NaN, infinity and negative inputs.
- [ ] Faceplace or lethal override relaxes only the minimum target damage.
- [ ] Lethal override requires `guaranteedLower` ≥ the target's effective-health upper bound; expected values never qualify.

This policy checks one opportunity. Cumulative checks belong to `CombatRiskBudget` in Phase 2.

### 1.5 CombatDamageAdapter

Wraps the audited 26.2 exposure and mitigation code behind a pure `ExposureView` and `MitigationView`. It must not mutate the client world.

- [ ] Three audited raycast policies through Meteor's custom raycast factory (or a project-owned ray-caster if needed):
  - **Faithful**: vanilla collision, for expected damage.
  - **Target lower bound**: treats uncertain cover as blocking, so soft cover never inflates lethal eligibility.
  - **Self/friend upper bound**: treats uncertain cover as open. Meteor's default (ignoring blocks below 600 blast resistance) is acceptable here only.
- [ ] Return `RawDamage` and `MitigatedDamage` separately; never subtract absorption inside an estimate.
- [ ] Mitigation envelope: compute bounds with armour pieces that could break and effects that could expire before the latest arrival removed.
- [ ] Differential test against a fixed pinned-Minecraft fixture (not a second copy of the formula): no Resistance vs Resistance I/II, armour tiers, Blast Protection levels, toughness, difficulty.
- [ ] Overlay test: a planned obsidian block changes exposure as the fixture says.
- [ ] Swept exposure: evaluate damage over all feasible crystal positions along a push path and all feasible victim positions, not just two endpoints.
- [ ] Per-friend exposure returned as a map.

### 1.6 CombatMotionModel

`predict(MotionSample s, int ticks, CollisionView v): PredictedBox` returning a box and a confidence.

- [ ] Three-dimensional prediction, including vertical escape from holes after explosion knockback.
- [ ] Stops at walls and floors; slides along walls.
- [ ] Returns a reachable-position set (box hull) with confidence, not only a point.
- [ ] Direction reversal lowers confidence.
- [ ] Teleport or setback invalidates the prediction.
- [ ] Prediction capped at 4 ticks; beyond the cap, confidence is 0.

### 1.7 CombatHurtModel

`observeHit(EntityKey e, double raw, Arrival a, double confidence)`, `effectiveRaw(EntityKey e, double incoming, Arrival a): Estimate`, `windowState(EntityKey e): WindowState`.

- [ ] Stronger, equal and weaker follow-up raw hits within `W` give raw increments of `incoming − lastHurt`, 0 and 0, applied before mitigation.
- [ ] Window expiry restores the full value.
- [ ] Unknown prior hurt on the target → `guaranteedLower` 0; `expected` uses the full value at reduced confidence.
- [ ] Arrival intervals are in server ticks; a jitter interval crossing a window edge yields a range.
- [ ] The model never lowers a self or friend upper bound unless grouping is enabled and its obligations hold.

### Exit gate

- [ ] All Phase 1 tests pass; purity guard enforced.
- [ ] Every Phase 0 trace replays without ledger assertion failures.
- [ ] Reviewer checkpoint on ledger binding and quarantine logic.

## Phase 2: Scheduler, risk budget, runtime, Meteor suppression and lifecycle

v1's Task 4 is split into four independently reviewed sub-phases. 2a is pure; 2b and 2c touch Minecraft and Meteor; 2d brings the minimal module, lifecycle and metrics forward so Phase 3 can ship a usable, measured DirectOnly milestone.

### 2a. CombatScheduler and CombatRiskBudget (pure)

**Files:** `CombatScheduler`, `CombatRiskBudget`, `PacketLedger`, tests.

Scheduler interface:

```java
boolean reserve(Intent i, Ticket t, RiskRequest r);   // all-or-nothing
boolean ready(Intent i, Snapshot s);                   // dependencies met, not expired
void sent(Intent i, int tick);
void invalidate(Ticket t);                             // drops unsent, keeps sent
void release(long plan);
Optional<Lease> holder(Cell c);                        // for Hybrid footprint checks
```

Shared resources: block cells, entities, hotbar slot, rotation, mining slot, and one rolling packet budget. Each `Lease` records plan, strategy, expiry and footprint.

Risk budget interface:

```java
Optional<RiskId> reserve(long intent, ExposureBound upper, ServerTickInterval arrival, SafetyState s);
void dispatching(RiskId r);            // before the first causative packet
void mayHaveLeft(RiskId r);            // a causative packet was accepted for local enqueue
void notTransmitted(RiskId r);         // only when non-transmission is established
void observe(Observation o);           // drives retirement rules 1–5
void widen(MitigationEnvelope e);      // protection deteriorated
void actionGenerationEnded(ActionGeneration g);
void connectionClosed(ConnectionKey c);
boolean suspended();                   // uncertain evidence
Debt debt(EntityKey who);              // for UI
Bound current(EntityKey who);          // summed (or gated grouped) bound
```

Tests first:

- [ ] Every test listed under the safety model section.
- [ ] Atomic acquisition: a denied partial reservation leaves no locks and no risk reserved.
- [ ] Strategies share one packet budget; cleanup and defence count against it.
- [ ] An expired intent is not ready; its unsent risk is released, its sent risk is kept.
- [ ] A stale ticket or ended action generation can never be dispatched.
- [ ] Event-triggered reservations go through the same `reserve` call as tick-driven ones.
- [ ] Debt from zero-damage hits blocks only margin-crossing sends.

**Exit gate 2a:** all tests pass; reviewer checkpoint on the risk budget implementation specifically.

### 2b. CombatRuntime and CombatInventoryAdapter (Minecraft-facing)

**Files:** `mc/CombatRuntime`, `mc/CombatInventoryAdapter`, `mc/ObservationIntake`, tests.

Runtime interface: `capture(): Snapshot`, `drain(): List<Observation>`, `dispatch(Intent i, Ticket t): DispatchResult`, `close(): void`.

Dispatch performs, in order, on the client thread:

1. Ticket valid: connection open, action generation and config generation current.
2. Bound entity still present with matching UUID in the newest applied revision (verified attacks).
3. Fresh estimate still passes policy; risk reservation still held; risk not suspended.
4. Reach and visibility against the current player position and the platform's real interaction range.
5. Inventory: required item in the expected slot, no screen open, no manual slot change since reservation, offhand untouched, no other owner's loan or weapon attack pending.
6. Rotation satisfied if rotations are on.
7. Enter `DISPATCHING` and call `risk.dispatching`.
8. Send packet by packet. For each, record local enqueue acceptance, send-method completion and (later) any server acknowledgement as separate facts. The first accepted causative packet (place or attack) calls `risk.mayHaveLeft`.
9. Supporting packets that fail afterwards (swing, slot restore) never roll back a causative packet that may have left.

A failure before step 8 returns a typed reason and releases the unsent reservations. A failure during step 8 keeps every reservation whose causative packet may have left, and releases only those proven untransmitted.

Inventory adapter rules:

- Hotbar-only switching for the first milestone; main-inventory lending has its own gate later.
- Restore the previous slot only if the module still owns the selection and the user has not changed it.
- Never move the offhand item; defer to AutoTotem and Offhand.
- `InventorySlotLoan.prepare()` can restore a loan and send a swap, so it is never called as an availability check. Add a side-effect-free query, or treat any active loan as "slot owned elsewhere".
- `Speedmine.requestBreak` has no owner-scoped cancel. Until one is added, combat may not issue mining requests.
- Define explicit ownership contracts with Offhand, Scaffold, AutoTool/AutoWeapon and the rotation manager: who holds the slot or rotation, for how long, and how each yields.

Reuse existing helpers: `PlacementTracker` for block confirmation, `ServerPacer` for feedback timing, `PacketBudget` for counting. These helpers are not locks; the scheduler is.

Tests (fake Minecraft where possible, integration test where not):

- [ ] Receipts are recorded on the network thread; observations are created only after vanilla applies the packet.
- [ ] Bundle order and connection identity are preserved through drain.
- [ ] Snapshots carry the applied revision; dispatch revalidates against the newest one.
- [ ] Dispatch after disable, death, connection change or config change sends nothing.
- [ ] Failure injected after each packet step keeps or releases risk exactly as specified.
- [ ] Manual slot change between reserve and dispatch aborts before step 8.
- [ ] Open screen blocks dispatch.
- [ ] Pending `InventoryWeaponAttack` or active loan blocks the combat switch without calling `prepare()`.
- [ ] Offhand totem is never touched.

**Exit gate 2b:** tests pass; `./gradlew compileJava` clean; reviewer checkpoint.

### 2c. Meteor CrystalAura suppression

**Files:** `mixin/CrystalAuraMixin`, `compat/MeteorUpgradeCheck`, capability registration, `CrystalCombatCompatibilityTest`.

While CrystalCombat is enabled, Meteor CrystalAura must not place, attack, swap or rotate, in every mode. Its enabled flag, settings and internal bookkeeping stay untouched.

- [ ] Audit every Meteor 26.2 CrystalAura send path: tick place, tick break, spawn-triggered fast break, pre-attack swap (`doBreak` before swap), delayed rotation callbacks that later place or attack, and any render-thread path.
- [ ] Inject one guard `CrystalCombatGate.suppressMeteor()` at each path, preserving null-world guards.
- [ ] Each Meteor callback captures a suppression generation when it is created and checks it again when it executes; a callback created while CrystalCombat was enabled never sends after disable.
- [ ] Passive hazard exclusions (ledger-protected crystals) still apply to Meteor after disable, as a read-only check in the same mixin.
- [ ] Capability check verifies each injection target by descriptor at startup. Do not rely on `MeteorUpgrades.applied()`'s default-true state.
- [ ] Missing critical injection → CrystalCombat refuses to enable and shows why; Meteor CrystalAura keeps working normally.
- [ ] Integration test: both modules enabled → zero Meteor sends over 200 ticks of combat fixture.
- [ ] Integration test: CrystalCombat disabled with Meteor callbacks queued → none of them sends.

**Exit gate 2c:** capability check passes on the pinned Meteor build; both integration tests pass; reviewer checkpoint.

### 2d. Minimal module, lifecycle and metrics

Moved forward from Phase 8 so the first usable milestone has correct activation, disable and visibility.

**Files:** `modules/CrystalCombat` (minimal settings), `CombatMetrics`, `CrystalCombatModuleTest`.

- [ ] Settings for this milestone only: DirectOnly mode, Conservative profile, safety thresholds, ranges, pauses.
- [ ] Enable: capability check first; refuse with a visible reason if a critical injection is missing.
- [ ] Disable: invalidate tickets, stop sends immediately, keep the ledger and risk budget observing passively, no cleanup packets.
- [ ] Death: end the action generation; keep hazards and friend debt; rescan on respawn before any action.
- [ ] Connection close: clear that connection's state; no old-connection packets.
- [ ] `getInfoString()`: current action, or the wait reason ("no crystals", "risk debt", "suspended: stall", "capability missing").
- [ ] `CombatMetrics` counters from Phase 4.7 exist from the start, so the Phase 3 comparison has data.

**Exit gate 2d:** disable at every direct state sends nothing afterwards; wait reasons display; module tests pass.

## Phase 3: Verified direct aura baseline

This is the **first usable milestone**: a DirectOnly aura that matches Meteor's correctness essentials over the new engine, with hard-safety risk accounting, suppression, lifecycle and metrics already in place, and no speculative behaviour. It must be measured against Meteor before any tactic is added.

**Files:** `DirectPlanner`, `DirectSequence`, first version of `CombatEngine`, `EngineHarness` test utility, tests.

### 3.1 Target selection

- [ ] Candidates: players within target range 6, not friends, not dead, not in creative or spectator.
- [ ] Sticky choice: keep the current target while it remains valid and its best opportunity is within 20% of the best alternative's.
- [ ] Deterministic ordering: score, then distance, then entity ID. No hash-map iteration order leaks into decisions.
- [ ] Up to 3 targets evaluated per pass; the rest are skipped, not queued.

### 3.2 Break opportunities

For each crystal in the ledger with state OBSERVED within attack range 3.0 (and the platform's real reach):

- [ ] Not hazard-protected by the ledger (no live piston trap or quarantine owns it).
- [ ] Visible, or within walls range if walls are allowed.
- [ ] Damage estimate passes policy for at least one target; self and per-friend upper bounds pass.
- [ ] Age at least `minAge` ticks (default 0) and attempts below `maxAttempts` (default 2) per crystal.
- [ ] Own-only option: only crystals bound to this module's own placements.
- [ ] Weakness handled: switch to a tool that breaks with Weakness, or skip when none is available, honouring switch delay.

### 3.3 Place opportunities

For each obsidian or bedrock cell within place range 4.5:

- [ ] Vanilla rule (as inspected): the first block above the base is air, and the two-block-high entity volume above is free of entity hitboxes. A crystal counts as present until its removal is observed, even after an attack was sent.
- [ ] Optional stricter profile setting: require two air blocks above the base. This is a deliberate restriction, not the vanilla rule.
- [ ] Placement face reachable and visible per the interaction rules.
- [ ] Expected damage at the crystal centre passes policy against the target's current box (prediction arrives in Phase 4); self and friend upper bounds use the upper-bound raycast policy.
- [ ] Faceplace when target effective health ≤ faceplace threshold (default 8), or to pressure armour near breakage (any piece ≤ 10% durability). Low durability does not itself reduce mitigation before the piece breaks; this is a pressure tactic, not a damage gain. Faceplace only relaxes minimum target damage.
- [ ] Support placement: if no base exists, propose an obsidian base as a dependency, limited to 1 support block per opportunity.

### 3.4 DirectSequence

States: IDLE → PLACE\_SENT → SPAWN\_OBSERVED → ATTACK\_SENT → REMOVAL\_OBSERVED → IDLE. Any state can move to ABORTED.

- [ ] Before placing: reserve the spawn hazard in the ledger and a risk reservation for that crystal (another player may detonate it), then dispatch.
- [ ] Unique matching spawn observed → bind and propose an attack in the same client-thread task.
- [ ] Rejected or delayed spawn → hazard and risk stay; no retry into the same cell until resolved.
- [ ] Attack sent → the attack shares the crystal's existing risk reservation; retirement follows the safety model rules.
- [ ] Never attack an unspawned or unverified ID.
- [ ] No duplicate send for the same intent from both a tick and an event path.

### 3.5 Pauses

- [ ] Pause on eating, drinking, mining (configurable), open screen, Baritone active (configurable), and self health below a configurable floor.
- [ ] Pause when TPS estimate is below 10 or the last server feedback is older than 1 s (lag pause).
- [ ] A pause invalidates queued unsent intents but keeps hazards and sent risk.

### 3.6 CombatEngine v1

- [ ] Owns the session and orchestrates drain → snapshot → plan → choose → reserve → dispatch.
- [ ] DirectOnly mode only at this stage.
- [ ] Per-tick caps: 1 place and 1 verified break (Conservative), counted against the shared budget.

### Tests

- [ ] Existing crystal break vs fresh placement choice on the same snapshot.
- [ ] Occupied placement box rejected (player, item entity, other crystal).
- [ ] Out-of-reach and wall cases from fixtures.
- [ ] Weakness with and without a usable tool.
- [ ] Friend standing next to the target → reject if friend upper bound > 2.
- [ ] Full place → spawn → break → removal cycle against a recorded Phase 0 trace.
- [ ] Rejected spawn trace → no retry into that cell until resolved.
- [ ] Event and tick both proposing the same attack → one send.

### Meteor parity check

Build a parity table before the exit gate: for each Meteor CrystalAura setting that affects correctness, record whether CrystalCombat has an equivalent, a deliberate difference, or a gap. Gaps must be either fixed or listed as known.

### Exit gate

- [ ] All tests pass.
- [ ] Parity table reviewed; no unexplained correctness gaps.
- [ ] On the local test server (authorised), 100 consecutive verified cycles with zero invariant violations. With zero failures in 100 independent trials, the one-sided 95% upper bound on the per-cycle failure rate is about 2.95%; state that, not "never fails".
- [ ] Paired comparison against Meteor CrystalAura on the Phase 9.2 protocol (same terrain, target programme and seed, randomised AB/BA order). Record the treatment-minus-baseline effect and its interval. DirectOnly becomes the default mode only if it shows no meaningful regression.

## Phase 4: Advanced direct tactics, event reaction and defence

Add deterministic improvements one at a time, each behind its own setting, each with an ablation test planned for Phase 9. No speculation in this phase.

**Files:** extend `DirectPlanner`, `CombatEngine`; new `DefensePlanner`, `CombatMetrics`, `CandidateCache`; tests `DirectTacticsTest`, `CombatInvalidationTest`, `DefensePlannerTest`, `CombatMetricsTest`.

### 4.1 Event-driven invalidation and caching

- [ ] `CandidateCache` keyed by world epoch, config generation, target equipment/effect revision, block collision revision, overlay revision and crystal lifecycle revision.
- [ ] Spawn, removal, block update, move and damage observations invalidate only the affected candidates.
- [ ] Before-send validation runs on every cache hit; the cache never skips it.
- [ ] Searches are capped (direct 128 candidates per pass) and resumable across ticks; a result computed for an old snapshot is never committed.

### 4.2 Motion prediction

- [ ] Use `CombatMotionModel` to evaluate expected damage over the target's reachable-position set at the expected arrival, capped at 2 ticks (Conservative) or 3 (Anarchy). The guaranteed lower bound uses the least exposed reachable position.
- [ ] Self and friend upper bounds use the most exposed reachable position within 1 tick, including vertical movement.
- [ ] Low motion confidence reduces opportunity confidence, never self safety.

### 4.3 Hurt-aware arrival scoring

- [ ] Use `CombatHurtModel` to estimate expected target damage given the target's window state and the opportunity's server-tick arrival interval.
- [ ] Prefer timing that lands just after the target's window ends, when the expected gain beats immediate detonation over the 20-tick horizon.
- [ ] Never delay a lethal opportunity for hurt-sync.
- [ ] Unknown target window state → no hurt-sync adjustment, and `guaranteedLower` stays 0.
- [ ] Hurt-sync never changes self or friend bounds.

### 4.4 Base and support overlays

- [ ] Evaluate placing an obsidian base where none exists, using the damage adapter's virtual overlay.
- [ ] Max 1 support block per direct opportunity; count it in action cost.

### 4.5 Surround-gap opportunities

- [ ] Detect missing or broken surround blocks around a hole target.
- [ ] Propose crystal placements that exploit the gap, scored on expected damage.
- [ ] Speedmine cooperation is blocked until Speedmine gains an owner-scoped request and cancel contract. Then: an explicit mining reservation on one surround block, cancelled if the block is not authoritatively cleared by its deadline. Never mine as a hidden side effect.

### 4.6 Defensive pass (DefensePlanner)

Two defensive opportunity types, both using the same identity, range, item and safety checks as offence:

**Dangerous crystal break.**

- [ ] Any observed crystal within attack range whose self upper bound would breach the margin if it were detonated by someone else.
- [ ] Breaking it must itself pass the risk budget.
- [ ] Never break a crystal the ledger protects for our own active trap, unless the trap is first invalidated by the engine.

**Anti-piston blocking (new in v2, from GameSense's Blocker idea).**

- [ ] Detect an enemy piston-crystal setup aimed at self: a piston within 2 blocks of self's head or feet cell, facing toward self, with an end crystal or empty crystal-ready cell in front of it.
- [ ] Responses, in preference order: break the crystal if safe; place an obsidian block in the push path between crystal and self; mine the power source if Speedmine cooperation is enabled.
- [ ] Defensive placements use `PLACE_BLOCKER` and share the packet budget.
- [ ] Tests from scripted geometry: four directions, feet and head height, with and without a crystal already placed.

Defence priority: an imminent threat (estimated arrival within 4 ticks) outranks offensive plans but never overrides self or friend checks. Breaking a dangerous crystal is itself a detonation and needs its own risk reservation. The dangerous-crystal break and the anti-piston blocker each have an independent gate and default; neither is required for the Phase 3 milestone.

### 4.7 Metrics

`CombatMetrics` keeps separate counters, never merged:

| Counter | Meaning |
| --- | --- |
| `estimated_target_damage` | Sum of expected estimates at send time |
| `place_attempts` / `place_observed` | Placement sends vs observed spawns |
| `attack_attempts` / `explosion_observed` | Verified attack sends vs observed explosions of the bound crystal |
| `target_damage_observed` | Target health drops after our explosions, with confidence |
| `totem_pops_observed` | Target totem pops after our explosions; attribution is weak without server logs |
| `unknown_outcome` | Sends with no attributable outcome |
| `risk_debt` | Outstanding debt over time, and retirements by rule |
| `suspensions` | By cause |
| `defence_actions` | By type |
| `queue_age_ms`, `observe_to_apply_ms`, `apply_to_send_ms` | Latency service objectives, p50/p95/p99 |
| `combat_tick_ms` | All combat work in a tick, p50/p95/p99 |

A removal is never counted as damage. An unrelated despawn stays in `unknown_outcome`.

### Exit gate

- [ ] All tests pass.
- [ ] Each tactic can be toggled independently and defaults are recorded.
- [ ] Each tactic has a paired ablation scenario scripted for Phase 9, with its prespecified practically meaningful improvement written down before evaluation.

## Phase 5: Single-push piston

One confirmed trap at a time, against stationary surrounded or hole targets, using the shared ledger, damage, risk and scheduler services. No speculative IDs and no optimistic removal, ever, in piston paths.

**Files:** `PistonPlanner`, `PistonSequence`, `PistonGeometry`, tests `PistonGeometryTest`, `PistonPlannerTest`, `PistonSequenceTest`, `PistonCleanupTest`.

### 5.1 Contracts

```java
record PistonStage(Cell piston, Direction facing, Cell power, PowerKind kind, Box swept) {}
record PistonPlan(long id, EntityKey target, Cell source, Vec expectedPushed,
                  List<PistonStage> pushes, List<Intent> dependencies,
                  Footprint footprint, Opportunity opportunity) {}
enum PowerKind { REDSTONE_TORCH, REDSTONE_BLOCK }
enum PistonPhase { SEARCH, RESERVE, SUPPORTS, PISTON, CRYSTAL, POWER, WAIT_PUSH,
                   ATTACK, WAIT_REMOVE, CLEANUP, COOLDOWN, ABORTED, MANUAL_CLEANUP }
```

`pushes` holds exactly one stage in this phase. The list shape exists so Phase 7 can add a second push without changing ownership.

### 5.2 Geometry search

- [ ] Target must be stationary (motion model speed below 0.05 blocks/tick for 10 ticks) and in a hole or full surround. Being in a hole does not mean it cannot move: explosions push in three dimensions.
- [ ] Enumerate 4 horizontal directions × 2 heights (feet, eye) = 8 base layouts.
- [ ] For each: source cell for the crystal (vanilla rule: one air block above the base, two-block entity volume free), piston cell behind it facing the target, extension cell, power cell adjacent to the piston but not powering it early.
- [ ] Check vanilla legality of every placement face, piston facing from the player's rotation at placement time, and that the power cannot reach the piston before the crystal is bound.
- [ ] Reject layouts where power would also activate any other piston, door or redstone component in range.
- [ ] Support depth ≤ 2 below any placed block; ≤ 6 new structural blocks total.
- [ ] Push offsets come from Phase 0 traces per direction and height, not fixed 0.75/0.5 constants.
- [ ] Damage is evaluated over the whole swept path: every feasible crystal position from source to final, against every feasible target and self position. The endpoints alone do not bound intermediate positions, where cover can change.
- [ ] Self and friend bounds use the most exposed point on the path, and placement reserves risk at `PLACE_CRYSTAL` because the crystal is exposed to other players' attacks from the moment it exists.
- [ ] Candidate cap 64 per pass; resumable within the shared tick budget.

### 5.3 Sequence (ordered dependencies)

1. **RESERVE.** Acquire leases on every structural cell, the source cell, the swept volume and the power cell. Create the ledger spawn hazard covering source and swept boxes.
2. **SUPPORTS.** Place missing support blocks bottom-up. Wait for each block observation.
3. **PISTON.** Rotate to give the correct facing, place, and wait for the block observation. Verify the observed facing; wrong facing → abort and clean up.
4. **CRYSTAL.** Place the crystal. Wait for a unique matching spawn; bind its ID and UUID.
5. **POWER.** Place the torch or redstone block. Wait for the block observation.
6. **WAIT\_PUSH.** Wait for the bound crystal's move observation to the expected pushed region, within the measured push time plus latency.
7. **ATTACK.** Re-run damage and risk at the actual observed position. Attack the bound entity only.
8. **WAIT\_REMOVE.** Wait for removal; hand risk resolution to the budget.
9. **CLEANUP.** Remove owned power first; optionally mine the owned piston; leave harmless supports.
10. **COOLDOWN.** 10 ticks before the next trap.

Power-before-crystal is a known-bad ordering from HongShi/Alien; a test must reject it.

### 5.4 Failure handling

- [ ] Each stage has a confirmation timeout of max(20 ticks, RTT p99 + 5 ticks).
- [ ] Structural placements may retry up to 2 times. An unresolved crystal placement is never retried.
- [ ] Ambiguous spawn (two plausible crystals) → quarantine, abort, no further piston trap until resolved.
- [ ] Target leaves the geometry envelope → abort before POWER; after POWER, complete the push only if the attack still passes safety.
- [ ] Damage estimate at the actual pushed position fails policy → do not attack; keep the crystal protected; enter CLEANUP.
- [ ] Trap deadline 100 ticks; cleanup deadline 40 ticks.
- [ ] Deadlines and timeouts change retry and backoff only; they never retire risk. At a deadline: release broad locks, keep spatial and entity exclusions and all risk reservations, set MANUAL\_CLEANUP and show it in the info string. No new piston trap until reconciled. Direct combat elsewhere may continue.

### 5.5 Cleanup ownership

- [ ] Cleanup uses the ledger's ownership states. Under the hard requirement, automatic cleanup mines only `CONFIRMED_OURS` cells, which vanilla cannot provide; in practice cleanup is manual unless the user enables `INFERRED_OURS` cleanup explicitly.
- [ ] Any unexplained block update on a cell makes it `CONTESTED`; contested cells are never touched automatically.
- [ ] Disable at any phase: stop sending immediately, keep hazards and risk; no cleanup packets.
- [ ] Death mid-trap: end the action generation; keep the trap's hazards; rescan on respawn and show manual cleanup if needed.

### Tests

- [ ] All 8 layouts with and without existing bases.
- [ ] Support depth 2 accepted, 3 rejected; 6 structural blocks accepted, 7 rejected.
- [ ] Torch and redstone block legality; power that would activate another component is rejected.
- [ ] Wrong observed piston facing → abort.
- [ ] Full happy path replayed from Phase 0 piston traces.
- [ ] Swept path passing a more exposed intermediate position than either endpoint → bound uses the intermediate.
- [ ] Target pushed vertically out of the hole by an outside explosion → trap aborts.
- [ ] Late and ambiguous spawns; ID reuse across connections.
- [ ] Target movement before and after POWER.
- [ ] Disable and death at each phase → no further sends, hazards and risk retained.
- [ ] Cleanup with inferred-only ownership stays manual by default; contested blocks untouched.

### Exit gate

- [ ] All tests pass.
- [ ] Local test server (authorised): for each of the 8 layouts × 2 power kinds, ≥ 19 of 20 complete cycles, zero wrong-entity attacks, zero policy violations, zero stale-world sends.
- [ ] Unsupported layouts are listed explicitly in the module docs.

## Phase 6: Hybrid with footprint-scoped concurrency

Hybrid ships serialised first: during a committed trap, direct plans wait. Footprint-scoped concurrency is the target design, but it must earn its place through a paired comparison against serialised Hybrid and DirectOnly. If it does not beat them, it stays off.

**Files:** extend `CombatEngine`, `CombatScheduler`, `CombatMetrics`; new `FootprintConflict`; tests `HybridChoiceTest`, `FootprintConflictTest`, `InterleavingTest`.

### 6.1 Choice

`CombatEngine.choose(List<Opportunity> all, Snapshot s): Optional<Opportunity>`.

1. Hard filter: drop anything that fails policy, the risk budget, ledger exclusions or footprint conflicts with a committed plan.
2. Lethal first: among survivors, the earliest credible lethal opportunity (confidence ≥ 0.7, target lower bound ≥ effective target health).
3. Otherwise rank by `confidence × usefulDamage20 / max(1, etaTicks)`.
4. Tie-break by lower self upper bound, then fewer actions, then stable opportunity ID.
5. Record the winner and each rejected opportunity's reason in metrics.

Unknown values reduce confidence. No invented probabilities.

### 6.2 Hysteresis

- [ ] Before a plan's first irreversible action, switch only if another safe plan's utility exceeds it by 20% for 3 consecutive ticks.
- [ ] After any crystal or power action, finish or abort safely; never abandon live structures to chase a score.
- [ ] Safety invalidation overrides hysteresis immediately.

### 6.3 Footprint conflict rules

Two plans may hold leases at the same time only if every rule below passes. Distance alone is never enough.

| Check | Conflict if |
| --- | --- |
| Structural | A's explosion could break any of B's structural, power, source or piston cells under the inspected blast-resistance rules |
| Entity | A's explosion search volume (12 blocks for power 6) includes B's bound crystal, or A would attack it |
| Swept | A's explosion could damage or displace anything inside B's swept volume |
| Target motion | A's explosion could move the target, in three dimensions, out of B's geometry envelope, given collision geometry and exposure |
| Resources | Both need the same slot or rotation in the same tick (resolved by serialising, not by rejection) |
| Risk | Combined reservations fail the budget |

When compatibility cannot be established for any row, serialise.

Under the inspected vanilla rules, a direct explosion can destroy the trap crystal before its intended attack, but it does not trigger a second crystal explosion: `EndCrystal.hurtServer` removes a crystal hit by an explosion-tagged source without exploding it. The trap therefore loses its damage rather than detonating early. Server modifications can differ, so Phase 0 confirms this on the target server type. Torches and pistons break easily; redstone blocks can break at close range.

Knockback: the inspected explosion computes a three-dimensional push, including a vertical component, and has no full-hole exemption. Its entity search reaches 12 blocks for a power-6 crystal, so there is no fixed 6-block cutoff. Bound the target's possible motion against collision geometry and exposure, including vertical escape; if that bound cannot keep the target inside the envelope, the plans conflict.

### 6.4 Concurrency stages

1. **Serialised (ships first).** During a committed trap, direct plans are blocked; defence evaluation continues. Validate Hybrid choice logic and measure it against DirectOnly.
2. **Footprint-scoped (gated).** Direct plans run alongside a trap when every check in 6.3 passes. Enable only after `FootprintConflictTest` and `InterleavingTest` pass, and only if the paired Phase 9 comparison shows a prespecified improvement over serialised Hybrid with all invariant gates clean.

### 6.5 Defence interaction

- [ ] Defensive opportunities are evaluated every tick, including during a trap.
- [ ] A defensive break of a crystal the trap protects is allowed only if the engine first invalidates the trap.
- [ ] Cleanup intents share budgets and cannot be starved: after 20 ticks waiting, cleanup gets the next free slot.

### Tests

- [ ] DirectOnly, PistonOnly and Hybrid pick as specified on scripted snapshots.
- [ ] Surrounded target where direct faceplace is still better → direct wins.
- [ ] Oscillating scores → no thrash under hysteresis.
- [ ] Missing piston materials → piston opportunities absent with a visible reason.
- [ ] Direct explosion reaching the trap crystal → crystal destroyed, no second explosion; trap aborts and its risk is retired only by evidence.
- [ ] Direct explosion intersecting power or piston cells → conflict.
- [ ] Explosion 8–12 blocks away pushing the target vertically out of a hole → conflict.
- [ ] Unrelated direct action whose search volume and motion bound both miss the trap → allowed concurrently.
- [ ] Mixed traces: interleave direct, piston, defence and cleanup over 1,000 randomised seeds; assert zero conflicting sends, no double slot ownership, no stale callbacks, and the risk budget never exceeded. These seeds test logic; they are not independent real encounters.

### Exit gate

- [ ] Serialised Hybrid: all tests pass and metrics record decision reasons.
- [ ] Footprint-scoped concurrency: tests pass and the paired comparison meets its prespecified threshold, or it stays off.

## Phase 7: Gated experiments

Each increment below (double push and optimistic presentation) is independently mergeable, off by default, and may never reach a validated preset. Run the complete regression suite after each one. Basic piston and direct effectiveness must never depend on them.

### 7.1 Head-height layouts and double push

**Files:** `DoublePushPlanner`, extend `PistonGeometry`, tests.

- [ ] Additional head-height layouts beyond Phase 5's eye-height set, each validated as a separate layout with its own measured pushed offsets.
- [ ] Perpendicular double push: two `PistonStage`s in one plan, with their own support and power dependencies, executed as a DAG.
- [ ] The two swept envelopes must not overlap a structural cell of the other stage.
- [ ] Partial push (first push observed, second fails) → abort, attack only if safe at the actual position, otherwise protect and clean up.
- [ ] Damage recomputed after each observed push.
- [ ] No triple push.
- [ ] Optional fire placement: off, and only after its own full safe-cycle fixture passes.
- [ ] Gate: ≥ 19/20 per declared double-push layout on the local server.

### 7.2 Optimistic presentation removal

**Files:** `OptimisticCrystalView`, mixin into crystal rendering and client collision, tests. Reference: Marlow's Crystal Optimizer (MIT) for the presentation approach; reimplement.

Three separate experiments, in this order, each with its own gate:

1. **Render hiding only.** After a verified attack send, stop rendering the crystal until RTT p99 + 5 ticks; on server removal drop the overlay; on expiry restore visuals and count a rollback. Collision, movement prediction and other modules see the crystal unchanged.
2. **Collision filtering (separate).** Hiding collision changes movement prediction and other modules' behaviour even while the ledger keeps the crystal. Needs its own desynchronisation and rollback tests, including movement prediction near the hidden crystal and interaction with Scaffold and Surround.
3. **Early cell reuse (separate).** Placing into the cell before removal is observed: at most 1 pending reuse per base, rejection counting and exponential backoff.

For all three:

- [ ] The overlay never changes ledger state or risk accounting.
- [ ] Never applied to piston-bound crystals.
- [ ] No duplicate entities, no ghost cell after rollback, connection close clears overlays.
- [ ] Render hiding must pass before collision filtering is attempted.

### 7.3 ID prediction: removed from this plan

No client-side precondition can establish an unseen entity's identity. No visible crystal holder nearby does not exclude unseen spawns, pending server activity or entity allocation outside tracking range; a quiet prior tick does not prove the next numeric ID; and there is no independent source for the UUID before spawn. The inspected attack handler also disconnects a player who attacks an item, experience orb or qualifying arrow, so a wrong guess can kick the user.

Speculative attacks therefore leave the hard-safety milestone and every profile that claims the invariants. They may be reconsidered only as a separate design with an explicitly weaker, user-visible contract, or with server-provided identity acknowledgement. The fast spawn-confirmed path remains the low-latency solution.

### Exit gate for each increment

- [ ] Its own tests pass; the full regression suite passes.
- [ ] Off in every preset until Phase 9 shows a measured benefit and no acceptance problems.

## Phase 8: Module, UI and lifecycle

**Files:** `modules/CrystalCombat`, `render/CrystalCombatRender`, `CrystalCombatModuleTest`; register in `TntCoatingAddon`.

### Settings groups

| Group | Settings |
| --- | --- |
| General | Mode (DirectOnly / PistonOnly / Hybrid), Profile (Conservative / Anarchy / Custom), target range, friend handling |
| Safety | Min target damage, max self damage, remaining-health margin, max friend damage, anti-suicide (locked on), grouped bound (gated), statistical quiet window (Anarchy only, labelled), debt display |
| Direct | Place range, attack range, walls range, per-tick caps, faceplace threshold, armour threshold, own-only, support placement |
| Timing | Prediction ticks (target, self), hurt-sync on/off |
| Piston | Enabled layouts, power kinds, max structural blocks, cleanup policy (mine piston y/n) |
| Defence | Dangerous-crystal break, anti-piston blocker, Speedmine cooperation |
| Pauses | Eating, mining, screens, Baritone, health floor, lag pause |
| Inventory | Switch mode (hotbar only / lending), switch delay, restore slot |
| Experimental | Double push, render hiding, collision filtering, early reuse (each with a warning line) |
| Render | Plan outline, pushed-position marker, damage label with confidence |

Profile changes invalidate queued unsent intents. They never clear hazards or sent risk.

### Lifecycle

- [ ] Extend the 2d lifecycle to every mode, profile and experimental setting; no new lifecycle rules here.
- [ ] Profile switching between Conservative and Anarchy shows what changes, including when the statistical quiet window turns on.
- [ ] Render reads state only; render callbacks never progress any sequence.
- [ ] Render shows outstanding risk debt and suspension causes.

### Inventory lending (gated)

- [ ] Main-inventory lending only after dedicated tests for cursor items, open containers, manual selection during a loan, and offhand priority all pass.

### Exit gate

- [ ] Module tests pass; full `./gradlew test` and compile pass.
- [ ] Disable at every direct, piston and cleanup phase verified to send nothing afterwards.

## Phase 9: Validation and release preparation

This phase produces the evidence. Each live step needs its own authorisation: local test server first, then any public server only with explicit permission for that session.

### 9.1 Build checks

- [ ] `./gradlew clean build`, `python3 scripts/test_check_docs.py`, `python3 scripts/check_docs.py` in `26.2/`.
- [ ] Inspect the JAR for mixin registration and capability metadata; confirm the source ZIP and notices match.

### 9.2 Matched comparisons (local server)

Compare under identical terrain, equipment, scripted target programme and seed, at 80 and 200 ms RTT (stated as RTT) and server TPS 20, 15 and 10. Each comparison is paired: the same scenario runs once with the baseline and once with the treatment, from an independent reset.

1. Meteor CrystalAura vs CrystalCombat DirectOnly baseline (Phase 3 milestone)
2. DirectOnly baseline vs + each Phase 4 tactic individually
3. DirectOnly vs PistonOnly on holed targets
4. DirectOnly vs serialised Hybrid
5. Serialised Hybrid vs footprint-scoped Hybrid
6. Baseline vs each Phase 7 experiment individually
7. Conservative vs Anarchy statistical quiet window (report the miss rate)
8. Optionally, a closed client you hold a licence for, as a black-box opponent only

Protocol: randomise AB/BA order per pair; keep failed runs; separate tuning seeds from evaluation seeds; predeclare the primary metric, the practically meaningful improvement and the stopping rule before evaluation. Log authoritative attack acceptance, explosions, health changes and server ticks on the server side; client-side totem timing alone cannot attribute third-party damage. Report:

- Primary: paired treatment-minus-baseline effect on observed target damage per 20 ticks, with its confidence interval
- Time to first observed target damage; totem pops per minute (server-attributed)
- Self damage taken; invariant violations (must be zero)
- Packets per second, rejections, rollbacks, debt and suspension time
- Latency service objectives and combat tick time p50/p95/p99

### 9.3 Performance

- [ ] The 1 ms budget covers **all combat work in a client tick**: queue drain, applied-revision stamping, snapshot capture, event-triggered passes, validation and every planner pass. Per-pass candidate caps alone cannot bound an event storm.
- [ ] A shared deadline and work counter; coalesced invalidations; optional planning deferred when reconciliation uses the budget. Reconciliation itself is never skipped.
- [ ] Event-storm test (50+ spawns and block updates in one tick) stays within budget or degrades by deferring optional work only.
- [ ] Profile whole-client runs with Java Flight Recorder on CachyOsPC before choosing any optimisation. Report allocation, frame-time tails and cache hit rates.
- [ ] Reject any speedup that reduces correctness or observed outcomes.

### 9.4 Live observation (only with explicit authorisation)

- [ ] Record acceptance of placements and attacks, push behaviour and damage on the target server, with real conditions and sample sizes.
- [ ] Documentation separates locally validated, live-observed and experimental behaviour. No "best client" claim.

### 9.5 Docs and release artefacts

- [ ] `docs/modules/crystal-combat.md`, `docs/validation/crystal-combat.md`, README index, CHANGELOG, THIRD\_PARTY\_NOTICES.
- [ ] Publishing is a separate decision.

## Phase 10: Version ports

Port only after 26.2 passes Phase 9.

| Tree | Java | Meteor | Notes |
| --- | --- | --- | --- |
| `26.1.2/` | 25 | build 42 | Re-audit Meteor APIs; rerun Phase 0 traces |
| `1.21.11/` | 21 | vendored build 82 | Audit build 82 independently; never infer from build 86 caches |

For each tree:

- [ ] Carry pure `core/combat` code and tests unchanged where possible.
- [ ] Re-implement `mc/*` adapters, mixins and capability checks against that tree's pinned APIs.
- [ ] Rerun Phase 0 recording and replay tests; confirm `W`, push offsets and event order.
- [ ] Repeat direct, piston, callback, cleanup and ownership validation, plus each advanced feature claimed for that version.
- [ ] Mark any unvalidated feature/version combination unavailable in validated profiles.

## Defaults and rationale

Every number here is a starting point to measure in Phase 9, not an established optimum. Health values are in health points (2 per heart).

| Setting | Conservative | Anarchy | Rationale |
| --- | --- | --- | --- |
| Mode | Hybrid | Hybrid | Hybrid falls back to direct when no trap is better |
| Target range | 6 | 6 | Covers place range plus target movement |
| Place range | 4.5 | 4.5 | Inside vanilla block interaction reach |
| Attack range | 3.0 | 3.0 | Inside vanilla entity reach; execution still checks real reach |
| Walls range | 3.0 | 3.0 | Avoids through-wall attempts most servers reject |
| Min target damage | 4 | 4 | Two hearts; below this a crystal rarely pressures a geared player |
| Max self damage per crystal | 6 | 6 | Per-opportunity ceiling; the summed risk budget handles accumulation |
| Remaining-health margin | > 2 | > 2 | Leaves one heart against outside damage between observations |
| Max friend damage | 2 | 2 | Per friend, independent budgets |
| Faceplace health | 8 | 8 | Four hearts including absorption |
| Faceplace armour | ≤ 10% | ≤ 10% | Pressures armour near breakage; it does not reduce mitigation before the piece breaks |
| Places per tick | 1 | 2 | Anarchy allows doubling where the server accepts it |
| Verified breaks per tick | 1 | 2 | Same |
| Rolling packet budget | 60 / 20 ticks | 100 / 20 ticks | See arithmetic below; Anarchy lowers on rejection |
| Rotations | On | On | Off only by explicit user choice |
| Target prediction | 2 ticks | 3 ticks | Typical RTT range on EU servers |
| Self prediction | 1 tick | 1 tick | Worst of current and predicted |
| Hurt-sync | Off | On | Needs Phase 9 evidence before Conservative |
| Switch hysteresis | 20% for 3 ticks | 20% for 3 ticks | Prevents thrash between near-equal plans |
| Max attempts per crystal | 2 | 2 | Limits wasted packets; risk from earlier attempts is kept |
| Piston stage timeout | max(20, RTT p99 + 5) ticks | same | Long enough for a round trip and a push |
| Trap deadline | 100 ticks | 100 ticks | Five seconds before giving up |
| Cleanup deadline | 40 ticks | 40 ticks | Two seconds |
| Structural retries | 2 | 2 | Never for crystal placements |
| Max new structural blocks | 6 | 6 | Bounds build time and material use |
| Support depth | 2 | 2 | Same |
| Combat work budget (all work per tick) | 1 ms / tick | 1 ms / tick | On CachyOsPC; adjust from JFR measurements |
| Candidate caps | 128 direct, 64 piston | same | Bounded search |
| Zero-damage retirement | Processing barrier, else debt | same | Anarchy may opt into a labelled statistical quiet window (RTT p99 + jitter p99 + 2 ticks); percentiles do not bound the tail |
| Experiments | All off | All off | Must earn their place in Phase 9 |

Aggressive reach, rotations off and every experiment need an explicit per-setting opt-in. Nothing is enabled automatically based on server name.

### Packet budget arithmetic

One full direct cycle is roughly:

| Packet | Count |
| --- | --- |
| Place interaction | 1 |
| Swing for place | 1 |
| Attack | 1 |
| Swing for attack | 1 |
| Slot switch to crystals and back (when needed) | 0–2 |
| **Total counted** | **4–6** |

Rotation updates ride on movement packets and are not counted here.

So Conservative's 60 packets per 20 ticks allows about 10–15 crystal cycles per second, and Anarchy's 100 allows about 16–25. A tick cap of 1 place and 1 break limits Conservative to 20 cycles per second anyway, so the budget, not the tick cap, is the usual limit. Defence, piston and cleanup actions draw from the same budget.

If Phase 9 shows the budget is the bottleneck without rejections, raise it in steps of 10 and remeasure.

## Test matrix and acceptance gates

Four tiers, each required before the next: pure unit tests, trace replay, pinned-Minecraft integration tests, and authorised local-server scenarios. Live public-server observation is a fifth, optional tier.

### Scenario matrix

| Dimension | Values |
| --- | --- |
| Target state | Static open, moving open, holed, full surround, partial surround |
| Target defence | No armour, full netherite + Blast Protection IV, Resistance I/II, absorption 4/16; armour breaking between hits; Resistance expiring mid-sequence |
| Target hurt state | Fresh, hidden prior hurt, stronger and weaker follow-ups |
| Friends | None, adjacent to target, adjacent to self; health KNOWN, UNKNOWN, CENSORED |
| Self state | Full health, low health, absorption, Weakness, eating, screen open; death and respawn in the same world |
| Network | 80 and 200 ms RTT; ±30 ms jitter; delays beyond the p99 tail |
| Server | TPS 20, 15, 10; a stall longer than 40 client ticks |
| Observation order | In order, duplicated, delayed, out of order, split absorption/health, missing health packet, tracking removal |
| Explosion semantics | Crystal destroyed by explosion without a second explosion; vertical knockback out of a hole; intermediate swept-path exposure |
| Dispatch | Failure after each packet step; delayed Meteor callbacks after disable |
| Piston | 4 directions × feet/eye × torch/redstone block |
| Ownership | Contested cleanup, inferred-only ownership |
| Load | Event storms (50+ spawns and updates in one tick) |
| Mixed | Direct + piston + defence + cleanup interleaved; several individually safe but cumulatively lethal attacks before a delayed health update |
| Experiments | Render-hiding rollback, collision desync, reuse rejection |

### Invariant gates (zero tolerance)

These must be exactly zero across every tier and every run:

- Wrong-entity attacks (no speculative path exists)
- Policy violations (self, friend or margin breach caused by module-issued explosions) in Conservative
- Risk retired by a timeout in Conservative
- Sends after disable, death, connection change or config change
- Concurrent sends from Meteor CrystalAura while CrystalCombat is enabled
- Double slot or rotation ownership
- Automatic mining of a contested cell

### Success-rate gates

A small sample cannot show high reliability. Using exact (Clopper–Pearson) two-sided 95% intervals, 19 of 20 successes gives a lower bound of 75.13% and 48 of 50 gives 86.29%. Neither establishes a 95% true-success floor. For invariant gates, zero failures in 100 independent identical trials bounds the failure rate at about 2.95% (one-sided 95%), and 1,000 at about 0.30%. These are calculated bounds, not measured reliability, and randomised trace seeds are not independent real encounters. Choose the method and sidedness before each evaluation.

| Gate | Sample | Pass | What it shows |
| --- | --- | --- | --- |
| Smoke gate per declared piston layout | 20 | ≥ 19 | Works at all; two-sided 95% lower bound about 75% |
| Default-preset piston layouts | 50 | ≥ 48 | Two-sided 95% lower bound about 86% |
| Direct verified cycles | 100 consecutive | 100 invariant-clean | Failure rate below about 2.95% (one-sided 95%) |
| Double push per layout | 20 | ≥ 19 | Same as smoke gate |

State the sample size, method and sidedness alongside every rate.

### Ablation rules

- Report the paired treatment-minus-baseline effect and its confidence interval over independently reset scenario runs. Overlap between two separate intervals is not a test of the paired difference.
- Promote a feature only when the interval supports a prespecified practically meaningful improvement, every invariant gate passes, and secondary regressions stay within predeclared limits.
- Pair terrain, target programme and seed; randomise AB/BA order; keep failed runs; separate tuning seeds from evaluation seeds; predeclare stopping rules.
- A feature that adds any invariant violation is excluded regardless of benefit.

## Risks, open questions and decisions

### Risks

| Risk | Effect | Mitigation |
| --- | --- | --- |
| No processing barrier exists in 26.2 | Zero-damage hits leave debt; Conservative throughput drops in long fights | Debt blocks only margin-crossing sends and is visible; Anarchy may opt into the labelled statistical rule |
| Grouping obligations cannot be proven | Summed bound throttles bursts | Accept the summed bound; Conservative stays safe and slower |
| Cached artifacts differ from publisher releases | Audit findings wrong | Hash every artifact; compare with publisher checksums where available |
| Meteor 26.2 internals change before release | Suppression mixin breaks | Descriptor capability check refuses activation; pin build |
| Hybrid rarely beats DirectOnly | Piston complexity without benefit | Paired Phase 9 comparison decides; DirectOnly stays default if so |
| Footprint checks too conservative | Concurrency rarely enabled | Measure conflict rejections by reason; stay serialised |
| Footprint checks too permissive | Direct explosion destroys trap crystal or moves target | Serialise when compatibility cannot be established; interleaving tests |
| Ledger ambiguity in crowded fights | Quarantines block traps | Count quarantines; tighten source box only if traces justify it |
| Ownership cannot be proven in vanilla | Cleanup is mostly manual | Show manual cleanup clearly; inferred cleanup is an explicit opt-in |
| Combat work exceeds 1 ms in event storms | Frame drops | Shared deadline, coalescing, deferral; JFR before optimising |
| Server anticheat rejects patterns | Rejections, setbacks | Backoff; Conservative profile; no automatic bypass claims |
| Provenance error | Licence violation in a GPL release | Phase 0 pins; per-file mode headers; review at each checkpoint |

### Open questions

- [ ] Does a block-change acknowledgement provide a reliable processing barrier relative to health sync? (Phase 0; decides zero-damage retirement.)
- [ ] Exact 26.2 immunity window, raw-before-mitigation order, and cooldown-bypassing damage types (Phase 0).
- [ ] Do absorption and health from one damage event share a bundle? (Phase 0.)
- [ ] Crystal-hit-by-explosion and vertical knockback behaviour on the target server type, not just vanilla (Phase 0/9).
- [ ] Measured piston push offsets and durations per direction and height (Phase 0).
- [ ] Which BlackOut fork the pinned commit comes from, and THM's lineage (Phase 0).
- [ ] Can `InventorySlotLoan` gain a side-effect-free query, and Speedmine an owner-scoped cancel? (Phase 2b.)
- [ ] Whether you hold a licence for any closed client to use as a black-box baseline (Phase 9, optional).
- [ ] Final user-facing names for modes and profiles (Phase 8).

### Decision log

| Date | Decision | Reason |
| --- | --- | --- |
| 2026-10-28 | Single `CrystalCombat` module with three modes over a shared engine | One owner for packets, slots and risk |
| 2026-10-28 | Risk resolution uses event + quiet window, not health packets alone (superseded in v2.1) | v1 could deadlock on zero-damage hits |
| 2026-10-28 | Grouped immunity bound with top-two per close-arrival group (v2.1: gated, summed first) | Sound with outside hits; less throttling on bursts |
| 2026-10-28 | Phase 0 trace recording before foundations | Build models against observed 26.2 behaviour |
| 2026-10-28 | Split v1 Task 4 into 2a/2b/2c | Separate review of pure logic, adapters and Meteor suppression |
| 2026-10-28 | Footprint-scoped concurrency is core Hybrid work | Serialised Hybrid loses direct pressure during traps |
| 2026-10-28 | Anti-piston blocking added to defence | Opponents will piston too; GameSense Blocker precedent |
| 2026-10-28 | ID prediction narrowed to no-other-crystal-actor conditions (superseded in v2.1: removed) | v1 bound was nearly always unsatisfiable |
| 2026-10-28 | Cracked, decompiled and leaked clients excluded entirely | Licence and GPL provenance |
| 2026-10-28 | 48/50 gate for default piston layouts | 19/20 only bounds success at about 75% |
| 2026-10-28 | v2.1: timeouts never retire risk; retirement needs terminal evidence and a coherent snapshot | Technical review: percentiles do not bound the tail; stalls break timeouts |
| 2026-10-28 | Conservative keeps hard-guarantee debt; Anarchy may opt into a labelled statistical quiet window | Chosen by the user; keeps one profile with a true guarantee |
| 2026-10-28 | Summed bound first; grouped bound gated on proof obligations | Grouping is a mathematical inference, not yet a gameplay proof |
| 2026-10-28 | Reserve risk at PLACE\_CRYSTAL | Others can detonate our crystal before we attack it |
| 2026-10-28 | ID prediction removed from the plan | No client precondition can prove unseen identity; wrong targets can disconnect |
| 2026-10-28 | Crystal hit by explosion: destroyed, no second explosion; 3D knockback, no hole exemption | Inspected 26.2 bytecode (to confirm in Phase 0) |
| 2026-10-28 | First usable milestone is DirectOnly with suppression, lifecycle, risk and metrics | Lifecycle and integration bugs surface earliest |
| 2026-10-28 | Hybrid ships serialised; footprint concurrency must win a paired comparison | Concurrency safety depends on geometry that must be proven |
| 2026-10-28 | Paired effects for ablation; 1 ms budget covers all combat work per tick | Interval overlap is not a paired test; per-pass caps miss event storms |

Numeric defaults, profile names and delivery order are recommendations awaiting your approval.
