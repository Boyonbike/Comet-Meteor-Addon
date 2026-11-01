# CrystalCombat tester build 2

This is the second **tester build** of CrystalCombat (P5.2). It is not a release build. It exists so
testers can exercise the audited DirectOnly baseline and the new **beta PistonOnly** single-push trap
on real servers and send back evidence.

> **Beta warning:** test only on servers where losing gear is acceptable. CrystalCombat can misplace,
> break or attack a crystal, spend pots, or leave you standing in an explosion. The piston trap is
> beta: it can leave blocks and crystals in the world, and **cleanup is manual**. Do not take it to a
> server, fight or kit you are not prepared to lose.

## What is in this build

- **DirectOnly (production baseline).** The module can place obsidian, place an end crystal, break it
  and attack it through the single audited `CombatRuntime.dispatch` path.
- **PistonOnly (beta tester mode).** Select `PistonOnly` in **Mode** to run the single-push piston
  trap instead of the direct engine. It builds the measured 16-combination layouts block by block,
  confirms every placed block, waits for the piston to extend, and attacks only at the computed final
  crystal position. **Hybrid is still not in this build** and refuses to enable.
- **The direct engine does not run in PistonOnly, and the trap does not run in DirectOnly.** Each
  mode uses the existing single tick and send path; switching modes mid-session aborts unsent work,
  keeps hazards and already-sent risk, and sends no stale packet from the mode being left.
- **Beta top-level switch.** **piston-trap** is on by default but only arms while **Mode** is
  `PistonOnly`. It never creates an independent hidden mode.
- **Sixteen beta layout toggles.** Each direction (east/west/north/south) × height (feet/eye) × power
  (torch/redstone) has its own on/off setting, all enabled by default. A disabled layout is never
  planned. **Disabling all sixteen leaves PistonOnly inert** (no trap, no direct fallback); the info
  string reports the wait reason instead of silently switching behaviour.
- **Conservative profile only.** Min target damage 4, max self damage 6, remaining-health margin 2 and
  friend damage 2 are the production floor. Weaker values refuse to enable.
- **Anti-suicide is locked on.** Turning it off disables the module immediately.
- **No defence logic, no full combat UI, no automatic cleanup.** The module's normal Meteor settings
  screen shows its settings; there is no separate piston screen and no cleanup/mining packet path.
- Diagnostics are opt-in: the **Debug log** is off by default and keeps evidence in memory only.

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
| Minecraft | 26.2 |
| Meteor Client | **26.2-20261009.074120-35** (build 35) |
| Fabric Loader | 0.19.3 or newer |
| Java | 25 |

Use the exact Meteor build above. A different Meteor build can change event order, rotation
behaviour or packet APIs, and evidence from it will not be comparable.

## Install

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2 and a Java 25 runtime.
2. Put Meteor Client **26.2-20261009.074120-35** in `mods/`.
3. Put `comet-<version>-mc26.2.jar` from `build/libs/` in `mods/` as well.
4. **Keep the matching `comet-<version>-mc26.2-source.zip` with the jar.** Comet's distributed jar is
   GPL-3.0-only and the source archive is the corresponding source for exactly this tested build; it
   must travel with the jar. Both files are produced by the same build and the source zip contains
   `src/` and `THIRD_PARTY_NOTICES.md`.
5. Launch the game, open Meteor's module list and enable `CrystalCombat` under **Comet**.

The module's info string shows the build version and short commit, for example
`v0.27.0-tester.2 fb59152-dirty`. A `-dirty` commit means the build was made from an uncommitted
working tree; the export's `build.txt` records the same value. Please include that version line in
feedback.

## PistonOnly beta checklist

Start with `Mode = PistonOnly`, **piston-trap** on, and all sixteen layouts on. Use a private test
world or an anarchy server where block loss is acceptable, and keep to one trap at a time.

- One trap at a time; a second opportunity waits until the active trap ends or you reconcile it.
- After manually removing the debris, run `.crystalcombat cleanup confirm` to release the trap slot.
  The command sends no packet and keeps the ledger hazards and sent risk as debt. Without it, PistonOnly
  stays inert with `manual cleanup required` in the info string.
- Support → piston → crystal → power order, each block confirmed before the next stage.
- The piston faces the target; the power block lands only after a unique crystal spawn binds.
- No attack before the authoritative piston extension events; exactly one swing.
- No packet after disable, death, disconnect or a mode switch; the ledger and risk stay occupied.
- Wrong facing, early power, ambiguous/foreign spawn or deadline expiry produce no further packets and
  leave **manual cleanup** visible.
- Record the chosen layout and the computed final position from the debug log.

## Debug log and incidents

Open `CrystalCombat`'s **Diagnostics** settings group:

- **Debug log** — off by default. When on, the last **five minutes** of the audited observations and
  incident markers are kept in a rolling in-memory buffer. Nothing is written to disk unless you
  export. The piston trap records its chosen layout, every stage transition, the computed final
  crystal position, the client-position cross-check, every block confirmation and the reason for
  every abort. These lines are diagnostics only: they never send or change a decision.
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

- DirectOnly and beta PistonOnly with Conservative and locked anti-suicide are the only accepted
  configurations; Hybrid and other profiles are refused, not silently approximated.
- The computed final crystal position is not server-confirmed. C21 suppresses crystal position sync;
  the only evidence is the piston block events plus the audited numeric push rules.
- **Manual cleanup is the default.** The trap does not mine, break or otherwise clean up its supports,
  piston, power block or any crystal after a trap ends, aborts, times out, or when you disable the
  module. Leave it and remove the blocks yourself only when safe; the trap slot stays occupied until
  reconciliation so other traps cannot start over the same debris. Run `.crystalcombat cleanup confirm`
  after removing the debris; it releases only the trap slot, sends no packets, and keeps hazards and
  sent risk as debt.
- Each of the sixteen layouts is beta and not yet server-confirmed. "All 16 measured" means the
  audited local traces agree on the 1.02 push; it does not mean every server behaves identically.
- There is no live module-level `buildStack` test (a source guard covers the trap/mode wiring); a real-client item is in
  the checklist and the controller/mode seams are unit-tested.
- Meteor's acknowledgement sequence is not exposed to addons, so the runtime's ack-based retirement
  stays unsatisfied on production servers; sent risk retires from observed damage/respawn instead.
- Phase 4 experimental tactics and both defence types are present but default off; the full combat UI is absent by design.
- Hurt-aware timing has no production raw-hit feed in this build, so turning it on does not change behaviour yet.
- Metrics exist in the runtime but have no UI surface in this build; most Phase 4.7 counters stay
  zero.
- The debug log is a five-minute in-memory ring; a crash loses it. Export before closing the game.
- This build does not update itself, does not download anything, and does not send telemetry.

## Checklist and feedback

- Work through [`checklist.md`](checklist.md): the **P5.2 tester build 2** section covers the
  per-layout matrix and the mode/target/death/disable scenarios; the earlier build 1 section still
  covers the DirectOnly baseline.
- Report with [`feedback-template.md`](feedback-template.md) and attach the export zip.
- Keep the jar and source zip together when sharing a build for testing.
