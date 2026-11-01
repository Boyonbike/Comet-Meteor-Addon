# Three-version feature parity — 2026-11-01

**Update: tester.4** removes the motion-prediction/hurt-sync version restriction at
the project owner’s request. Both are now selectable on all three versions. Runtime
validation remains pending. The tester.3 findings below are historical.
See [tester.4 notes](../testers/tester-4-handout.md).

Scope: match existing features and fixes across Minecraft 1.21.11, 26.1.2 and 26.2,
while retaining the Java, Minecraft and Meteor adapters required by each version.
No remote branches were pushed.

## Shared baseline

All three versions register the same 31 modules and both combat commands, including
Chat Tools, Highway Fly, Boat Fly, Flight Dashboard, Speedmine, Comet Scaffold,
CrystalCombat and Combat Recorder. The 117 pure combat-core source files are byte
identical. All 180 combat trace fixtures are byte identical; their README files retain
version-specific provenance and replay instructions.

CrystalCombat's Direct, PistonOnly and Hybrid modes, three risk models, trap-material
gates, direct-versus-trap arbitration, vertical-push abort, auto-cleanup, risk debt UI,
recorder and tester export tooling are present in all three trees.

## Fixes applied by this parity pass

- On 1.21.11 and 26.1.2, planned obsidian no longer lowers the self/friend exposure
  upper bound. Only confirmed world cover may shield that bound. Planned cover still
  participates in target/faithful calculations, matching 26.2.
- On 1.21.11 and 26.1.2, combat support validation checks the supplied support block's
  top face instead of incorrectly stepping down a second time. This matches 26.2
  and the runtime and piston-world callers.
- All three versions have the same adapter regression tests: a floating solid support
  is accepted, air above a solid block is rejected, and planned cover does not shield
  self while confirmed obsidian does.

The shared regression expectations failed against the old port logic before the fixes.
The two older tests that asserted the incorrect overlay behavior were corrected.
Independent review found no critical or important findings in these changes.

## Deliberate differences and limits

This is supported-feature parity, not identical binaries or full experimental parity.

- Motion prediction and hurt-sync remain unavailable on 1.21.11 and 26.1.2, pending
  runtime evidence for their stronger-hit knockback model. They remain experimental
  on 26.2. This pass does not remove those compatibility gates.
- The client gametest harness and its server-ground-truth report tooling are currently
  26.2-only. Older branches reuse the same trace fixtures as offline regression inputs,
  not as evidence that their live server behavior is identical.
- Minecraft GUI/rendering, inventory/attack packets, Meteor mixin descriptors and Java
  toolchains remain version-specific. The 1.21.11 Speedmine helpers remain inline;
  newer versions extract equivalent logic into helper classes.
- Older adapters use the audited double-precision damage arithmetic; 26.2 also calls
  pinned vanilla mitigation helpers. These are not blindly exchanged between versions.
- Observation listener exceptions are isolated per listener in the older adapters and
  propagate in 26.2. This existing exceptional-path difference was reviewed and retained;
  it is not a missing user feature.
- No live client/server gameplay was tested in this pass.

## Verification

See the final test totals and tester artifacts below. Each version runs its own pinned
dependencies with `./gradlew cleanTest build -PtesterSuffix=tester.3 --offline`.

| Minecraft | Tests passed | Failures/errors/skips | Tester jar |
|---|---:|---|---|
| 1.21.11 | 2658 | 0 / 0 / 0 | `build/libs/comet-0.27.0-tester.3-mc1.21.11.jar` |
| 26.1.2 | 2654 | 0 / 0 / 0 | `build/libs/comet-0.27.0-tester.3-mc26.1.2.jar` |
| 26.2 | 2779 | 0 / 0 / 0 | `build/libs/comet-0.27.0-tester.3-mc26.2.jar` |

Matching source archives are in `build/distributions/`. Combat purity checks passed
as part of each build. Documentation links were checked with `python3 scripts/check_docs.py`.
