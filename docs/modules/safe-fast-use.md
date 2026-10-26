# Safe Fast Use

[Documentation index](../README.md) · [Project README](../../README.md)

Module: **safe-fast-use**. Open **Meteor → Comet → safe-fast-use**.

Enable **Safe Fast Use** (**safe-fast-use**) in Meteor's **Comet** category to use it in place of Meteor's Fast Use. Hold Use to place blocks quickly. With **prevent-shulker-open** on (default), a shulker placed during that hold will not open from repeated use: release Use and click again to open it normally. This covers all shulker colours and either hand. Sneaking still lets you place against a shulker.

- **mode**: **All** (default) speeds up every item; **Some** speeds up selected **items**, plus all block items when **blocks** is on (default off).
- **cooldown**: delay between uses in ticks, default **0**. Items outside the Some filter keep the normal four-tick delay.
- **prevent-shulker-open**: turn off to allow the normal repeated interaction with newly placed shulkers.

Safe Fast Use's cooldown takes precedence while it is enabled, including when Meteor's original Fast Use is also on. In **Some** mode with no items selected and **blocks** off nothing is sped up; the module says so when enabled and shows `Nothing selected`. Live Minecraft/server testing is still required, particularly placement from each hand, release and reclick, and sneaking against a box.
