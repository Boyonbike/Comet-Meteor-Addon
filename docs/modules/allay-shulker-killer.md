# Allay Shulker Killer

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **allay-shulker-killer**.

**allay-shulker-killer** attacks allays holding a shulker box so they drop it, along with anything they have collected. Meteor's **auto-weapon** picks the weapon if it is on.

- **no-sweep** (on by default) keeps the allays around the target unhurt. A fully charged sword hit on the ground sweeps every living thing within about a block of the target, with or without Sweeping Edge, which only makes the sweep stronger. So the killer jumps and hits while falling instead. That is a critical hit, which never sweeps and does half as much damage again. The jump is timed so you start falling just as the hit is fully charged. It needs room to jump: with a block above your head it doesn't attack and shows `No room to jump`. While it is on, Auto Weapon takes its best hotbar weapon, because a weapon pulled from your inventory hits later, possibly after you land. With **no-sweep** off, the killer hits at full charge wherever you stand.

See [allay interaction and coordination](../guides/allay-behaviour.md) for reach/visibility, shared click timing and why allays cannot be attacked by the account they like.
