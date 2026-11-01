# CrystalCombat ports overview

Updated 2026-11-01 after the three-version parity pass. Source baseline for the ports:
`363b410fa945e361c707b997a97556d0e5ef7398` (26.2).

All three ports are implemented and have passing tester builds. The supported combat
features match after correcting planned-cover upper bounds and support-cell validation
on both older versions. Motion prediction and hurt-sync remain gated on the older
versions, and the live gametest harness remains 26.2-only.

See [the parity report](version-parity-2026-11-01.md) for fixes, deliberate differences,
verification and remaining live-testing limitations.

| Minecraft | Tests passed | Failures/errors/skips | Tester jar |
|---|---:|---|---|
| 1.21.11 | 2658 | 0 / 0 / 0 | `build/libs/comet-0.27.0-tester.3-mc1.21.11.jar` |
| 26.1.2 | 2654 | 0 / 0 / 0 | `build/libs/comet-0.27.0-tester.3-mc26.1.2.jar` |
| 26.2 | 2779 | 0 / 0 / 0 | `build/libs/comet-0.27.0-tester.3-mc26.2.jar` |

Matching source archives are in `build/distributions/`. Combat purity checks passed
as part of each build. Documentation links were checked with `python3 scripts/check_docs.py`.
