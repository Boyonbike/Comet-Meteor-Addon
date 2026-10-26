# Allay Farm

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **allay-farm**.

**allay-farm** breeds allays and gives them shulker boxes, using items from anywhere in your inventory. It takes from your offhand or hotbar first. An item in the main inventory is swapped into your held slot for the click, then swapped back. Each job can be turned off on its own.

- **breed** (on by default) duplicates allays dancing to a jukebox with amethyst shards. It only uses a shard on an allay that is dancing and off its 5-minute duplication cooldown. Any other allay with an empty hand would just take the shard as its held item. Allays holding a shulker are bred too, since holding an item stops neither dancing nor duplication.
- **give-shulkers** (on by default) gives a shulker box to each allay with an empty hand. Any shulker you carry can be given away, whatever its colour or contents, so keep the ones you want out of your inventory. While breeding, allays ready to duplicate are bred first and only given a shulker afterwards.
- **keep-population** (on by default) never gives out more shulkers than allays bred. The farm counts every allay duplication it sees while it is on, by you or anyone else nearby. It gives a shulker only while the allays it has given one to are fewer than that count. An allay holding a shulker is not assumed dead. It uses up one breed while it is alive, and that breed stays used if it dies. The breed is freed again once the allay is seen alive without its shulker. Even if every allay holding a shulker is killed, you never lose more allays than you bred. Until the first duplication it shows `Waiting for a breed`. Turning the farm off, or changing world or dimension, starts the count again from zero.

See [allay interaction and coordination](../guides/allay-behaviour.md) for reach/visibility, shared click timing and why allays cannot be attacked by the account they like.
