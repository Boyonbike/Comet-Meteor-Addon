# Speedmine

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **speedmine**. Open **Meteor → Comet → speedmine**.

**speedmine** ports THM Addons' packet miner to Minecraft 26.2. It keeps queueing,
double break, rebreak, client prediction, silent tool switching, TPS sync, optional
air mining and automatic enemy targeting. Source credits and licensing are in
[THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).

For the intended **6b6t bedrock** workflow, enable **auto-mine**, **anti-phase** and
**mine-bedrock**. Bedrock uses normal mining actions and hand swings, one target
at a time; this requires the server's bedrock-breaking mechanic and does not
make bedrock breakable on vanilla servers. `mine-bedrock` remains off by default.
Eligible blocks overlapping an enemy's actual hitbox take priority over surround
blocks. Existing Meteor friends, range limits and **never-mine-own** still apply.
The latter protects the area one block around your hitbox, so nearby phase targets
can be skipped deliberately. Enable **debug-auto-mine** to see the rejected phase
position and reason while a surround target is selected.

THM clan-service filtering and Paket Limiter integration are omitted. The bedrock
path and phase targeting have automated coverage; live 6b6t compatibility still
requires in-game verification.
