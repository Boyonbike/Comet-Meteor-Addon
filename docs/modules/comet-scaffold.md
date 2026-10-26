# Comet Scaffold

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **comet-scaffold**. Open **Meteor → Comet → comet-scaffold**.

**comet-scaffold** is a separate module with exactly two presets. It does not drive
Meteor's Scaffold. Both remain available; enabling either disables the other.
While Comet Scaffold is active, Block Coat and Smart Sponge pause without losing
their enabled state or settings, then resume through fresh validation.

| Preset | Purpose | Default floor policy |
| --- | --- | --- |
| **Narrow** (initial) | Only the swept footpath needed by sprinting, sprint-jumps, diagonals and corners. Earlier support deadlines take priority. | Follow grounded height, retaining it through ordinary jumps. |
| **Wide** | Fill a transverse strip out to the full reachable contact-face range, without a fixed nine-block width limit. Repair holes and alternate lateral opportunities. | Lock the plane when Wide is selected. |

Wide lets you move freely: it does not hold you to finish outer rows. The module
list reports confirmed contiguous width, desired width, holes and pending work;
outer coverage can remain incomplete if you move faster than placements finish.
Narrow looks ahead using current motion, steering and measured confirmation
latency. Both use confirmed supports, so edge protection can hold the immediate
footpath while waiting. Neither preset promises to keep up through arbitrary lag.

Each preset keeps its own advanced height, vertical offset, layer, lookahead and
reach-margin settings. Absolute Y means target block Y. Above-head placement and
a configurable downward key are available. Additional layers are explicit and
do not widen Narrow. Tower, auto-jump and edge sneaking are optional and off;
fixed planes suppress tower. The module pauses for screens, manual item use,
water, riding and flight. Existing Auto Replenish can replenish hotbar stacks.

**Materials.** Full non-falling blocks are selected by default. Empty allowed-blocks
accepts every eligible block; denied-blocks always excludes its entries. Sticky
switching saves slot packets and restores after 20 idle ticks. Normal, Silent and
Disabled are also available. Offhand blocks avoid hotbar switching. Unusual
BlockItem subclasses with special placement behaviour are conservatively excluded.

**Packets and timing.** Start with 150 observed packets per real second and a
50 ms minimum interaction interval. At most one interaction occurs per client
tick. A dedicated ordinary movement slot yields to Meteor rotations; no extra
rotation-only packets are created. These are starting settings to measure on
6b6t, not claimed server limits. Other modules' traffic is counted but cannot be
stopped by Scaffold. Air-place and container supports are separate options, off
by default; container interactions use proper prediction sequences.

Predictions remain quarantined until authoritative state is reconciled, including
through disabling, preset changes and handoffs. A visible ghost block or prediction
acknowledgement alone does not become a trusted anchor. If narrow rotation hooks
are incompatible with a newer Meteor build, placement pauses with a visible
compatibility status. The legacy Scaffold upgrade is checked separately.

**Diagnostics.** Summary (default), Off and Trace write bounded asynchronous JSONL
sessions to `meteor-client/comet/comet-scaffold-logs`, at most 4 MiB per session.
Summary includes exact build identifiers/settings, preset changes, five-second
success summaries, actual outgoing packet categories, sent/confirmed/retry counts,
latency, coverage, deadline misses and edge holds. Corrections/rejections/timeouts
include the bounded preceding two seconds. Trace adds packet/attempt detail.
Logger drops, capacity and disk errors are reported. Position and movement data
appear in diagnostic records. Counts mean observed local sends and authoritative
occupancy, not proof a packet caused a placement or reached the server.

For a controlled 6b6t comparison, use the same route/settings at normal Timer speed:
first stationary Wide, then walking/turning Wide, then Narrow sprinting and
sprint-jumping. Compare confirmed occupancy per packet, achieved width, distance,
edge holds, retries and corrections. Keep successful and failed session logs.
Automated geometry/lifecycle/packet/compatibility tests are included; single-player
and actual 6b6t acceptance require gameplay validation.
