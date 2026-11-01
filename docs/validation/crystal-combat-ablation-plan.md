# CrystalCombat tactic ablation plan (Phase 9)

Status: pre-registered before any tester evaluation; this file records the intended comparison and the
practically meaningful improvement **before** results are seen. Results belong in
`docs/validation/tester-round-<N>.md`, not here.

CrystalCombat is a beta. Tester evidence is anecdotal, not a controlled trial. No tactic may be described
as better than Meteor or any other client; the only claims allowed are the pre-specified observations
below, with their sample size and caveats.

## Common method

- Compare the same tester, server, ping band and target style with the tactic off and on. One variable at a
  time; keep every other setting fixed.
- Use the debug log and incident markers, not memory. Record: settings snapshot, commit hash, Meteor
  version, server type, ping, TPS, number of attempts, number of confirmed placements/attacks, observed
  target damage and every self/friend damage event.
- A "confirmed" placement is a `place_observed` for the bound crystal; a "confirmed" attack is an
  `attack_attempt` with an `explosion_observed` for the bound crystal. Nothing is inferred from a despawn
  and removal is never counted as damage.
- Stop the comparison and file a safety report if any self damage exceeds the predicted upper bound, any
  friend takes more than the configured ceiling, the module sends after disable/death/config change, or a
  wrong-entity/offhand event appears. A safety incident overrides any performance result.
- Minimum useful sample: 20 opportunities per arm per tester, and at least two testers per tactic. Fewer
  than that is labelled "insufficient evidence", not a result.

## 4.2 Motion prediction

**Arms:** `motion-prediction` off (Phase 3 baseline) vs on with `motion-prediction-ticks` 2 (Conservative).

**Scenario:** open-ground and one-block-hole targets that strafe or jump while a crystal is placed, then
stop within the prediction horizon. At least 20 placement+attack attempts per arm.

**Pre-specified meaningful improvement:** at least **+10% confirmed target damage per confirmed attack**
on moving targets, with **no increase** in self damage above the predicted upper bound and no friend
over-ceiling event. If confirmed damage is unchanged but placement count rises without a safety event, it
is "no measured benefit", not a promotion.

**Guardrail:** the prediction lower bound is 0 whenever it affects the policy estimate, so a lethal
override must never be attributed to this tactic.

## 4.3 Hurt-aware timing

**Arms:** `hurt-sync` off vs on.

**Availability:** the tester build has **no production raw-hit observation feed**. The pure/harness
behaviour can be reviewed, but a real-server ablation cannot measure this tactic yet. Do not claim a
benefit from a disabled feed.

**Scenario (when a feed exists):** target with a known invulnerability window from our previous hit, at
least 20 attack attempts per arm.

**Pre-specified meaningful improvement:** at least **+10% confirmed target damage per confirmed attack**
or at least **one fewer wasted attack per five attempts**, with no delay of a lethal opportunity and no
increase in self/friend exposure. If the feed remains absent, the tactic stays off and this plan records
"not evaluable".

## 4.4 Base and support placement

**Arms:** `support` off vs on (support placement exists only when no direct obsidian/bedrock base passes).

**Scenario:** holed or enclosed targets where no direct base is already available, at least 20 placement
attempts per arm. Record the single support block and the later crystal as separate actions.

**Pre-specified meaningful improvement:** at least **+1 confirmed placement per 10 attempts** in the
no-direct-base scenario, with no added self damage and no more than one support block per opportunity.
Extra packet/action cost is expected and is not a benefit.

## 4.5 Surround-gap placement

**Arms:** `surround-gap` off vs on.

**Scenario:** a holed target with one missing/replaceable surround block at feet or head height, at least
20 attempts per arm, four sides and both heights where the server allows it.

**Pre-specified meaningful improvement:** at least **+10% confirmed target damage per confirmed attack**
against holed targets, with no self/friend safety event and no mining/Speedmine action. Detection without
a legal base or reachable face is "no opportunity", not a failure.

## 4.6 Defence

**Arms:** `dangerous-crystal-break` off vs on, and (separately) `anti-piston` off vs on; do not combine
the two toggles in one arm. Both are default-off.

**Dangerous-crystal scenario:** an enemy end crystal is placed within our attack range and within
dangerous self-damage range of the tester, with no active own trap crystal in the same area. At least
20 observed enemy-crystal setups per arm; record every self-damage event, every defensive break attempt,
and whether the crystal was subsequently removed/exploded. A defensive break that is refused by the risk
budget is recorded as a correct refusal, not a missed action.

**Dangerous-crystal pre-specified meaningful improvement:** at least **one fewer self-damage event per
10 enemy-crystal setups** (or at least **20% lower total self damage from those setups** when event
counts are too small), with **no** offensive-plan starvation beyond the imminent-threat rule, no friend
over-ceiling event, and no increase in outstanding risk debt at the end of the encounter. A break that
fires only after the enemy detonates is not a benefit.

**Anti-piston scenario:** a scripted or observed enemy piston-crystal setup aimed at the tester from one
of four horizontal directions at feet or head height, with the front cell empty or holding a foreign
crystal, at least 10 setups per arm where the server permits building. Record every `place_blocker`,
defensive break, self-damage event, and whether the piston extended before the defence arrived.

**Anti-piston pre-specified meaningful improvement:** at least **one blocked setup per 10 attempted
setups** (blocker placed before extension, or safe pre-emptive crystal break), with no self damage above
the predicted upper bound, no friend over-ceiling event, and no mining/Speedmine packet. A blocker placed
after the piston has already extended is not a benefit. Packet cost is expected and is not a benefit.

Both 4.6 arms remain **not evaluable** until a tester build includes them; this section is pre-registration,
not evidence.

## Decision rule

A tactic is recommended for promotion only if the pre-specified improvement is observed in **both** arms
by at least two testers, **and** there is no open safety item. Otherwise it stays off by default. A tactic
that cannot be evaluated (for example the absent 4.3 raw-hit feed) is marked unavailable, never "passed".
