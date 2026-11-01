# CrystalCombat tester feedback

Copy this template into your report. Attach the export zip produced by
`.crystalcombat export confirm` unless you have a reason not to share it.

## What happened

<!-- One or two sentences. What did CrystalCombat do that you did not expect? -->

## What you expected

<!-- What should it have done instead? Reference a checklist item if one applies. -->

## Steps to reproduce

1.
2.
3.

## Build

- Comet info string (from the module, e.g. `v0.27.0-tester.2 fb59152-dirty`):
- Export `build.txt` version / commit / tester suffix:
- Meteor Client build: <!-- must be 26.2-20261009.074120-35 -->
- Minecraft / Fabric Loader / Java:

## Environment

- Server type and region (e.g. anarchy EU, private paper test server):
- Approximate ping (ms):
- Approximate server TPS during the fight:
- Other combat modules enabled (CrystalAura, AutoTotem, AutoEat, pacing, etc.):
- Any modules that also use rotations or hotbar slots:

## CrystalCombat settings

- Mode / profile: <!-- should be DirectOnly / Conservative -->
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

- Mode (`DirectOnly` / `PistonOnly` / `Hybrid`): <!-- PistonOnly is beta; Hybrid is shipped in its serialised stage -->
- Was `piston-trap` on? (yes/no):
- Chosen layout from the debug log (direction/height/power):
- Which of the sixteen layout toggles were enabled?
- Computed final crystal position vs the client's last observed position:
- Did the trap abort, time out or reach manual cleanup? What did the debug log say?
- Manual cleanup done? (yes/no) — the trap never mines or breaks its own blocks or crystals:
