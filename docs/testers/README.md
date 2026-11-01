# CrystalCombat tester build 3

This is the third **tester build** of CrystalCombat (`tester.3`). It is not a release build. It exists
so testers can exercise the audited DirectOnly baseline, the **beta PistonOnly** single-push trap and
the serialised Hybrid mode on real servers, and send back evidence. The suggested testing order is in
[`tester-3-handout.md`](tester-3-handout.md).

**Minecraft 26.1.2 edition.** This branch is Minecraft 26.1.2 with Meteor Client
**26.1.2-20260810.135545-42** (build 42). `motion-prediction` and `hurt-sync` are **not available on
26.1.2**: they stay visible in the Timing group but enabling either refuses activation, because the
port audit records the stronger-hit knockback/motion behaviour as unconfirmed here. Do not report those
two settings as bugs — test everything else.

> **Beta warning:** test only on servers where losing gear is acceptable. CrystalCombat can misplace,
> break or attack a crystal, spend pots, or leave you standing in an explosion. The piston trap is
> beta: it can leave blocks and crystals in the world, and **cleanup is manual by default**. The beta
> **auto-cleanup** switch can remove only the trap's own power, piston and supports; it is off by
> default and never touches contested or pre-existing blocks. Do not take it to a server, fight or kit
> you are not prepared to lose.

## Sending your report

Send reports **directly to the developer** — not to a public issue tracker, public chat or Discord.
Use the same private channel that delivered this build.

1. Turn on **debug-log** (Diagnostics) before you test.
2. In game, run `.crystalcombat export confirm`. This writes
   `comet/exports/crystalcombat-<timestamp>.zip` under the game directory and hashes other players'
   names and UUIDs unless you opted into identities. Keep that zip intact.
3. Fill in [`feedback-template.md`](feedback-template.md).
4. Copy the filled-in template into the game directory, next to the `comet` folder, then zip
   `comet/exports/` together with the template. **Keep the in-game export zip inside**; do not replace
   it with its extracted files.
5. Rename the report zip with your tester name and date, for example
   `crystalcombat-report-<name>-2026-11-01.zip`.
6. Send the report zip to the developer.

Find the game directory that contains `mods/` and `config/`; the export is written under it:

