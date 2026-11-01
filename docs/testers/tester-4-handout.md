# Tester build 4 — all-version timing access

`0.27.0-tester.4` makes motion prediction and hurt-sync selectable on Minecraft
1.21.11, 26.1.2 and 26.2. Enabling either, or changing it mid-session, no longer
refuses activation solely because of the Minecraft version.

Defaults are unchanged: timing features remain opt-in in the Timing settings group.
Choose the features for each test instead of enabling all interacting options at once.
All implemented combat modes and options are available on each version. Capability
checks, anti-suicide and the existing risk-budget rules still apply. Unimplemented
features are not created by removing the version restriction.

Hurt-sync still has no production raw-hit feed, as on 26.2: availability of its setting
does not establish effective hurt-aware timing in gameplay. Stronger-hit knockback
and motion behavior on the older versions remains unvalidated.

For each Minecraft version, test motion prediction alone, hurt-sync alone, then both.
Check both fresh activation and mid-session changes. Record the build stamp, server,
risk model, mode, relevant settings and combat recorder export with each result.
Use the existing [checklist](checklist.md) for the remaining combat scenarios.

The 26.2-only automated gametest harness is development tooling, not a feature gate
on the tester jars. No live gameplay validation is claimed by this build.

## Install this build

Use `build/libs/comet-0.27.0-tester.4-mc1.21.11.jar` with Minecraft 1.21.11
and this branch’s pinned Meteor build. Keep the matching source zip from
`build/distributions/` with it. Replace the previous Comet jar in `mods/` rather
than loading both. Confirm the in-game build stamp says `tester.4`.

## Build verification

Full offline tester.4 builds passed on all three versions: 2658 tests on 1.21.11,
2654 on 26.1.2 and 2779 on 26.2; zero failures, errors or skipped tests.
Documentation checks passed. No live gameplay validation was performed.
