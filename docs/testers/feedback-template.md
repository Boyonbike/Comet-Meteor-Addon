# CrystalCombat tester feedback

Copy this template into your report. Attach the export zip produced by
`.crystalcombat export confirm` unless you have a reason not to share it.

## Where to send it

Send the filled-in template and the report zip **directly to the developer** — not to a public issue
tracker, public chat or Discord. Use the same private channel that delivered this build. The developer
stores received reports at `testers/reports/tester-3/<tester>/` in this repository for the Phase 9
triage process; testers never need to commit anything themselves. See the
[tester README](README.md#sending-your-report) for the per-OS export paths and zipping steps.

## What happened

<!-- One or two sentences. What did CrystalCombat do that you did not expect? -->

## What you expected

<!-- What should it have done instead? Reference a checklist item if one applies. -->

## Steps to reproduce

1.
2.
3.

## Build

- Tester build (`tester.3`; jar `comet-0.27.0-tester.3-mc26.1.2.jar`):
- Comet info string (from the module, e.g. `[risk: strict] [v0.27.0-tester.3 <short commit> tester.3]`):
- Export `build.txt` version / commit / tester suffix:
- Meteor Client build: <!-- must be 26.1.2-20260810.135545-42 -->
- Minecraft / Fabric Loader / Java:

## Environment

- Server type and software (vanilla, Paper, Folia, proxy, other; version if known):
- Is this a local vanilla server, singleplayer, or another server? <!-- Barrier is only confirmed on
  local vanilla -->
- Server region (e.g. anarchy EU, private test server):
- Approximate ping (ms):
- Approximate server TPS during the fight:
- Other combat modules enabled (CrystalAura, AutoTotem, AutoEat, pacing, etc.):
- Any modules that also use rotations or hotbar slots:

## CrystalCombat settings

- Mode (`DirectOnly` / `PistonOnly` / `Hybrid`): <!-- Hybrid is serialised in this build -->
- Profile (`Conservative` / `Custom`): <!-- Conservative is the default -->
- `risk-model` used (`Strict` / `Barrier` / `Meteor-style`): <!-- Strict is the default -->
- `auto-cleanup` on or off? <!-- off by default -->
- Safety values changed from defaults:
- Pauses changed from defaults:
- Debug log on? (yes/no) — if yes, when was it enabled relative to the event?
- Export include identities on? (yes/no) <!-- leave off unless asked -->

## Evidence

- Export attached? (yes/no)
- Incident markers in the export's `debug-log.jsonl` (paste the `incident:` lines):
- Screenshot / clip (optional, especially for placement, rotation or ghost-crystal issues):
- Does the issue reproduce after disabling and re-enabling CrystalCombat?

## Extra context

<!-- Deaths/respawn, dimension change, server restart, low TPS, disconnects, friends nearby,
Weakness/eating, moving or holed target, anything else that was going on. -->

## Privacy check

- I did not include chat or server addresses.
- If identities were included (opt-in), I understand the export has real names/UUIDs.

## PistonOnly (beta) details

- Mode (`DirectOnly` / `PistonOnly` / `Hybrid`): <!-- PistonOnly is beta; Hybrid is serialised in this
  build -->
- Was `piston-trap` on? (yes/no):
- Chosen layout from the debug log (direction/height/power):
- Which of the sixteen layout toggles were enabled?
- Computed final crystal position vs the client's last observed position:
- If `auto-cleanup` was on, what did it remove before the deadline (power/piston/supports)?
- Did the trap abort, time out or reach manual cleanup? What did the debug log say?
- Manual cleanup done? (yes/no) — with `auto-cleanup` off the trap never mines or breaks its own blocks
  or crystals; with it on, record what it removed:
