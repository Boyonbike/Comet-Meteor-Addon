# Auto Replenish

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **auto-replenish**. Open **Meteor → Comet → auto-replenish**.

Comet upgrades Meteor's existing **auto-replenish** in place. Its Player and Comet entries share one toggle, keybind and configuration. Existing **min-count**, **delay**, **offhand**, **unstackable**, **same-enchants**, **search-hotbar** and **excluded-items** settings remain in use, including Meteor's Auto Totem offhand lock.

- **stack-preference** chooses **SmallStacks** (default), **FullStacks** or **FirstMatch** across eligible inventory stacks. **smart-refill** waits until a remaining stack has one item; empty tracked slots can still be restored.
- **pause-on-use** defaults on. **max-refills-per-tick** defaults to 1 and allows up to 5 transfers per refill tick, subject to Meteor's existing delay.
- **refill-all-stackable** defaults on. Turn it off to select categories: blocks, food, tools, weapons, projectiles, ender pearls, potions, totems, golden apples and fireworks. Tools and weapons default off. Tools always keep their exact item/material; replacing broken or consumed unstackable items also requires Meteor's **unstackable** setting.
- **respect-custom-names** defaults on and requires the same custom name when replacing an emptied slot. Partly filled stacks always require matching item components, including names, enchantments and potion data, so incompatible stacks are never swapped over remaining items. **same-enchants** and **excluded-items** still apply.

Transfers use Meteor's targeted inventory clicks without opening the inventory. Comet replaces the original shift-click and delayed-click queue with direct transfers to the intended slot, so an earlier empty hotbar slot cannot redirect a refill. Transfers pause for open containers, carried cursor items and Comet's temporary tool/weapon loans. Manual screen changes reset tracked slots through Meteor's original lifecycle. These features have automated build and compatibility coverage; in-game movement, camera effects and server inventory acceptance still need verification.
