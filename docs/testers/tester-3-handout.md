# CrystalCombat tester build 3 handout

This is the third CrystalCombat tester build (`tester.3`, jar
`comet-0.27.0-tester.3-mc26.1.2.jar`). It is not a release. All three modes can now be selected, and the
build adds the three-option risk model, the risk-debt/suspension display and beta automatic cleanup.
Test only where losing gear is acceptable. The full reference is the [tester README](README.md).

This edition is for **Minecraft 26.1.2** (Meteor build 42). `motion-prediction` and `hurt-sync` are
available starting with tester.4; record the version and build stamp with timing feedback.

## Install

- Minecraft 26.1.2, Fabric Loader 0.19.3 or newer, Java 25.
- Meteor Client **26.1.2-20260810.135545-42** (build 42) — use this exact pin.
- `comet-0.27.0-tester.3-mc26.1.2.jar` in `mods/`, and keep the matching
  `comet-0.27.0-tester.3-mc26.1.2-source.zip` with it.
- Enable `CrystalCombat` under **Comet**. The info string should show `[risk: ...]` followed by
  `[v0.27.0-tester.3 <short commit> tester.3]`; report that line with any issue.

## Test in this order

Run one mode per session. Do not switch modes in the middle of a fight on the first pass.

### 1. DirectOnly — basic crystal aura

Set **Mode** to `DirectOnly` and leave the default **Conservative** profile. In a safe test area,
check that it places obsidian, places an end crystal, breaks it and attacks it once, with no double
placement, no attack without a crystal, and no packet after you disable the module. This is the
production baseline; if the basics fail here, stop and report before testing the trap.

### 2. PistonOnly (beta)

Set **Mode** to `PistonOnly`, keep **piston-trap** on and all sixteen layouts on. Carry pistons, end
crystals, obsidian and both power items (redstone torch and redstone block); the trap may choose
either power, and a chosen plan re-checks its own. Keep to one trap at a time. Watch the support →
piston → crystal → power order, the computed final position, and exactly one attack after the piston
extends. If the info
string says `piston trap: missing materials: ...`, that is the new pre-dispatch check, not a trap
failure; add the named item and try again.

When the trap ends, remove the debris it lists and run `.crystalcombat cleanup confirm`. The command
sends nothing and keeps the hazard and sent risk. If the info string stays on
`manual cleanup required`, read the block list it gives you; that is the default path, not a crash.

### 3. Hybrid

Set **Mode** to `Hybrid`. Watch whether the direct plan or the trap owns the field: a committed or
live trap must serialise every direct plan, but a direct tactic with higher ranked utility may win
over an **uncommitted** trap preview. Report any case where both act at once, where the trap starts
over an existing trap's debris, or where the module appears stuck on `hybrid: idle` while a valid
target is in range.

## Risk model

Try the models in this order, one at a time. The active model is in the info string and the debug-log
header.

1. **Strict** (default) — establish the baseline. It may slow the aura in long fights because every
   crystal that might still hurt you stays counted until a later coherent health update. Note whether
   the aura is merely slow or actually stuck.
2. **Barrier** — **only on a local vanilla server**. It sends one side-effect-free check packet after
   our explosion, using the real vanilla prediction sequence, and retires harmless reservations after
   the server's block-change acknowledgement plus one tick. It is not verified on proxies, anti-cheat
   or other server software; use Strict there.
3. **Meteor-style** — last. It retires on the observed explosion or crystal removal and is the fastest
   and least protective when several hits land before a health update.

Only Barrier sends the extra packet. Strict and Meteor-style send no probe.

## Debt and suspension text

`show-risk-debt` (Render, on by default) draws in the top-left corner:

```text
risk debt: 2 outstanding (bound 12.00)
suspended: incoherent health
```

If the aura stalls with debt, record the model, the count and bound, and the suspension cause, then
note whether it clears after the next health update or explosion. If the debt never clears during a
quiet period, or the cause does not match what you did, stop and send the log. The overlay is
read-only; it never changes a plan or a packet.

## Auto-cleanup

Leave **auto-cleanup** off unless you are comfortable with the trap breaking its own blocks. It is
off by default and manual cleanup is the default path. When on, it removes only the power, piston and
supports this trap placed and saw confirmed, power first then piston then supports; it never touches
pre-existing or contested blocks and never detonates anything. It has a 40-tick deadline, and
**obsidian supports usually exceed it**, so expect manual cleanup for obsidian even when the power and
piston were removed. Disable, death, respawn, a mode switch or a connection change stops it
immediately. Wrong-block cleanup is a stop-and-report case.

## Debug log and export

1. Open `CrystalCombat` → **Diagnostics** and turn on **Debug log** before you test. It keeps about
   five minutes (up to 20,000 JSON lines) in memory; a crash loses it.
2. After the event, run `.crystalcombat export confirm`. This writes
   `comet/exports/crystalcombat-<timestamp>.zip` under your game directory and reports the path.
   `.crystalcombat export` only prints the plan and writes nothing.
3. Find the export under your game directory's `comet/exports/` folder. The README has the per-OS
   paths and the zipping steps, including custom launchers such as Prism, MultiMC, CurseForge,
   Modrinth and ATLauncher.
4. Copy the filled-in [`feedback-template.md`](feedback-template.md) next to the `comet` folder and
   zip `comet/exports/` together with it. Keep the export zip inside the report zip.

## Privacy

Other players' names and UUIDs are hashed with a per-export salt by default. Chat and server
addresses are never included. **Export include identities** is off by default; leave it off unless
the developer asks. Do not post an export publicly.

## Send the report

Send the report zip and the filled-in template **directly to the developer** through the same private
channel that delivered this build — not to a public issue tracker, public chat or Discord. The
developer files reports under `testers/reports/tester-3/<tester>/` in this repository for triage;
testers never need to commit anything. The README's **Sending your report** section has the full
steps.

## What to include

- The module info string (version, commit and `[risk: ...]`).
- Mode, profile, `risk-model` and whether `auto-cleanup` was on.
- Server type and software, and whether it is a local vanilla server, singleplayer or another server.
- What happened, what you expected, and the steps to reproduce.
- Timestamps (local time and how long after the debug log started) and the incident lines.
- Screenshots or a clip where useful, especially for placement, rotation, cleanup or ghost-crystal
  issues.
- The export zip.

## Known limitations

- Barrier's check-packet behaviour is confirmed only on a local vanilla server; proxies, anti-cheat
  and other server software are unverified.
- Beta auto-cleanup frequently ends in manual cleanup for obsidian supports because vanilla break time
  exceeds the 40-tick window.
- Inventory lending is deferred and not built.
- All sixteen piston layouts are beta and not server-confirmed.
- Head-height/double-push trap handling, render hiding, collision filtering and early reuse remain
  gated and are not shipped.
- The computed final crystal position is not server-confirmed; the client suppresses crystal position
  sync (C21).
- There is no in-game combat UI beyond the debt/suspension text.

## Stop and send the log

Stop testing, export the log with `.crystalcombat export confirm`, and send the report if any of these
happens:

- a crash (send the crash report and what you were doing; the in-memory debug ring may be lost);
- a stuck trap, or an info string that stays on manual cleanup with no blocks listed or no way to
  release the slot;
- auto-cleanup breaks a block it did not place, or breaks a pre-existing block;
- an attack without a crystal, at the wrong target, or after disable/death;
- the aura stays on `risk debt` or `suspended: ...` through a quiet period and never recovers.