| OS | Export folder |
| --- | --- |
| Windows | `%APPDATA%\.minecraft\comet\exports\` |
| macOS | `~/Library/Application Support/minecraft/comet/exports/` |
| Linux | `~/.minecraft/comet/exports/` |

Custom launchers (Prism, MultiMC, CurseForge, Modrinth, ATLauncher) use their own instance game
directory. The export is inside whichever folder contains that instance's `mods/` and `config/`.

How to zip:

- **Windows:** select the `comet` folder (it contains `exports/`) and the filled-in template, right-click
  → **Send to** → **Compressed (zipped) folder**.
- **macOS:** select the `comet` folder (it contains `exports/`) and the filled-in template, right-click →
  **Compress**.
- **Linux:** from the game directory, run
  `zip -r crystalcombat-report.zip comet/exports feedback-template.md`.

The developer stores received reports in this repository under
`testers/reports/<round>/<tester>/`, where `<round>` is the tester-build round and `<tester>` is your
name. For this build the round is `tester-3`, so reports go under
`testers/reports/tester-3/<tester>/`. That directory is the input to the Phase 9 triage process.
Testers never need to commit anything themselves.

## What is in this build

- **All three modes can be selected: DirectOnly, PistonOnly (beta) and Hybrid (serialised).**
  DirectOnly is the production baseline: the module places obsidian, places an end crystal, breaks it
  and attacks it through the single audited `CombatRuntime.dispatch` path. PistonOnly builds the measured
  16-combination single-push trap block by block, confirms every placed block, waits for the piston to
  extend, and attacks only at the computed final crystal position. Hybrid ranks direct plans and the
  trap against each other; selecting it no longer refuses to enable.
- **Serialised Hybrid.** At most one plan owns the field: a committed or live trap blocks every direct
  plan until it ends or aborts, and defence evaluation continues during a trap. A direct tactic with
  higher ranked utility can win over an **uncommitted** trap preview; a committed trap still
  serialises. Hybrid changes which plan is chosen, never how a packet is sent. The experimental
  `footprint-concurrency` path is off by default and is not promoted.
- **The direct engine does not run in PistonOnly, and the trap does not run in DirectOnly.** Each mode
  uses the existing single tick and send path; switching modes mid-session aborts unsent work, keeps
  hazards and already-sent risk, and sends no stale packet from the mode being left.
- **Piston trap materials are checked before reserving.** With any missing material — pistons, end
  crystals, obsidian, or the power item of at least one enabled layout, and a chosen plan additionally
  re-checks its own power item — the trap creates no opportunity and shows
  `piston trap: missing materials: ...` in the info string instead of reserving and failing at
  dispatch.
- **Beta top-level switch.** **piston-trap** is on by default but only arms while **Mode** is
  `PistonOnly` or `Hybrid` (Hybrid only while a trap is selected/committed), and never in
  `DirectOnly`. It never creates an independent hidden mode.
- **Sixteen beta layout toggles.** Each direction (east/west/north/south) × height (feet/eye) × power
  (redstone torch/redstone block) has its own on/off setting, all enabled by default. A disabled layout
  is never planned. **Disabling all sixteen leaves PistonOnly inert** (no trap, no direct fallback); the info
  string reports the wait reason instead of silently switching behaviour.
- **Piston trace and vertical-push hardening.** All sixteen layouts have recorded-trace replay
  coverage. The self bound prices the swept path's mid-point as well as its endpoints, and a
  vertical-push abort during POWER/WAIT_PUSH fires on a rise above 0.45 blocks/tick together with an
  explosion or a nearby non-plan block change. The abort sends nothing further and keeps the hazard
  and sent risk.
- **Beta automatic cleanup.** **auto-cleanup** (Piston, default off; description: *Beta: removes only
  the power, piston and supports this module placed and saw confirmed. Contested blocks are always
  left for you.*) removes only the trap's own confirmed cells through vanilla hotbar breaking on the
  dispatch path. It works power first, then piston, then supports, never detonates anything, never
  touches pre-existing obsidian, bedrock or contested blocks, and shares the packet budget and 20-tick
  starvation rule. It has a 40-tick deadline, after which the info string switches to manual cleanup
  listing the remaining blocks. Disable, death or a connection change stops it immediately. Obsidian
  supports usually take longer than the deadline, so expect manual cleanup even when the power and
  piston were removed. **Manual cleanup is the default**; with auto-cleanup off,
  the info string lists the exact blocks to remove, and `.crystalcombat cleanup confirm` releases the
  trap slot after you have removed them.
- **Settings re-grouped, with migration.** Settings now live in General, Safety, Direct, Timing,
  Piston, Defence, Pauses, Experimental, Render and Diagnostics (Diagnostics is a documented
  deviation). There is no Inventory group because inventory lending is deferred. `CrystalCombat`
  migrates values saved in the old General and Ranges groups into the new groups when it loads, so an
  upgrading tester keeps their settings.
- **Conservative and Custom profiles.** Conservative is the default and sets min target damage 4, max
  self damage 6, remaining-health margin 2 and max friend damage 2; it re-applies those values when
  selected. Custom keeps your values, but the same conservative safety floor is still enforced, so
  weaker values refuse to enable. The plan's third "Anarchy" profile is not offered, and the
  statistical quiet window is superseded and disabled.
- **Anti-suicide is locked on.** Turning it off disables the module immediately.
- **Risk debt and suspension HUD.** **show-risk-debt** (Render group, default on) is covered below.
- **No full combat UI.** The module's normal Meteor settings screen shows its settings; there is no
  separate piston screen and no in-game combat UI beyond the debt/suspension text. Defence tactics
  are present but default off.
- Diagnostics are opt-in: the **Debug log** is off by default and keeps evidence in memory only.

## Risk models and the debt display

**risk-model** is in the **Safety** group. It decides when a crystal that might still hurt you stops
counting:

- **Strict** (the default): keeps full debt until a causally later coherent health update proves the
  crystal harmless; a respawn or connection change closes the session. It is the safest model and can
  slow the aura in long fights.
- **Barrier**: Strict plus one side-effect-free check packet after our explosion is applied. The
  packet uses the real vanilla prediction sequence; harmless reservations (no health evidence at or
  after the explosion) retire after the server's block-change acknowledgement plus one server tick.
  It is confirmed only on a local vanilla server. On proxies, anti-cheat or other server software,
  treat it as unverified and use Strict. Only Barrier sends this probe.
- **Meteor-style**: retires a reservation as soon as the crystal's explosion or removal is observed,
  while crystals sent but not yet exploded still count. It is the fastest model and the weakest when
  several hits land before a health update.

Only Barrier sends an extra packet; Strict and Meteor-style send no probe. The active model is shown
in the module's info string as `[risk: strict]`, `[risk: barrier]` or `[risk: meteor-style]`, and in
the debug-log header as `risk-model=<key>`.

**show-risk-debt** (Render, on by default) draws the published snapshot in the top-left corner:
`risk debt: N outstanding (bound X)` and, when risk is suspended, `suspended: <cause>`. It is
read-only, fed by a snapshot from the client tick, shows nothing while the module is disabled or when
there is no debt or suspension, and clears when you leave the world.

## Computed position, not a server-confirmed position

The trap does not see the server's crystal movement: the client suppresses crystal position sync
(trace fact C21). The final crystal position is therefore **computed** from the audited push rules
(two extension ticks, 0.51 blocks per tick, measured 1.02 total) plus the observed piston block
events. It is **not confirmed by the server**. With the debug log on, the build records the computed
position, the client's last observed crystal position, and either a "stale/no update" diagnostic
(the normal C21 case) or a disagreement incident only when a genuine client position update exists
and differs by more than 0.05 blocks.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.1.2 |
| Meteor Client | **26.1.2-20260810.135545-42** (build 42) |
| Fabric Loader | 0.19.3 or newer |
| Java | 25 |

Use the exact Meteor build above. A different Meteor build can change event order, rotation
behaviour or packet APIs, and evidence from it will not be comparable.

## Install

1. Install Fabric Loader 0.19.3+ for Minecraft 26.1.2 and a Java 25 runtime.
2. Put Meteor Client **26.1.2-20260810.135545-42** in `mods/`.
3. Put `comet-0.27.0-tester.3-mc26.1.2.jar` from `build/libs/` in `mods/` as well.
4. **Keep the matching
   `build/distributions/comet-0.27.0-tester.3-mc26.1.2-source.zip` with the jar.** Comet's distributed jar
   is GPL-3.0-only and the source archive is the corresponding source for exactly this tested build; it
   must travel with the jar. Both files are produced by the same build and the source zip contains
   `src/` and `THIRD_PARTY_NOTICES.md`.
5. Launch the game, open Meteor's module list and enable `CrystalCombat` under **Comet**.

The module's info string shows the active risk model and then a build bracket, for example
`[risk: strict] [v0.27.0-tester.3 <short commit> tester.3]`: the first bracket is the model, the
second carries the version, short commit and tester label. A `-dirty` commit means the build was
made from an uncommitted working tree; the export's `build.txt` records the same value. Please
include that version line in feedback.

## PistonOnly beta checklist

Start with `Mode = PistonOnly`, **piston-trap** on, and all sixteen layouts on. Use a private test
world or an anarchy server where block loss is acceptable, and keep to one trap at a time.

- One trap at a time; a second opportunity waits until the active trap ends or you reconcile it.
- After manually removing the debris, run `.crystalcombat cleanup confirm` to release the trap slot.
  The command sends no packet and keeps the ledger hazards and sent risk as debt. Without it, PistonOnly
  stays inert with `manual cleanup required` in the info string.
- **Optional beta auto-cleanup:** turn on **auto-cleanup** (off by default) and watch the trap remove its
  own power, piston and supports after the crystal is resolved. It is deliberately narrow: only cells
  this trap placed and saw confirmed are broken, power first, then the piston, then its supports;
  contested, changed and pre-existing blocks are never touched. It uses a hotbar pickaxe for the piston
  and obsidian (never the offhand); if the tool a cell needs is missing it sends nothing for that cell
  and ends in manual cleanup, while a torch cell needs no tool and can still be removed. Obsidian
  supports usually take longer than the 40-tick cleanup deadline, so expect
  `manual cleanup required` with the remaining blocks even when the power and piston were removed.
  Disable, death, respawn, a mode switch or a connection change stops it immediately, cancels the
  destruction it started and sends no further cleanup packet.
- Support → piston → crystal → power order, each block confirmed before the next stage.
- The piston faces the target; the power block lands only after a unique crystal spawn binds.
- No attack before the authoritative piston extension events; exactly one swing.
- No packet after disable, death, disconnect or a mode switch; the ledger and risk stay occupied.
- Wrong facing, early power, ambiguous/foreign spawn or deadline expiry produce no further packets and
  leave **manual cleanup** visible.
- Record the chosen layout and the computed final position from the debug log.

## Debug log and incidents

Open `CrystalCombat`'s **Diagnostics** settings group:

- **Debug log** — off by default. When on, the last **five minutes** (up to 20,000 JSON lines) of the
  audited observations and incident markers are kept in a rolling in-memory buffer. Nothing is written
  to disk unless you export. The piston trap records its chosen layout, every stage transition, the
  computed final crystal position, the client-position cross-check, every block confirmation and the
  reason for every abort. These lines are diagnostics only: they never send or change a decision.
- **Incident marker key** — an optional hotkey that writes a `something went wrong here` marker into
  the debug log while it is running.
- **Export include identities** — off by default; see privacy below.

With the debug log on, the build records these incidents automatically:

- self death while CrystalCombat is enabled;
- self damage above the predicted upper bound, with the damage and bound values;
- risk debt that stays outstanding for more than 30 seconds;
- every risk-suspension change with its causes;
- a failed dispatch that indicates a broken send-path invariant;
- the manual marker from the hotkey;
- **piston-trap aborted after the POWER stage**;
- **manual cleanup required** (entering `MANUAL_CLEANUP`, for example on disable or a deadline);
- **computed/client crystal position disagreement** beyond the 0.05 tolerance — only when a real
  client position update exists; a suppressed or stale client position (C21) is a diagnostic, not an
  incident;
- **attack sent at the computed position when no crystal was then observed**;
- **block-confirmation timeout or wrong block/facing**.

Incident markers are diagnostics only. They never send, place, attack or change risk.

## Exporting evidence

Run these in chat:

```text
.crystalcombat export
.crystalcombat export confirm
```

The first command prints the exact archive plan and **writes nothing**. The second writes
`comet/exports/crystalcombat-<timestamp>.zip` under your game directory and reports the path.

The zip contains:

| File | Contents |
| --- | --- |
| `manifest.json` | format, build stamp, entry sizes and privacy mode |
| `build.txt` | Comet version, git commit, tester suffix, Minecraft and Meteor versions |
| `settings.json` | the current CrystalCombat settings, redacted |
| `debug-log.jsonl` | the rolling debug log, redacted |
| `THIRD_PARTY_NOTICES.md` | the third-party licence notices for this build |

### Privacy

By default, other players' names and every UUID are replaced with a **stable per-export salted
SHA-256 pseudonym**. The salt is generated for that export and never written into the archive, so
pseudonyms cannot be correlated between exports. **Chat and server addresses are never included**,
even if you turn identities on. Enabling **Export include identities** keeps real names and UUIDs but
still removes chat and addresses; leave it off unless a maintainer asks for identities.

Review the preview before `confirm`, and do not attach an export publicly if you enabled identities.

## Known limitations

- DirectOnly, beta PistonOnly and serialised Hybrid with Conservative or Custom and locked anti-suicide
  are the accepted configurations; the plan's third profile is not offered, not silently approximated,
  and the statistical quiet window is superseded and disabled. Hybrid changes which plan is chosen, not
  how a packet is sent.
- The computed final crystal position is not server-confirmed. C21 suppresses crystal position sync;
  the only evidence is the piston block events plus the audited numeric push rules.
- **Manual cleanup is the default.** With **auto-cleanup** off, the trap does not mine, break or
  otherwise clean up its supports, piston, power block or any crystal after a trap ends, aborts, times
  out, or when you disable the module. Leave it and remove the blocks yourself only when safe; the trap
  slot stays occupied until reconciliation so other traps cannot start over the same debris. Run
  `.crystalcombat cleanup confirm` after removing the debris; it releases only the trap slot, sends no
  packets, and keeps hazards and sent risk as debt. With **auto-cleanup** on, the trap breaks only the
  cells it placed and saw confirmed (power, piston, supports), never contested or pre-existing blocks,
  and the same `.crystalcombat cleanup confirm` still releases the slot and keeps the debt.
- **Beta auto-cleanup frequently ends in manual cleanup for obsidian supports**, because vanilla break
  time exceeds the 40-tick deadline. Expect to remove obsidian by hand even when the power and piston
  were cleaned up.
- **Barrier's check-packet behaviour is confirmed only on a local vanilla server.** Proxies, anti-cheat
  and other server software are unverified; use Strict there. Strict and Meteor-style send no extra
  packet. The ack-based retirement described above is used only by Barrier.
- Each of the sixteen layouts is beta and not yet server-confirmed. "All 16 measured" means the
  audited local traces agree on the 1.02 push; it does not mean every server behaves identically.
- There is no live module-level `buildStack` test (a source guard covers the trap/mode wiring); a
  real-client item is in the checklist and the controller/mode seams are unit-tested.
- **Inventory lending is deferred and not built.** The Inventory settings group and the lending path
  are absent from this build.
- Head-height/double-push trap handling, render hiding, collision filtering and early reuse remain
  gated and are not shipped.
- Phase 4 experimental tactics and both defence types are present but default off; the full combat UI
  is absent by design.
- Hurt-aware timing has no production raw-hit feed in this build, so turning it on does not change
  behaviour yet.
- Metrics exist in the runtime but have no UI surface in this build; most Phase 4.7 counters stay
  zero.
- The debug log is a five-minute in-memory ring; a crash loses it. Export before closing the game.
- No Minecraft client or server was launched to validate this build itself; the live checks are the
  tester items in this README and the checklist.
- This build does not update itself, does not download anything, and does not send telemetry.

## Checklist and feedback

- Work through [`checklist.md`](checklist.md): the **tester build 3 additions** section covers the risk
  models, debt display, auto-cleanup and settings migration; the **P5.2 tester build 2** section covers
  the per-layout matrix and the mode/target/death/disable scenarios; the earlier build 1 section still
  covers the DirectOnly baseline.
- Read [`tester-3-handout.md`](tester-3-handout.md) for the suggested order: DirectOnly, then
  PistonOnly beta, then Hybrid.
- Report with [`feedback-template.md`](feedback-template.md) and send the zip as described under
  **Sending your report**.
- Keep the jar and source zip together when sharing a build for testing.
